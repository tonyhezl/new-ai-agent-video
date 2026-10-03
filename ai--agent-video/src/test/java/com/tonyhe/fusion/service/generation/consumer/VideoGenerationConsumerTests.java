package com.tonyhe.fusion.service.generation.consumer;

import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.infrastructure.queue.RedisTaskQueue;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.generation.TaskOwnershipSnapshotService;
import com.tonyhe.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoGenerationConsumerTests {

    @Test
        void submitTaskDefaultsWatermarkOffAndAudioOnWhenUnset() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        AiModel model = AiModel.builder()
                .id(101L)
                .status(1)
                .build();
        when(aiModelService.getById(101L)).thenReturn(model);
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(201L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue,
                videoGenerationService,
                aiModelService,
                capabilityService,
                strategyRouter,
                mock(MediaStorageService.class),
                mock(TaskOwnershipSnapshotService.class)
        );

        VideoTask task = VideoTask.builder()
                .modelId(101L)
                .prompt("test prompt")
                .build();

        consumer.submitTask(task);

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask createdTask = taskCaptor.getValue();
        assertThat(createdTask.getWatermark()).isFalse();
        assertThat(createdTask.getGenerateAudio()).isTrue();
    }

    @Test
        void submitTaskPreservesExplicitFlags() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        AiModel model = AiModel.builder()
                .id(102L)
                .status(1)
                .build();
        when(aiModelService.getById(102L)).thenReturn(model);
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(202L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue,
                videoGenerationService,
                aiModelService,
                capabilityService,
                strategyRouter,
                mock(MediaStorageService.class),
                mock(TaskOwnershipSnapshotService.class)
        );

        VideoTask task = VideoTask.builder()
                .modelId(102L)
                .prompt("test prompt")
                .watermark(true)
                .generateAudio(false)
                .build();

        consumer.submitTask(task);

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask createdTask = taskCaptor.getValue();
                assertThat(createdTask.getWatermark()).isTrue();
                assertThat(createdTask.getGenerateAudio()).isFalse();
    }
}
