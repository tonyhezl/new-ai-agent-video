package com.tonyhe.fusion.service.ai.agentscope;

import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeToolAdapterTests {

    @Test
    void callAsyncShouldPreserveToolCallIdAndName() {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ToolExecutionContext toolContext = ToolExecutionContext.builder().build();
        when(toolExecutor.getToolName()).thenReturn("asset_query");
        when(toolExecutor.getToolDescription()).thenReturn("query asset");
        when(toolExecutor.getParametersSchema()).thenReturn("{}");
        when(toolExecutor.execute(eq("{\"keyword\":\"cat\"}"), any(ToolExecutionContext.class)))
                .thenReturn("ok");

        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter(
                toolExecutor,
                toolContext,
                new AgentCancellationToken(() -> false));

        ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                .id("call-123")
                .name("asset_query")
                .input(Map.of("keyword", "cat"))
                .build();

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(toolUseBlock)
                .input(Map.of("keyword", "cat"))
                .build();

        ToolResultBlock result = adapter.callAsync(param).block();

        assertEquals("call-123", result.getId());
        assertEquals("asset_query", result.getName());
                TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().getFirst());
                assertEquals("ok", output.getText());
    }
}