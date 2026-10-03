package com.tonyhe.fusion.service.ai;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具执行器注册中心
 * <p>
 * 自动收集所有 {@link ToolExecutor} Bean，按 toolName 建立索引，
 * 提供按名称查找和执行工具的能力。
 */
@Component
@Slf4j
public class ToolExecutorRegistry {

    private final Map<String, ToolExecutor> executorMap;

    public ToolExecutorRegistry(List<ToolExecutor> toolExecutors) {
        this.executorMap = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getToolName, Function.identity()));
        log.info("[ToolExecutorRegistry] 已注册 {} 个工具执行器: {}",
                executorMap.size(), executorMap.keySet());
    }

    /**
     * 执行指定工具
     *
     * @param toolName  工具名称（对应 ToolExecutor.getToolName()）
     * @param toolInput 工具入参 JSON
     * @param context   工具执行上下文（包含用户身份等信息）
     * @return 工具执行结果 JSON
     */
    public String execute(String toolName, String toolInput, ToolExecutionContext context) {
        ToolExecutor executor = executorMap.get(toolName);
        if (executor == null) {
            log.warn("[ToolExecutorRegistry] 未找到工具执行器: {}, 返回默认结果", toolName);
            return JSONUtil.toJsonStr(Map.of(
                    "status", "not_implemented",
                    "message", "工具 " + toolName + " 尚未实现执行器",
                    "toolName", toolName));
        }
        try {
            return executor.execute(toolInput, context);
        } catch (Exception e) {
            log.error("[ToolExecutorRegistry] 工具执行失败: name={}", toolName, e);
            return JSONUtil.toJsonStr(Map.of(
                    "status", "error",
                    "message", "工具执行失败: " + e.getMessage(),
                    "toolName", toolName));
        }
    }

    /**
     * 查找工具执行器
     *
     * @param toolName 工具名称
     * @return 工具执行器，不存在返回 null
     */
    public ToolExecutor findExecutor(String toolName) {
        return executorMap.get(toolName);
    }
}
