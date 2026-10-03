package com.tonyhe.fusion.service.ai.agentscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope 子 Agent 工具适配器。
 * <p>
 * 与 AgentScope 默认 SubAgentTool 不同，这里在 callAsync 中能拿到当前 ToolCallParam，
 * 因此可以把新创建的子 Agent 实例和父 tool_call_id 精确绑定，支持同名子 Agent 并行执行。
 */
@Slf4j
public class AgentScopeSubAgentToolAdapter implements AgentTool {

    private final String toolName;
    private final String description;
    private final Supplier<ReActAgent> agentFactory;
    private final StreamingEventHook streamingHook;
    private final AgentCancellationToken cancellationToken;
    private final Consumer<String> modelUsageEmitter;

    public AgentScopeSubAgentToolAdapter(String toolName,
            String description,
            Supplier<ReActAgent> agentFactory,
            StreamingEventHook streamingHook,
            AgentCancellationToken cancellationToken,
            Consumer<String> modelUsageEmitter) {
        this.toolName = toolName;
        this.description = description;
        this.agentFactory = agentFactory;
        this.streamingHook = streamingHook;
        this.cancellationToken = cancellationToken;
        this.modelUsageEmitter = modelUsageEmitter;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "发送给子 Agent 的任务消息")),
                "required", List.of("message"),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            cancellationToken.throwIfCancelled();

            ReActAgent subAgent = agentFactory.get();
            ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
            String parentToolCallId = toolUseBlock != null ? toolUseBlock.getId() : null;

            // 推送子 Agent 模型调用追踪事件（在订阅建立后调用，且已知 parentToolCallId）
            if (modelUsageEmitter != null) {
                modelUsageEmitter.accept(parentToolCallId);
            }

            streamingHook.bindSubAgentCall(subAgent, parentToolCallId);

            String inputMessage = buildInputMessage(param);
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具被调用: name={}, parentToolCallId={}, input={}",
                    getName(), parentToolCallId, inputMessage);

            if (inputMessage == null || inputMessage.isBlank()) {
                return Mono.just(buildToolResult(param, JSONUtil.toJsonStr(Map.of(
                        "status", "error",
                        "message", "子 Agent 调用缺少 message 参数",
                        "toolName", getName()))));
            }

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(inputMessage)
                    .build();

            return subAgent.call(userMsg)
                    .map(finalMsg -> {
                        cancellationToken.throwIfCancelled();
                        String result = finalMsg != null ? finalMsg.getTextContent() : "";

                        // 校正 Agent 专用日志
                        if (getName().startsWith("review_")) {
                            if ("review_script".equals(getName())) {
                                // 剧本医生：输出为诊断报告文本，不剥离代码围栏，不尝试JSON解析
                                log.info("[ScriptDoctor] 剧本诊断工具执行完成: name={}, inputLength={}, outputLength={}",
                                        getName(), inputMessage.length(), result.length());
                                log.info("[ScriptDoctor] 诊断报告预览: name={}, preview={}",
                                        getName(), result.length() > 500 ? result.substring(0, 500) + "..." : result);
                            } else {
                                // Prompt 校正 Agent：去除 ```json ... ``` 或 ``` ... ``` 围栏
                                String cleaned = stripCodeFences(result);
                                if (!cleaned.equals(result)) {
                                    log.info("[PromptReviewer] 已去除markdown代码围栏: name={}, originalLength={}, cleanedLength={}",
                                            getName(), result.length(), cleaned.length());
                                }

                                // 尝试从文本中提取 JSON 对象（处理 LLM 在 JSON 前后输出多余文字的情况）
                                String jsonStr = extractJsonString(cleaned);
                                if (jsonStr != null && !jsonStr.equals(cleaned)) {
                                    log.info("[PromptReviewer] 已从文本中提取JSON: name={}, originalLength={}, extractedLength={}",
                                            getName(), cleaned.length(), jsonStr.length());
                                    cleaned = jsonStr;
                                }

                                log.info("[PromptReviewer] 校正工具执行完成: name={}, inputLength={}, outputLength={}",
                                        getName(), inputMessage.length(), cleaned.length());

                                try {
                                    cn.hutool.json.JSONObject resultJson = cn.hutool.json.JSONUtil.parseObj(cleaned);
                                    boolean changed = resultJson.getBool("changed", false);
                                    String revised = resultJson.getStr("revisedPrompt", "");
                                    cn.hutool.json.JSONObject checks = resultJson.getJSONObject("checks");
                                    log.info("[PromptReviewer] 校正结果: name={}, changed={}, checks={}, revisedPreview={}",
                                            getName(), changed, checks != null ? checks.toString() : "null",
                                            revised.length() > 200 ? revised.substring(0, 200) + "..." : revised);
                                    cn.hutool.json.JSONArray issues = resultJson.getJSONArray("issues");
                                    if (issues != null && !issues.isEmpty()) {
                                        log.info("[PromptReviewer] 发现问题数: name={}, issues={}", getName(), issues.size());
                                        for (int i = 0; i < issues.size(); i++) {
                                            log.info("[PromptReviewer] 问题[{}]: {}", i, issues.get(i));
                                        }
                                    }
                                    result = cleaned;
                                } catch (Exception parseEx) {
                                    // JSON 解析失败，尝试用正则提取 revisedPrompt 作为兜底
                                    String fallbackRevised = extractRevisedPrompt(cleaned);
                                    if (StrUtil.isNotBlank(fallbackRevised)) {
                                        log.warn("[PromptReviewer] JSON解析失败，正则提取revisedPrompt成功: name={}, revisedPreview={}",
                                                getName(), fallbackRevised.length() > 200 ? fallbackRevised.substring(0, 200) + "..." : fallbackRevised);
                                        result = JSONUtil.toJsonStr(Map.of(
                                                "revisedPrompt", fallbackRevised,
                                                "changed", true,
                                                "issues", List.of(),
                                                "checks", Map.of(
                                                        "spatialConsistency", "unknown",
                                                        "shotLanguage", "unknown",
                                                        "refImageIndex", "unknown",
                                                        "capabilityCompliance", "unknown",
                                                        "genreConsistency", "unknown",
                                                        "negativeAndMotion", "unknown")));
                                    } else {
                                        log.warn("[PromptReviewer] 校正结果解析失败(非JSON): name={}, resultPreview={}",
                                                getName(), cleaned.length() > 300 ? cleaned.substring(0, 300) + "..." : cleaned);
                                        result = cleaned;
                                    }
                                }
                            }
                        }

                        return buildToolResult(param, result);
                    });
        }).onErrorResume(AgentCancelledException.class, e -> {
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具执行被取消: name={}", getName());
            return Mono.error(e);
        }).onErrorResume(e -> {
            if (e instanceof AgentCancelledException) {
                return Mono.error(e);
            }
            log.error("[AgentScopeSubAgentToolAdapter] 子Agent工具执行失败: name={}", getName(), e);
            String errorResult = JSONUtil.toJsonStr(Map.of(
                    "status", "error",
                    "message", "子 Agent 执行失败: " + e.getMessage(),
                    "toolName", getName()));
            return Mono.just(buildToolResult(param, errorResult));
        });
    }

    private String stripCodeFences(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            // 去掉开头的 ```json 或 ```
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // 去掉结尾的 ```
            trimmed = trimmed.trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    /**
     * 从文本中提取 JSON 对象字符串。
     * 处理 LLM 在 JSON 前后输出多余文字、代码围栏不在开头等情况。
     *
     * @param text 待解析的文本
     * @return 提取到的 JSON 字符串，如果无法提取则返回 null
     */
    private String extractJsonString(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();

        // 如果已经是合法 JSON，直接返回
        try {
            JSONUtil.parseObj(trimmed);
            return trimmed;
        } catch (Exception ignored) {
            // 继续尝试提取
        }

        // 尝试找到第一个 { 和最后一个 } 之间的内容
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = trimmed.substring(firstBrace, lastBrace + 1);
            try {
                JSONUtil.parseObj(candidate);
                return candidate;
            } catch (Exception ignored) {
                // 不是合法 JSON，继续
            }
        }

        return null;
    }

    private static final Pattern REVISED_PROMPT_PATTERN = Pattern.compile(
            "\"revisedPrompt\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * 当 JSON 解析失败时，尝试用正则从文本中提取 revisedPrompt 字段的值。
     * 适用于 JSON 被截断或包含未转义字符导致整体解析失败的场景。
     *
     * @param text 包含 revisedPrompt 字段的文本
     * @return 提取到的 revisedPrompt 值，如果无法提取则返回 null
     */
    private String extractRevisedPrompt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = REVISED_PROMPT_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
        }
        return null;
    }

    private String buildInputMessage(ToolCallParam param) {
        if (param == null || param.getInput() == null || param.getInput().isEmpty()) {
            return "";
        }
        Object message = param.getInput().get("message");
        if (message != null) {
            String messageText = String.valueOf(message);
            if (!messageText.isBlank()) {
                return messageText;
            }
        }
        return JSONUtil.toJsonStr(param.getInput());
    }

    private ToolResultBlock buildToolResult(ToolCallParam param, String result) {
        ToolResultBlock block = ToolResultBlock.text(result != null ? result : "");
        ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
        if (toolUseBlock == null) {
            return block;
        }
        return block.withIdAndName(toolUseBlock.getId(), toolUseBlock.getName());
    }
}