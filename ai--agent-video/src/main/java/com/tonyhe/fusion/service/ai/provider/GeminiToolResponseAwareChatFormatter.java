package com.tonyhe.fusion.service.ai.provider;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMediaConverter;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class GeminiToolResponseAwareChatFormatter extends GeminiChatFormatter {

    private static final String ROLE_MODEL = "model";
    private static final String ROLE_USER = "user";
    private static final String THOUGHT_SIGNATURE = "thoughtSignature";

    private final GeminiMediaConverter mediaConverter = new GeminiMediaConverter();

    @Override
    protected List<Content> doFormat(List<Msg> messages) {
        List<Content> contents = new ArrayList<>();
        List<Part> pendingToolResponses = new ArrayList<>();
        List<String> expectedToolResponseOrder = new ArrayList<>();

        for (Msg message : messages) {
            List<Part> parts = new ArrayList<>();
            List<ContentBlock> blocks = message.getContent();
            if (blocks == null || blocks.isEmpty()) {
                continue;
            }

            for (ContentBlock block : blocks) {
                if (block instanceof ToolResultBlock toolResultBlock) {
                    pendingToolResponses.add(buildToolResponsePart(toolResultBlock));
                    continue;
                }

                flushToolResponses(contents, pendingToolResponses, expectedToolResponseOrder);

                if (block instanceof TextBlock textBlock) {
                    parts.add(Part.builder().text(textBlock.getText()).build());
                    continue;
                }

                if (block instanceof ToolUseBlock toolUseBlock) {
                    parts.add(buildToolUsePart(toolUseBlock));
                    if (toolUseBlock.getId() != null) {
                        expectedToolResponseOrder.add(toolUseBlock.getId());
                    }
                    continue;
                }

                if (block instanceof ImageBlock imageBlock) {
                    parts.add(mediaConverter.convertToInlineDataPart(imageBlock));
                    continue;
                }

                if (block instanceof AudioBlock audioBlock) {
                    parts.add(mediaConverter.convertToInlineDataPart(audioBlock));
                    continue;
                }

                if (block instanceof VideoBlock videoBlock) {
                    parts.add(mediaConverter.convertToInlineDataPart(videoBlock));
                    continue;
                }

                if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock when formatting message for Gemini API");
                    continue;
                }

                log.warn("Unsupported block type: {} in the message, skipped.", block.getClass().getSimpleName());
            }

            if (!parts.isEmpty()) {
                contents.add(Content.builder()
                        .role(convertRole(message.getRole()))
                        .parts(parts)
                        .build());
            }
        }

        flushToolResponses(contents, pendingToolResponses, expectedToolResponseOrder);
        return contents;
    }

    private Part buildToolUsePart(ToolUseBlock toolUseBlock) {
        FunctionCall.Builder functionCallBuilder = FunctionCall.builder()
                .id(toolUseBlock.getId())
                .name(toolUseBlock.getName())
                .args(resolveToolUseArgs(toolUseBlock));

        Map<String, Object> metadata = toolUseBlock.getMetadata();
        Part.Builder partBuilder = Part.builder().functionCall(functionCallBuilder.build());
        if (metadata != null) {
            Object thoughtSignature = metadata.get(THOUGHT_SIGNATURE);
            if (thoughtSignature instanceof byte[] bytes) {
                partBuilder.thoughtSignature(bytes);
            }
        }
        return partBuilder.build();
    }

    private Map<String, Object> resolveToolUseArgs(ToolUseBlock toolUseBlock) {
        String content = toolUseBlock.getContent();
        if (content == null || content.isEmpty()) {
            return toolUseBlock.getInput();
        }

        try {
            Object parsed = JsonUtils.getJsonCodec().fromJson(content, Map.class);
            if (parsed instanceof Map<?, ?> parsedMap) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return normalized;
            }
        } catch (Exception e) {
            log.warn("Failed to parse content as JSON, falling back to input map: {}", e.getMessage());
        }

        return toolUseBlock.getInput();
    }

    private Part buildToolResponsePart(ToolResultBlock toolResultBlock) {
        Map<String, Object> response = new HashMap<>();
        response.put("output", convertToolResultToString(toolResultBlock.getOutput()));

        FunctionResponse functionResponse = FunctionResponse.builder()
                .id(toolResultBlock.getId())
                .name(toolResultBlock.getName())
                .response(response)
                .build();

        return Part.builder()
                .functionResponse(functionResponse)
                .build();
    }

    private void flushToolResponses(List<Content> contents,
                                    List<Part> pendingToolResponses,
                                    List<String> expectedToolResponseOrder) {
        if (pendingToolResponses.isEmpty()) {
            return;
        }

        if (!expectedToolResponseOrder.isEmpty()) {
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < expectedToolResponseOrder.size(); i++) {
                order.putIfAbsent(expectedToolResponseOrder.get(i), i);
            }
            pendingToolResponses.sort((left, right) -> Integer.compare(
                    toolResponseOrder(left, order),
                    toolResponseOrder(right, order)));
            expectedToolResponseOrder.clear();
        }

        contents.add(Content.builder()
                .role(ROLE_USER)
                .parts(new ArrayList<>(pendingToolResponses))
                .build());
        pendingToolResponses.clear();
    }

    private int toolResponseOrder(Part part, Map<String, Integer> order) {
        return part.functionResponse()
                .flatMap(FunctionResponse::id)
                .map(id -> order.getOrDefault(id, Integer.MAX_VALUE))
                .orElse(Integer.MAX_VALUE);
    }

    private String convertRole(MsgRole role) {
        return role == MsgRole.ASSISTANT ? ROLE_MODEL : ROLE_USER;
    }
}