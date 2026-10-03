package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewApiVideoProtocolRouterTests {

    @Test
    void shouldResolveDedicatedProtocolAdapterWhenPresent() {
        NewApiVideoProtocolAdapter genericAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(genericAdapter.getProtocol()).thenReturn("generic");
        NewApiVideoProtocolAdapter jimengAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(jimengAdapter.getProtocol()).thenReturn("jimeng");

        NewApiVideoProtocolRouter router = new NewApiVideoProtocolRouter(List.of(genericAdapter, jimengAdapter));
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("jimeng-v1").build(),
                null,
                null,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "jimeng", "jimeng")
        );

        assertSame(jimengAdapter, router.resolve(context));
    }

    @Test
    void shouldFallbackToGenericAdapterWhenDedicatedProtocolMissing() {
        NewApiVideoProtocolAdapter genericAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(genericAdapter.getProtocol()).thenReturn("generic");

        NewApiVideoProtocolRouter router = new NewApiVideoProtocolRouter(List.of(genericAdapter));
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("kling-v1").build(),
                null,
                null,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "kling", "kling")
        );

        assertSame(genericAdapter, router.resolve(context));
    }
}