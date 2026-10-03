package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ModelPresetService;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.TestCapabilityRuleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetGenerationModelCapabilitiesToolExecutorTests {

    @Test
    void shouldReturnImageAndVideoCapabilitiesForCurrentDefaults() {
        AiModelService aiModelService = mock(AiModelService.class);
        AiModelMetadataResolver metadataResolver = mock(AiModelMetadataResolver.class);
        when(metadataResolver.resolvePlatform(org.mockito.ArgumentMatchers.any())).thenReturn("openai_compatible");
        AiModelMetadata testMetadata = new AiModelMetadata("openai_compatible", "openai_compatible", "generic", "generic");
        when(metadataResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(testMetadata);
        when(metadataResolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(testMetadata);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(metadataResolver, new ModelPresetService() {
            @Override
            public String getPresetConfig(String code) {
                return switch (code) {
                    case "gpt-image-1" -> """
                            {
                              "supportReferenceImages": false,
                              "minReferenceImages": 0,
                              "maxReferenceImages": 0,
                              "defaultWidth": 1024,
                              "defaultHeight": 1024
                            }
                            """;
                    case "veo_3_1_r2v_fast" -> """
                            {
                              "supportFirstFrame": false,
                              "supportLastFrame": false,
                              "supportReferenceImages": true,
                              "supportReferenceVideos": false,
                              "supportReferenceAudios": false,
                              "minImageInputs": 0,
                              "maxImageInputs": 3,
                              "maxReferenceImages": 3,
                              "supportedAspectRatios": ["16:9", "9:16"]
                            }
                            """;
                    default -> null;
                };
            }
        }, new TestCapabilityRuleService());

        AiModel imageModel = AiModel.builder().id(11L).name("GPT Image 1").code("gpt-image-1").build();
        AiModel videoModel = AiModel.builder().id(22L).name("Veo R2V").code("veo_3_1_r2v_fast").build();

        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        when(aiModelService.getDefaultByType(3)).thenReturn(videoModel);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService);

        String result = executor.execute("{}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("success", json.getStr("status"));
        assertFalse(json.getJSONObject("image").getBool("supportsReferenceImages"));
        assertTrue(json.getJSONObject("video").getBool("supportsReferenceImages"));
        assertEquals("default_model", json.getJSONObject("image").getStr("selectionSource"));
        assertTrue(json.getJSONObject("video").getStr("toolGuidance").contains("referenceImageUrls"));
    }

    @Test
    void shouldSupportSingleModelTypeQuery() {
        AiModelService aiModelService = mock(AiModelService.class);
        AiModelMetadataResolver metadataResolver = mock(AiModelMetadataResolver.class);
        when(metadataResolver.resolvePlatform(org.mockito.ArgumentMatchers.any())).thenReturn("openai_compatible");
        AiModelMetadata testMetadata = new AiModelMetadata("openai_compatible", "openai_compatible", "generic", "generic");
        when(metadataResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(testMetadata);
        when(metadataResolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(testMetadata);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(metadataResolver, new ModelPresetService(), new TestCapabilityRuleService());

        AiModel imageModel = AiModel.builder().id(11L).name("GPT Image 1").code("gpt-image-1")
                .config("{\"supportReferenceImages\":false,\"maxReferenceImages\":0}")
                .build();
        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService);

        String result = executor.execute("{\"modelType\":\"image\"}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("image", json.getStr("requestedModelType"));
        assertTrue(json.containsKey("image"));
        assertFalse(json.containsKey("video"));
    }
}