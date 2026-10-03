package com.tonyhe.fusion.service.ai.agentscope;

/**
 * Agent 执行取消异常
 * <p>
 * 当用户取消 Agent 执行时，在 Hook 或工具执行中抛出此异常，
 * 以中断 AgentScope ReActAgent 的执行循环。
 */
public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException(String message) {
        super(message);
    }
}
