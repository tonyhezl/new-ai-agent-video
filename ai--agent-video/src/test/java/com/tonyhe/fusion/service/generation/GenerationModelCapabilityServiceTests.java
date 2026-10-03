package com.tonyhe.fusion.service.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.service.ai.ModelPresetService;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationModelCapabilityServiceTests {

                private final ModelPresetService presetService = createPresetService();
                private final GenerationModelCapabilityService service =
                        new GenerationModelCapabilityService(createMetadataResolver(), presetService, new TestCapabilityRuleService());

        private static ModelPresetService createPresetService() {
                ModelPresetService presetService = new ModelPresetService();
                presetService.init();
                return presetService;
        }

        private static AiModelMetadataResolver createMetadataResolver() {
                return new AiModelMetadataResolver(null) {
                        @Override
                        public AiModelMetadata resolve(AiModel model, String platform) {
                                String normalized = normalizePlatform(platform);
                                String code = model != null && model.getCode() != null
                                        ? model.getCode().toLowerCase() : "";
                                String family = model != null && model.getModelFamily() != null
                                        ? model.getModelFamily().toLowerCase()
                                        : (code.contains("grok") && model != null && model.getModelType() != null && model.getModelType() == 3
                                        ? "grok_video"
                                        : code.contains("grok") ? "grok"
                                        : code.contains("agnes") ? "agnes"
                                        : code.contains("sora") ? "sora"
                                        : code.contains("video-ds") || code.contains("jimeng") ? "jimeng"
                                        : "generic");
                                String protocol = model != null && model.getModelProtocol() != null
                                        ? model.getModelProtocol().toLowerCase()
                                        : family;
                                return new AiModelMetadata(platform, normalized, family, protocol);
                        }

                        @Override
                        public String resolvePlatform(AiModel model) {
                                return null;
                        }
                };
        }

    @Test
    void shouldUsePresetReferenceImageCapabilityForSupportedOpenAiImageModels() {
        List<String> supportedCodes = List.of("gpt-image-1", "gpt-image-1.5", "gpt-image-1-mini", "gpt-image-2");

        supportedCodes.forEach(modelCode -> {
            AiModel model = AiModel.builder()
                    .name(modelCode)
                    .code(modelCode)
                    .build();

            GenerationModelCapabilityService.ImageModelCapability capability = service.resolveImageCapability(model, "openai");

            assertTrue(capability.supportsReferenceImages(), modelCode);
            assertEquals(0, capability.minReferenceImages(), modelCode);
            assertEquals(16, capability.maxReferenceImages(), modelCode);
        });
    }

    @Test
    void shouldAllowReferenceImagesForPresetSupportedOpenAiImageModel() {
        AiModel model = AiModel.builder()
                .name("GPT Image 1")
                .code("gpt-image-1")
                .build();
        ImageTask task = ImageTask.builder()
                .refImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build();

        service.validateImageTask(model, task, "openai");
    }

    @Test
    void shouldExposeOfficialOpenAiImagePresetSizesAndRatios() {
        AiModel dallE3 = AiModel.builder()
                .name("DALL·E 3")
                .code("dall-e-3")
                .build();
        var dallE3Config = service.getMergedModelConfig(dallE3);

        assertEquals(List.of("1:1", "7:4", "4:7"),
                JSONUtil.toList(dallE3Config.getJSONArray("supportedAspectRatios"), String.class));
        assertEquals("1792x1024",
                dallE3Config.getJSONObject("supportedSizes").getJSONObject("standard").getStr("7:4"));

        AiModel gptImage2 = AiModel.builder()
                .name("GPT Image 2")
                .code("gpt-image-2")
                .build();
        var gptImage2Config = service.getMergedModelConfig(gptImage2);

        assertEquals(List.of("auto", "1:1", "3:2", "2:3", "4:3", "3:4", "5:4", "4:5",
                        "16:9", "9:16", "2:1", "1:2", "21:9", "9:21"),
                JSONUtil.toList(gptImage2Config.getJSONArray("supportedAspectRatios"), String.class));
        assertFalse(gptImage2Config.getBool("asyncMode"));
        assertTrue(gptImage2Config.getBool("supportCustomSize"));
        assertEquals(16, gptImage2Config.getInt("sizeMultiple"));
        assertEquals(3840, gptImage2Config.getInt("maxEdge"));
        assertEquals(3, gptImage2Config.getInt("maxAspectRatio"));
        assertEquals(655360, gptImage2Config.getInt("minPixels"));
        assertEquals(8294400, gptImage2Config.getInt("maxPixels"));
        assertEquals("2048x1152",
                gptImage2Config.getJSONObject("supportedSizes").getJSONObject("2K").getStr("16:9"));
        assertEquals("2048x1360",
                gptImage2Config.getJSONObject("supportedSizes").getJSONObject("2K").getStr("3:2"));
        assertEquals("2160x3840",
                gptImage2Config.getJSONObject("supportedSizes").getJSONObject("4K").getStr("9:16"));
        assertEquals("3840x1648",
                gptImage2Config.getJSONObject("supportedSizes").getJSONObject("4K").getStr("21:9"));
    }

    @Test
    void shouldRejectReferenceImagesForDallE3Preset() {
        AiModel model = AiModel.builder()
                .name("DALL·E 3")
                .code("dall-e-3")
                .build();
        ImageTask task = ImageTask.builder()
                .refImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateImageTask(model, task, "openai"));

        assertTrue(ex.getMessage().contains("不支持参考图输入"));
    }

    @Test
    void shouldUsePresetCapabilityWhenModelConfigDoesNotContainNewFields() {
        GenerationModelCapabilityService serviceWithPreset = new GenerationModelCapabilityService(createMetadataResolver(), new ModelPresetService() {
            @Override
            public String getPresetConfig(String code) {
                if (!"doubao-seedream-3-0-t2i-250415".equals(code)) {
                    return null;
                }
                return """
                        {
                          "supportReferenceImages": false,
                          "minReferenceImages": 0,
                          "maxReferenceImages": 0
                        }
                        """;
            }
        }, new TestCapabilityRuleService());

        AiModel model = AiModel.builder()
                .name("Seedream 3.0")
                .code("doubao-seedream-3-0-t2i-250415")
                .config("{\"defaultWidth\":2048,\"defaultHeight\":2048}")
                .build();

        GenerationModelCapabilityService.ImageModelCapability capability = serviceWithPreset.resolveImageCapability(model, "volcengine");

        assertFalse(capability.supportsReferenceImages());
        assertEquals(0, capability.maxReferenceImages());
    }

    @Test
    void shouldRejectFirstFrameForT2vGoogleFlowModel() {
        AiModel model = AiModel.builder()
                .name("Veo T2V Fast")
                .code("veo_3_1_t2v_fast")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, task, "GoogleFlowReverseApi"));

        assertTrue(ex.getMessage().contains("不支持首帧图输入"));
    }

    @Test
    void shouldRequireTwoImagesForInterpolationModel() {
        AiModel model = AiModel.builder()
                .name("Interpolation Lite")
                .code("veo_3_1_interpolation_lite")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, task, "GoogleFlowReverseApi"));

        assertTrue(ex.getMessage().contains("至少需要 2 张图片输入"));
    }

    @Test
    void shouldRejectLastFrameForSeedanceProFast() {
        AiModel model = AiModel.builder()
                .name("Seedance 1.0 Pro Fast")
                .code("doubao-seedance-1-0-pro-fast-251015")
                .build();
        VideoTask task = VideoTask.builder()
                .lastFrameImageUrl("https://example.com/last.png")
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, task, "volcengine"));

        assertTrue(ex.getMessage().contains("不支持尾帧图输入"));
    }

    @Test
    void shouldAllowSeedance20ReferenceMedia() {
        AiModel model = AiModel.builder()
                .name("Seedance 2.0")
                .code("doubao-seedance-2-0-260128")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .lastFrameImageUrl("https://example.com/last.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref-1.png")))
                .referenceVideoUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref-1.mp4")))
                .referenceAudioUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref-1.mp3")))
                .build();

        service.validateVideoTask(model, task, "volcengine");
    }

    @Test
    void shouldAllowDashScopeWanImageReferenceImages() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 Image")
                .code("wan2.7-image")
                .build();
        ImageTask task = ImageTask.builder()
                .refImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build();

        GenerationModelCapabilityService.ImageModelCapability capability = service.resolveImageCapability(model, "dashscope");

        assertTrue(capability.supportsReferenceImages());
        service.validateImageTask(model, task, "dashscope");
    }

    @Test
    void shouldAllowDashScopeQwenImage20ReferenceImages() {
        AiModel model = AiModel.builder()
                .name("Qwen Image 2.0")
                .code("qwen-image-2.0")
                .build();
        ImageTask task = ImageTask.builder()
                .refImageUrls(JSONUtil.toJsonStr(List.of(
                        "https://example.com/ref-1.png",
                        "https://example.com/ref-2.png")))
                .build();

        GenerationModelCapabilityService.ImageModelCapability capability = service.resolveImageCapability(model, "dashscope");

        assertTrue(capability.supportsReferenceImages());
        assertEquals(2, capability.maxReferenceImages());
        service.validateImageTask(model, task, "dashscope");
    }

    @Test
    void shouldRejectFirstFrameForDashScopeT2vModel() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 T2V")
                .code("wan2.7-t2v")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, task, "dashscope"));

        assertTrue(ex.getMessage().contains("不支持首帧图输入"));
    }

    @Test
    void shouldAllowReferenceAudioForDashScopeT2vModel() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 T2V")
                .code("wan2.7-t2v")
                .build();
        VideoTask task = VideoTask.builder()
                .referenceAudioUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp3")))
                .build();

        GenerationModelCapabilityService.VideoModelCapability capability = service.resolveVideoCapability(model, "dashscope");

        assertTrue(capability.supportsReferenceAudios());
        service.validateVideoTask(model, task, "dashscope");
    }

    @Test
    void shouldAllowFirstAndLastFrameForDashScopeI2vModel() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 I2V")
                .code("wan2.7-i2v")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .lastFrameImageUrl("https://example.com/last.png")
                .build();

        GenerationModelCapabilityService.VideoModelCapability capability = service.resolveVideoCapability(model, "dashscope");

        assertTrue(capability.supportsFirstFrame());
        assertTrue(capability.supportsLastFrame());
        service.validateVideoTask(model, task, "dashscope");
    }

    @Test
    void shouldAllowReferenceMediaForDashScopeR2vModel() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .referenceVideoUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp4")))
                .referenceAudioUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp3")))
                .build();

        service.validateVideoTask(model, task, "dashscope");
    }

    @Test
    void shouldAllowDashScopeVideoEditReferenceImageAndVideo() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 Video Edit")
                .code("wan2.7-videoedit")
                .build();
        VideoTask task = VideoTask.builder()
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .referenceVideoUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp4")))
                .build();

        GenerationModelCapabilityService.VideoModelCapability capability = service.resolveVideoCapability(model, "dashscope");

        assertTrue(capability.supportsReferenceImages());
        assertTrue(capability.supportsReferenceVideos());
        assertFalse(capability.supportsReferenceAudios());
        service.validateVideoTask(model, task, "dashscope");
    }

    @Test
    void shouldAllowFirstFrameAndSingleReferenceForGrokImagineVideo() {
        AiModel model = AiModel.builder()
                .name("grok-imagine-video")
                .code("grok-imagine-video")
                .modelProtocol("grok_video")
                .build();

        GenerationModelCapabilityService.VideoModelCapability capability =
                service.resolveVideoCapability(model, "openai_compatible");

        assertTrue(capability.supportsFirstFrame());
        assertFalse(capability.supportsLastFrame());
        assertTrue(capability.supportsReferenceImages());
        assertEquals(1, capability.maxImageInputs());
        assertEquals(1, capability.maxReferenceImages());
        assertFalse(capability.supportsReferenceVideos());
        assertFalse(capability.supportsReferenceAudios());

        // Grok 仅支持 1 张图片输入：有首帧时只传首帧
        service.validateVideoTask(model, VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .build(), "openai_compatible");
        // 无首帧时允许 1 张参考图
        service.validateVideoTask(model, VideoTask.builder()
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build(), "openai_compatible");
        // 首帧 + 参考图会超过 maxImageInputs=1
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, VideoTask.builder()
                        .firstFrameImageUrl("https://example.com/first.png")
                        .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                        .build(), "openai_compatible"));
        assertTrue(ex.getMessage().contains("最多支持 1 张图片输入"));
    }

    @Test
    void shouldAllowReferenceImagesForGrokImagineImage() {
        AiModel model = AiModel.builder()
                .name("grok-imagine-image")
                .code("grok-imagine-image")
                .build();
        ImageTask task = ImageTask.builder()
                .refImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build();

        GenerationModelCapabilityService.ImageModelCapability capability =
                service.resolveImageCapability(model, "openai_compatible");

        assertTrue(capability.supportsReferenceImages());
        assertEquals(16, capability.maxReferenceImages());
        service.validateImageTask(model, task, "openai_compatible");
    }

    @Test
    void shouldAllowFirstFrameAndSingleReferenceForJimengVideoDs() {
        AiModel model = AiModel.builder()
                .name("即梦 Video DS 2.0")
                .code("video-ds-2.0")
                .build();

        GenerationModelCapabilityService.VideoModelCapability capability =
                service.resolveVideoCapability(model, "openai_compatible");

        assertTrue(capability.supportsFirstFrame());
        assertFalse(capability.supportsLastFrame());
        assertTrue(capability.supportsReferenceImages());
        assertEquals(1, capability.maxImageInputs());
        assertFalse(capability.supportsReferenceVideos());
        assertFalse(capability.supportsReferenceAudios());

        // 有首帧时只传首帧
        service.validateVideoTask(model, VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .build(), "openai_compatible");
        // 无首帧时允许 1 张参考图
        service.validateVideoTask(model, VideoTask.builder()
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .build(), "openai_compatible");
        // 首帧 + 参考图会超过 maxImageInputs=1
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoTask(model, VideoTask.builder()
                        .firstFrameImageUrl("https://example.com/first.png")
                        .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                        .build(), "openai_compatible"));
        assertTrue(ex.getMessage().contains("最多支持 1 张图片输入"));
    }

    // ==================== 首帧/尾帧降级测试 ====================

    @Test
    void shouldDowngradeFirstFrameToReferenceImageWithPromptHint() {
        // wan2.7-r2v: supportsFirstFrame=false, supportsReferenceImages=true
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色走进房间")
                .firstFrameImageUrl("https://example.com/first.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/char.png")))
                .build();

        service.trimVideoTaskInputs(model, task);

        // firstFrameImageUrl should be cleared
        assertTrue(task.getFirstFrameImageUrl() == null || task.getFirstFrameImageUrl().isBlank());
        // firstFrame URL should be appended to referenceImageUrls
        List<String> refImages = JSONUtil.toList(task.getReferenceImageUrls(), String.class);
        assertEquals(2, refImages.size());
        assertEquals("https://example.com/char.png", refImages.get(0));
        assertEquals("https://example.com/first.png", refImages.get(1));
        // prompt should contain hint referencing image 2 (1-indexed)
        assertTrue(task.getPrompt().contains("以参考图片2的画面作为视频首帧起始画面"));
    }

    @Test
    void shouldDowngradeLastFrameToReferenceImageWithPromptHint() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色走出房间")
                .lastFrameImageUrl("https://example.com/last.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/char.png")))
                .build();

        service.trimVideoTaskInputs(model, task);

        assertTrue(task.getLastFrameImageUrl() == null || task.getLastFrameImageUrl().isBlank());
        List<String> refImages = JSONUtil.toList(task.getReferenceImageUrls(), String.class);
        assertEquals(2, refImages.size());
        assertEquals("https://example.com/last.png", refImages.get(1));
        assertTrue(task.getPrompt().contains("以参考图片2的画面作为视频末帧结束画面"));
    }

    @Test
    void shouldDowngradeBothFirstAndLastFrameWithCorrectIndices() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色从走到跑")
                .firstFrameImageUrl("https://example.com/first.png")
                .lastFrameImageUrl("https://example.com/last.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/char.png")))
                .build();

        service.trimVideoTaskInputs(model, task);

        assertTrue(task.getFirstFrameImageUrl() == null || task.getFirstFrameImageUrl().isBlank());
        assertTrue(task.getLastFrameImageUrl() == null || task.getLastFrameImageUrl().isBlank());
        List<String> refImages = JSONUtil.toList(task.getReferenceImageUrls(), String.class);
        assertEquals(3, refImages.size());
        assertEquals("https://example.com/char.png", refImages.get(0));
        assertEquals("https://example.com/first.png", refImages.get(1));
        assertEquals("https://example.com/last.png", refImages.get(2));
        // first frame is image 2, last frame is image 3
        assertTrue(task.getPrompt().contains("以参考图片2的画面作为视频首帧起始画面"));
        assertTrue(task.getPrompt().contains("以参考图片3的画面作为视频末帧结束画面"));
    }

    @Test
    void shouldNotDowngradeWhenModelSupportsFirstFrame() {
        // wan2.7-i2v: supportsFirstFrame=true
        AiModel model = AiModel.builder()
                .name("Wan 2.7 I2V")
                .code("wan2.7-i2v")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色走进房间")
                .firstFrameImageUrl("https://example.com/first.png")
                .build();

        service.trimVideoTaskInputs(model, task);

        // firstFrame should be preserved
        assertEquals("https://example.com/first.png", task.getFirstFrameImageUrl());
        // prompt should not contain downgrade hint
        assertFalse(task.getPrompt().contains("以参考图片"));
    }

    @Test
    void shouldNotDowngradeWhenModelDoesNotSupportReferenceImages() {
        // GoogleFlow 兜底: supportsFirstFrame=false, supportsReferenceImages=false
        // Use a model code that hits the fallback rule
        AiModel model = AiModel.builder()
                .name("GoogleFlow T2V")
                .code("googleflow-t2v-unknown")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色走进房间")
                .firstFrameImageUrl("https://example.com/first.png")
                .build();

        service.trimVideoTaskInputs(model, task);

        // firstFrame should be cleared (not downgraded, since no reference image support)
        assertTrue(task.getFirstFrameImageUrl() == null || task.getFirstFrameImageUrl().isBlank());
        // no reference images should be set
        assertTrue(task.getReferenceImageUrls() == null || task.getReferenceImageUrls().isBlank());
        // prompt should not contain downgrade hint
        assertFalse(task.getPrompt().contains("以参考图片"));
    }

    @Test
    void shouldNotDuplicateUrlWhenFirstFrameAlreadyInReferenceImages() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .prompt("角色走进房间")
                .firstFrameImageUrl("https://example.com/char.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/char.png")))
                .build();

        service.trimVideoTaskInputs(model, task);

        assertTrue(task.getFirstFrameImageUrl() == null || task.getFirstFrameImageUrl().isBlank());
        List<String> refImages = JSONUtil.toList(task.getReferenceImageUrls(), String.class);
        // URL already in list, should not be duplicated
        assertEquals(1, refImages.size());
        assertEquals("https://example.com/char.png", refImages.get(0));
        // prompt should reference image 1
        assertTrue(task.getPrompt().contains("以参考图片1的画面作为视频首帧起始画面"));
    }

    @Test
    void shouldHandleDowngradeWithEmptyPrompt() {
        AiModel model = AiModel.builder()
                .name("Wan 2.7 R2V")
                .code("wan2.7-r2v")
                .build();
        VideoTask task = VideoTask.builder()
                .firstFrameImageUrl("https://example.com/first.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/char.png")))
                .build();

        service.trimVideoTaskInputs(model, task);

        assertTrue(task.getFirstFrameImageUrl() == null || task.getFirstFrameImageUrl().isBlank());
        // prompt should be set even if originally null/blank
        assertTrue(StrUtil.isNotBlank(task.getPrompt()));
        assertTrue(task.getPrompt().contains("以参考图片2的画面作为视频首帧起始画面"));
    }

    @Test
    void resolveImageSizeFromResolution_shouldRespectPortraitAndLandscapeAspectRatios() {
        AiModel model = AiModel.builder()
                .name("SDXL")
                .code("sdxl")
                .config("""
                        {
                          "supportedSizes": {
                            "1K": {
                              "16:9": "1792x1024",
                              "9:16": "1024x1792"
                            },
                            "2K": "2848x1600"
                          }
                        }
                        """)
                .build();

        int[] sizeLandscape1K = service.resolveImageSizeFromResolution(model, "1K", "16:9");
        assertEquals(1792, sizeLandscape1K[0]);
        assertEquals(1024, sizeLandscape1K[1]);

        int[] sizePortrait1K = service.resolveImageSizeFromResolution(model, "1K", "9:16");
        assertEquals(1024, sizePortrait1K[0]);
        assertEquals(1792, sizePortrait1K[1]);

        int[] sizePortrait2K = service.resolveImageSizeFromResolution(model, "2K", "9:16");
        assertEquals(1600, sizePortrait2K[0]);
        assertEquals(2848, sizePortrait2K[1]);

        int[] sizeLandscape2K = service.resolveImageSizeFromResolution(model, "2K", "16:9");
        assertEquals(2848, sizeLandscape2K[0]);
        assertEquals(1600, sizeLandscape2K[1]);
    }
}