package com.tonyhe.fusion.service.ai.agentscope;

import com.tonyhe.fusion.controller.ai.vo.AiChatStreamRespVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamingEventHook.emitModelUsage 的单元测试。
 * <p>
 * 验证 AGENT_MODEL_USAGE 事件正确推送到 eventSink，
 * 包含主 Agent 和子 Agent 两种场景下的字段正确性。
 */
class StreamingEventHookModelUsageTest {

    @Test
    void emitModelUsage_mainAgent_correctFields() {
        List<AiChatStreamRespVO> emitted = new ArrayList<>();
        Sinks.Many<AiChatStreamRespVO> sink = Sinks.many().multicast().onBackpressureBuffer(64);
        sink.asFlux().subscribe(emitted::add);

        StreamingEventHook hook = new StreamingEventHook(
                sink, "conv-1", "msg-1", "ai_assistant_agent", new AgentCancellationToken(() -> false));

        hook.emitModelUsage(
                "script_writer", "AI 剧本创作",
                100L, "gpt-5.6-sol", "GPT-5.6-Sol",
                "config", false, null, null);

        assertEquals(1, emitted.size());
        AiChatStreamRespVO event = emitted.get(0);
        assertEquals("AGENT_MODEL_USAGE", event.getOutputType());
        assertEquals("conv-1", event.getConversationId());
        assertEquals("msg-1", event.getMessageId());
        assertNull(event.getAgentName(), "主 Agent 的 agentName 应为 null");
        assertNull(event.getParentToolCallId(), "主 Agent 的 parentToolCallId 应为 null");

        AiChatStreamRespVO.AgentModelUsageVO usage = event.getAgentModelUsage();
        assertNotNull(usage);
        assertEquals("script_writer", usage.getAgentType());
        assertEquals("AI 剧本创作", usage.getAgentName());
        assertEquals(100L, usage.getModelId());
        assertEquals("gpt-5.6-sol", usage.getModelCode());
        assertEquals("GPT-5.6-Sol", usage.getModelName());
        assertEquals("config", usage.getModelSource());
        assertFalse(usage.getSubAgent());
        assertNull(usage.getParentAgentType());
    }

    @Test
    void emitModelUsage_subAgent_correctFields() {
        List<AiChatStreamRespVO> emitted = new ArrayList<>();
        Sinks.Many<AiChatStreamRespVO> sink = Sinks.many().multicast().onBackpressureBuffer(64);
        sink.asFlux().subscribe(emitted::add);

        StreamingEventHook hook = new StreamingEventHook(
                sink, "conv-2", "msg-2", "ai_assistant_agent", new AgentCancellationToken(() -> false));

        hook.emitModelUsage(
                "script_doctor", "剧本医生",
                200L, "grok-4.6", "Grok-4.6",
                "config", true, "script_full_parse", "tool-call-123");

        assertEquals(1, emitted.size());
        AiChatStreamRespVO event = emitted.get(0);
        assertEquals("AGENT_MODEL_USAGE", event.getOutputType());
        assertEquals("剧本医生", event.getAgentName(), "子 Agent 的 agentName 应为 Agent 名称");
        assertEquals("tool-call-123", event.getParentToolCallId(), "子 Agent 的 parentToolCallId 应正确设置");

        AiChatStreamRespVO.AgentModelUsageVO usage = event.getAgentModelUsage();
        assertNotNull(usage);
        assertEquals("script_doctor", usage.getAgentType());
        assertEquals("剧本医生", usage.getAgentName());
        assertEquals(200L, usage.getModelId());
        assertEquals("grok-4.6", usage.getModelCode());
        assertEquals("Grok-4.6", usage.getModelName());
        assertEquals("config", usage.getModelSource());
        assertTrue(usage.getSubAgent());
        assertEquals("script_full_parse", usage.getParentAgentType());
    }

    @Test
    void emitModelUsage_inheritedModel_nullModelFields() {
        List<AiChatStreamRespVO> emitted = new ArrayList<>();
        Sinks.Many<AiChatStreamRespVO> sink = Sinks.many().multicast().onBackpressureBuffer(64);
        sink.asFlux().subscribe(emitted::add);

        StreamingEventHook hook = new StreamingEventHook(
                sink, "conv-3", "msg-3", "ai_assistant_agent", new AgentCancellationToken(() -> false));

        hook.emitModelUsage(
                "episode_script_creator", "分集剧本创作器",
                null, null, "继承父Agent模型",
                "inherited", true, "script_full_parse", "tool-call-456");

        assertEquals(1, emitted.size());
        AiChatStreamRespVO.AgentModelUsageVO usage = emitted.get(0).getAgentModelUsage();
        assertNotNull(usage);
        assertNull(usage.getModelId());
        assertNull(usage.getModelCode());
        assertEquals("继承父Agent模型", usage.getModelName());
        assertEquals("inherited", usage.getModelSource());
        assertTrue(usage.getSubAgent());
    }

    @Test
    void emitModelUsage_multipleEmits_allDelivered() {
        List<AiChatStreamRespVO> emitted = new ArrayList<>();
        Sinks.Many<AiChatStreamRespVO> sink = Sinks.many().multicast().onBackpressureBuffer(64);
        sink.asFlux().subscribe(emitted::add);

        StreamingEventHook hook = new StreamingEventHook(
                sink, "conv-4", "msg-4", "ai_assistant_agent", new AgentCancellationToken(() -> false));

        hook.emitModelUsage("agent_a", "Agent A", 1L, "model-a", "Model A", "explicit", false, null, null);
        hook.emitModelUsage("agent_b", "Agent B", 2L, "model-b", "Model B", "config", true, "agent_a", "tc-1");
        hook.emitModelUsage("agent_c", "Agent C", null, null, "继承", "inherited", true, "agent_b", "tc-2");

        assertEquals(3, emitted.size());
        assertEquals("agent_a", emitted.get(0).getAgentModelUsage().getAgentType());
        assertEquals("agent_b", emitted.get(1).getAgentModelUsage().getAgentType());
        assertEquals("agent_c", emitted.get(2).getAgentModelUsage().getAgentType());
    }
}
