package com.tonyhe.fusion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

/**
 * 异步任务配置 - 启用 @Async 支持，并定义视频合成专用执行器。
 * <p>
 * 使用 Spring Boot 3.2+ 的 {@link SimpleAsyncTaskExecutor} 配合 JDK 21 虚拟线程：
 * <ul>
 * <li>{@code setVirtualThreads(true)} - 每个任务在独立虚拟线程中运行，轻量无开销</li>
 * <li>{@code setConcurrencyLimit(n)} - 内置并发节流，限制 ffmpeg 同时合成数量避免 CPU 过载</li>
 * </ul>
 * 相比传统 {@code ThreadPoolTaskExecutor}，虚拟线程停机时自动中断，不会阻塞 JVM 退出。
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * ffmpeg 合成任务最大并发数。
     * <p>
     * 默认值 {@code 0} 表示根据 CPU 核心数自动计算：
     * 由于 ffmpeg libx264 编码（-preset veryfast）单进程约占 2~4 核，
     * 默认取 {@code max(1, availableProcessors / 4)}，在保证合成效率的同时避免 CPU 过载。
     * <p>
     * 示例：4 核 → 1 并发，8 核 → 2 并发，16 核 → 4 并发，32 核 → 8 并发。
     */
    @Value("${app.video-compose.max-concurrent:0}")
    private int maxConcurrent;

    @Bean(name = "videoComposeExecutor")
    public Executor videoComposeExecutor() {
        int limit = resolveMaxConcurrent();

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("video-compose-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(limit);

        log.info("[AsyncConfig] 视频合成执行器: virtualThreads=true, concurrencyLimit={} (CPU 核心数={})",
                limit, Runtime.getRuntime().availableProcessors());
        return executor;
    }

    private int resolveMaxConcurrent() {
        if (maxConcurrent > 0) {
            return maxConcurrent;
        }
        // 自动计算：每个 ffmpeg 进程约占 4 核（libx264 -preset veryfast），至少保留 1 个并发
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }
}
