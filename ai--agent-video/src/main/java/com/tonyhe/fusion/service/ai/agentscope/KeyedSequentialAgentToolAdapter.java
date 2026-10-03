package com.tonyhe.fusion.service.ai.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serializes calls for one storyboard scene while allowing different scenes to run concurrently.
 * A missing scene key falls back to one legacy queue so older prompts remain safe.
 */
@Slf4j
public class KeyedSequentialAgentToolAdapter implements AgentTool {

    private static final String LEGACY_QUEUE_KEY = "__legacy__";
    private static final Pattern SCENE_ID_PATTERN = Pattern.compile(
            "(?im)(?:storyboardSceneId|storyboard_scene_id)\\s*[:=]\\s*[\\\"']?(\\d+)");

    private final AgentTool delegate;
    private final Map<String, SerialQueue> queues = new ConcurrentHashMap<>();

    public KeyedSequentialAgentToolAdapter(AgentTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            String queueKey = resolveQueueKey(param);
            QueuedCall call = new QueuedCall(param);
            SerialQueue queue = queues.computeIfAbsent(queueKey, this::createQueue);
            Sinks.EmitResult emitResult = queue.calls.tryEmitNext(call);
            if (emitResult.isFailure()) {
                return Mono.error(new IllegalStateException(
                        "无法加入场次串行队列: toolName=" + getName() + ", result=" + emitResult));
            }
            return call.result.asMono()
                    .doOnCancel(() -> {
                        call.cancelled.set(true);
                        call.cancelSignal.tryEmitEmpty();
                    });
        });
    }

    private SerialQueue createQueue(String queueKey) {
        Sinks.Many<QueuedCall> calls = Sinks.many().unicast().onBackpressureBuffer();
        SerialQueue queue = new SerialQueue(calls);
        calls.asFlux()
                .concatMap(call -> execute(queueKey, call))
                .subscribe(
                        ignored -> { },
                        error -> log.error("[KeyedSequential] 场次队列异常终止: toolName={}, sceneKey={}",
                                getName(), queueKey, error));
        return queue;
    }

    private Mono<Void> execute(String queueKey, QueuedCall call) {
        if (call.cancelled.get()) {
            return Mono.empty();
        }
        log.info("[KeyedSequential] 开始执行: toolName={}, sceneKey={}", getName(), queueKey);
        return Mono.defer(() -> delegate.callAsync(call.param))
                .takeUntilOther(call.cancelSignal.asMono())
                .doOnNext(call.result::tryEmitValue)
                .switchIfEmpty(Mono.defer(() -> {
                    if (!call.cancelled.get()) {
                        call.result.tryEmitError(new IllegalStateException("子 Agent 工具未返回结果: " + getName()));
                    }
                    return Mono.empty();
                }))
                .doOnError(error -> call.result.tryEmitError(error))
                .onErrorResume(error -> {
                    log.error("[KeyedSequential] 调用失败: toolName={}, sceneKey={}",
                            getName(), queueKey, error);
                    return Mono.empty();
                })
                .doFinally(signal -> log.info(
                        "[KeyedSequential] 执行结束: toolName={}, sceneKey={}, signal={}",
                        getName(), queueKey, signal))
                .then();
    }

    private String resolveQueueKey(ToolCallParam param) {
        if (param != null && param.getInput() != null) {
            Object directValue = param.getInput().get("storyboardSceneId");
            if (directValue != null && !String.valueOf(directValue).isBlank()) {
                return String.valueOf(directValue).trim();
            }
            Object message = param.getInput().get("message");
            if (message != null) {
                Matcher matcher = SCENE_ID_PATTERN.matcher(String.valueOf(message));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        log.warn("[KeyedSequential] 调用缺少 storyboardSceneId，使用兼容串行队列: toolName={}", getName());
        return LEGACY_QUEUE_KEY;
    }

    private static final class SerialQueue {
        private final Sinks.Many<QueuedCall> calls;

        private SerialQueue(Sinks.Many<QueuedCall> calls) {
            this.calls = calls;
        }
    }

    private static final class QueuedCall {
        private final ToolCallParam param;
        private final Sinks.One<ToolResultBlock> result = Sinks.one();
        private final Sinks.One<Void> cancelSignal = Sinks.one();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private QueuedCall(ToolCallParam param) {
            this.param = param;
        }
    }
}
