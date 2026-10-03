package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.script.Script;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 追加剧本原文工具（append_script_raw_content）
 * <p>
 * 供 script_writer Agent 按集逐段追加写入 Script.rawContent。
 * 每完成一集创作调用一次，避免一次性把整个多集剧本作为大字符串传给工具导致 token 翻倍。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppendScriptRawContentToolExecutor implements ToolExecutor {

    private final ScriptService scriptService;

    @Override
    public String getToolName() {
        return "append_script_raw_content";
    }

    @Override
    public String getDisplayName() {
        return "追加剧本文本";
    }

    @Override
    public String getToolDescription() {
        return "将单集剧本正文追加到 Script.rawContent 末尾。每完成一集创作就调用一次，不要攒到最后一次性提交。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptId": { "type": "number", "description": "剧本ID" },
                        "episodeNumber": { "type": "number", "description": "当前集数，从1开始" },
                        "episodeContent": { "type": "string", "description": "本集完整正文（含分集标记、场景标题、对白）" }
                    },
                    "required": ["scriptId", "episodeNumber", "episodeContent"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long scriptId = params.getLong("scriptId");
            Integer episodeNumber = params.getInt("episodeNumber");
            String episodeContent = params.getStr("episodeContent");

            if (scriptId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 scriptId").toString();
            }
            if (episodeNumber == null || episodeNumber <= 0) {
                return JSONUtil.createObj().set("status", "error").set("message", "episodeNumber 必须为正整数").toString();
            }
            if (StrUtil.isBlank(episodeContent)) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 episodeContent").toString();
            }

            Script script = scriptService.getById(scriptId);
            if (script == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "剧本不存在").toString();
            }

            String existing = StrUtil.blankToDefault(script.getRawContent(), "");
            String trimmed = existing.trim();
            String updated = trimmed.isEmpty()
                    ? episodeContent.trim()
                    : trimmed + "\n\n" + episodeContent.trim();

            script.setRawContent(updated);
            // 标记为AI生成
            script.setAiGenerated(Boolean.TRUE);
            scriptService.update(script);

            log.info("[append_script_raw_content] 已追加第{}集剧本: scriptId={}, totalLength={}",
                    episodeNumber, scriptId, updated.length());

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("episodeNumber", episodeNumber)
                    .set("totalLength", updated.length())
                    .set("message", "第" + episodeNumber + "集已追加写入").toString();
        } catch (Exception e) {
            log.error("追加剧本文本失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "追加失败: " + e.getMessage()).toString();
        }
    }
}
