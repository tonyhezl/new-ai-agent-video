package com.tonyhe.fusion.service.generation.strategy;

import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.service.ai.ApiConfigService;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoGenerationStrategyRouterTests {

    @Test
    void shouldRouteByApiPlatformInsteadOfModelCode() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);

        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");

        VideoGenerationStrategy flowStrategy = mock(VideoGenerationStrategy.class);
        when(flowStrategy.getName()).thenReturn("GoogleFlowReverseApi");

        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(
                List.of(flowStrategy, newApiStrategy),
                resolver,
                apiConfigService
        );

        AiModel model = AiModel.builder()
                .id(1L)
                .code("kling-v1")
                .apiConfigId(11L)
                .modelType(3)
                .build();
        when(apiConfigService.getById(11L)).thenReturn(ApiConfig.builder().id(11L).platform("newapi").build());

        assertSame(newApiStrategy, router.resolve(model));
    }
}
