package com.tonyhe.fusion.service.ai.provider;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiToolResponseAwareChatFormatterTests {

    @Test
    void formatShouldMergeParallelToolResponsesIntoSingleUserTurnInCallOrder() {
        GeminiToolResponseAwareChatFormatter formatter = new GeminiToolResponseAwareChatFormatter();

        Msg assistantToolCall = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("get_project")
                                .input(Map.of("projectId", 2))
                                .build(),
                        ToolUseBlock.builder()
                                .id("call-2")
                                .name("get_storyboard")
                                .input(Map.of("storyboardId", 11))
                                .build())
                .build();

        Msg secondToolResult = Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.text("storyboard-ok").withIdAndName("call-2", "get_storyboard"))
                .build();

        Msg firstToolResult = Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.text("project-ok").withIdAndName("call-1", "get_project"))
                .build();

        List<Content> contents = formatter.format(List.of(assistantToolCall, secondToolResult, firstToolResult));

        assertEquals(2, contents.size());
        assertEquals("model", contents.getFirst().role().orElseThrow());
        assertEquals(2, contents.getFirst().parts().orElseThrow().size());

        Content toolResponseTurn = contents.get(1);
        assertEquals("user", toolResponseTurn.role().orElseThrow());

        List<Part> responseParts = toolResponseTurn.parts().orElseThrow();
        assertEquals(2, responseParts.size());
        assertFunctionResponse(responseParts.get(0), "call-1", "get_project", "project-ok");
        assertFunctionResponse(responseParts.get(1), "call-2", "get_storyboard", "storyboard-ok");
    }

    private void assertFunctionResponse(Part part, String expectedId, String expectedName, String expectedOutput) {
        FunctionResponse functionResponse = part.functionResponse().orElseThrow();
        assertEquals(expectedId, functionResponse.id().orElseThrow());
        assertEquals(expectedName, functionResponse.name().orElseThrow());
        assertEquals(expectedOutput, functionResponse.response().orElseThrow().get("output"));
    }
}