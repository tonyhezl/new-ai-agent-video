package com.tonyhe.fusion.service.ai.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KeyedSequentialAgentToolAdapterTests {

    @Test
    void sameSceneWaitsWhileDifferentSceneStartsConcurrently() throws InterruptedException {
        Sinks.One<Void> releaseFirstShot = Sinks.one();
        List<String> starts = new CopyOnWriteArrayList<>();
        CountDownLatch concurrentStarts = new CountDownLatch(2);
        AgentTool delegate = new StubAgentTool(param -> {
            String itemId = valueFromMessage(param, "storyboardItemId");
            starts.add(itemId);
            concurrentStarts.countDown();
            Mono<Void> gate = "1".equals(itemId) ? releaseFirstShot.asMono() : Mono.empty();
            return gate.thenReturn(ToolResultBlock.text("done-" + itemId));
        });
        KeyedSequentialAgentToolAdapter adapter = new KeyedSequentialAgentToolAdapter(delegate);

        Mono<List<ToolResultBlock>> execution = Mono.zip(
                        adapter.callAsync(param("1", "10")),
                        adapter.callAsync(param("2", "10")),
                        adapter.callAsync(param("3", "20")))
                .map(tuple -> List.of(tuple.getT1(), tuple.getT2(), tuple.getT3()))
                .cache();
        execution.subscribe();

        assertThat(concurrentStarts.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(starts).contains("1", "3").doesNotContain("2");

        releaseFirstShot.tryEmitEmpty();
        assertThat(execution.block(Duration.ofSeconds(2))).hasSize(3);
        assertThat(starts).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void missingSceneIdUsesLegacySerialQueue() throws InterruptedException {
        Sinks.One<Void> releaseFirstShot = Sinks.one();
        List<String> starts = new CopyOnWriteArrayList<>();
        CountDownLatch firstStart = new CountDownLatch(1);
        AgentTool delegate = new StubAgentTool(param -> {
            String itemId = valueFromMessage(param, "storyboardItemId");
            starts.add(itemId);
            firstStart.countDown();
            Mono<Void> gate = "1".equals(itemId) ? releaseFirstShot.asMono() : Mono.empty();
            return gate.thenReturn(ToolResultBlock.text("done-" + itemId));
        });
        KeyedSequentialAgentToolAdapter adapter = new KeyedSequentialAgentToolAdapter(delegate);

        Mono<Void> execution = Mono.when(
                adapter.callAsync(legacyParam("1")),
                adapter.callAsync(legacyParam("2"))).cache();
        execution.subscribe();

        assertThat(firstStart.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(starts).containsExactly("1");
        releaseFirstShot.tryEmitEmpty();
        execution.block(Duration.ofSeconds(2));
        assertThat(starts).containsExactly("1", "2");
    }

    private static ToolCallParam param(String itemId, String sceneId) {
        return buildParam("storyboardItemId: " + itemId + "\nstoryboardSceneId: " + sceneId);
    }

    private static ToolCallParam legacyParam(String itemId) {
        return buildParam("storyboardItemId: " + itemId);
    }

    private static ToolCallParam buildParam(String message) {
        Map<String, Object> input = Map.of("message", message);
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-" + message.hashCode())
                        .name("generate")
                        .input(input)
                        .build())
                .input(input)
                .build();
    }

    private static String valueFromMessage(ToolCallParam param, String field) {
        String message = String.valueOf(param.getInput().get("message"));
        for (String line : message.split("\\R")) {
            if (line.startsWith(field + ":")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "";
    }

    private static final class StubAgentTool implements AgentTool {
        private final java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> callback;

        private StubAgentTool(java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> callback) {
            this.callback = callback;
        }

        @Override
        public String getName() {
            return "generate";
        }

        @Override
        public String getDescription() {
            return "test";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return callback.apply(param);
        }
    }
}
