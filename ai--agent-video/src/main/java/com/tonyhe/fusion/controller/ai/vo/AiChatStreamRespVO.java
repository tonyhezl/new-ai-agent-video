package com.tonyhe.fusion.controller.ai.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * AI 助手流式响应 VO（SSE 事件数据）
 */
@Data
@Accessors(chain = true)
public class AiChatStreamRespVO {

    /** 消息 ID */
    private String messageId;

    /** 会话 ID */
    private String conversationId;

    /** 输出类型：REASONING / CONTENT / TOOL_CALL / TOOL_FINISHED / SUB_AGENT_FINISHED / AGENT_MODEL_USAGE / TOKEN_USAGE / DONE / ERROR / CANCELLED */
    private String outputType;

    /** 文本内容（增量） */
    private String content;

    /** 思考内容（增量） */
    private String reasoningContent;

    /** 思考开始时间 */
    private Long reasoningStartTime;

    /** 思考耗时（毫秒，首个 CONTENT 事件携带） */
    private Long reasoningDurationMs;

    /** 工具调用信息 */
    private List<ToolCallVO> toolCalls;

    /** 工具调用 ID */
    private String toolCallId;

    /** 工具名 */
    private String toolName;

    /** 工具执行结果 */
    private String toolResult;

    /** 工具执行状态：success / error */
    private String toolStatus;

    /** 父级工具调用 ID（子 Agent 输出时用于归属映射） */
    private String parentToolCallId;

    /** 产生此事件的 Agent 名称 */
    private String agentName;

    /** 是否结束 */
    private Boolean finished;

    /** 错误信息 */
    private String error;

    /** Token 用量（PostReasoning 事件携带） */
    private TokenUsageVO tokenUsage;

    /** Agent 模型调用信息（AGENT_MODEL_USAGE 事件携带） */
    private AgentModelUsageVO agentModelUsage;

    @Data
    @Accessors(chain = true)
    public static class ToolCallVO {
        private String id;
        private String name;
        private String arguments;
    }

    @Data
    @Accessors(chain = true)
    public static class TokenUsageVO {
        /** 输入 token 数 */
        private int inputTokens;
        /** 输出 token 数 */
        private int outputTokens;
        /** 总 token 数 */
        private int totalTokens;
        /** 模型名称 */
        private String modelCode;
    }

    @Data
    @Accessors(chain = true)
    public static class AgentModelUsageVO {
        /** Agent 类型标识 */
        private String agentType;
        /** Agent 显示名称 */
        private String agentName;
        /** 使用的模型 ID */
        private Long modelId;
        /** 使用的模型 code */
        private String modelCode;
        /** 使用的模型名称 */
        private String modelName;
        /** 模型来源：explicit(请求指定) / config(绑定配置) / default(默认模型) / inherited(继承父Agent) */
        private String modelSource;
        /** 是否为子 Agent */
        private Boolean subAgent;
        /** 父 Agent 类型（子 Agent 时携带） */
        private String parentAgentType;
    }
}
