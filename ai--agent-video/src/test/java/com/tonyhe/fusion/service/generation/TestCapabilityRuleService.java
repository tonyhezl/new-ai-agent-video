package com.tonyhe.fusion.service.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.generation.CapabilityRule;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test-only subclass of CapabilityRuleService that uses in-memory rules
 * instead of a database-backed mapper.
 */
public class TestCapabilityRuleService extends CapabilityRuleService {

    private final List<CapabilityRule> rules = new ArrayList<>();

    public TestCapabilityRuleService() {
        super(null);
        seedDefaults();
    }

    public void addRule(CapabilityRule rule) {
        rules.add(rule);
    }

    public void clearRules() {
        rules.clear();
    }

    @Override
    public java.util.List<CapabilityRule> listByType(Integer modelType) {
        return rules.stream()
                .filter(r -> r.getModelType().equals(modelType))
                .sorted((a, b) -> {
                    int cmp = Integer.compare(a.getSortOrder(), b.getSortOrder());
                    return cmp != 0 ? cmp : Long.compare(a.getId(), b.getId());
                })
                .toList();
    }

    @Override
    public List<CapabilityRule> listAll() {
        return new ArrayList<>(rules);
    }

    private void seedDefaults() {
        long id = 1;
        // Video rules
        id = addVideoRule(id, "GoogleFlow R2V", "googleflowreverseapi", "r2v", null, null, null, 10,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":3,\"maxReferenceImages\":3,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "GoogleFlow 插值", "googleflowreverseapi", "interpolation", null, null, null, 11,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":2,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "GoogleFlow I2V", "googleflowreverseapi", "i2v", null, null, null, 12,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":1,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "GoogleFlow 视频兜底", "googleflowreverseapi", null, null, null, null, 19,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":0,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "Volcengine Seedance 2.0", "volcengine", "seedance-2-0", null, null, null, 20,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":true,\"supportReferenceVideos\":true,\"supportReferenceAudios\":true,\"minImageInputs\":0,\"maxImageInputs\":null,\"maxReferenceImages\":9,\"maxReferenceVideos\":3,\"maxReferenceAudios\":3}");
        id = addVideoRule(id, "Volcengine 1.0 lite t2v", "volcengine", "1-0-lite-t2v", null, null, null, 21,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":0,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "Volcengine 1.0 lite i2v", "volcengine", "1-0-lite-i2v", null, null, null, 22,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":null,\"maxReferenceImages\":9,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "Volcengine 1.0 pro fast", "volcengine", "1-0-pro-fast", null, null, null, 23,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":1,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "Volcengine 1.5 pro", "volcengine", "1-5-pro", null, null, null, 24,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "Volcengine 1.0 pro", "volcengine", "1-0-pro", null, null, null, 25,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "DashScope wan2.7-r2v", "dashscope", "wan2.7-r2v", null, null, null, 30,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":true,\"supportReferenceAudios\":true,\"minImageInputs\":0,\"maxImageInputs\":10,\"maxReferenceImages\":10,\"maxReferenceVideos\":3,\"maxReferenceAudios\":3}");
        id = addVideoRule(id, "DashScope wan2.7-videoedit", "dashscope", "wan2.7-videoedit", null, null, null, 31,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":true,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":3,\"maxReferenceImages\":3,\"maxReferenceVideos\":1,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "DashScope wan2.7-i2v", "dashscope", "wan2.7-i2v", null, null, null, 32,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":true,\"supportReferenceAudios\":true,\"minImageInputs\":1,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":1,\"maxReferenceAudios\":1}");
        id = addVideoRule(id, "DashScope kf2v", "dashscope", "kf2v", null, null, null, 33,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":2,\"maxImageInputs\":2,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "DashScope i2v 通用", "dashscope", "i2v", null, null, null, 34,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":true,\"minImageInputs\":1,\"maxImageInputs\":1,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":1}");
        id = addVideoRule(id, "DashScope t2v 通用", "dashscope", "t2v", null, null, null, 35,
                "{\"supportFirstFrame\":false,\"supportLastFrame\":false,\"supportReferenceImages\":false,\"supportReferenceVideos\":false,\"supportReferenceAudios\":true,\"minImageInputs\":0,\"maxImageInputs\":0,\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":1}");
        id = addVideoRule(id, "NewAPI 视频默认", "newapi", null, null, null, null, 40,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":1,\"maxReferenceImages\":1,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "OpenAI-compat Agnes 视频", "openai_compatible", "agnes", null, "agnes", "agnes", 50,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":true,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":null,\"maxReferenceImages\":null,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "OpenAI-compat Grok 视频", "openai_compatible", null, "grok-imagine-video", "grok_video", "grok_video", 51,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":1,\"maxReferenceImages\":1,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "OpenAI-compat Sora 视频", "openai_compatible", null, "sora", "sora", "sora", 52,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":1,\"maxReferenceImages\":1,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");
        id = addVideoRule(id, "OpenAI-compat 即梦 video-ds", "openai_compatible", "video-ds", null, "jimeng", "jimeng", 53,
                "{\"supportFirstFrame\":true,\"supportLastFrame\":false,\"supportReferenceImages\":true,\"supportReferenceVideos\":false,\"supportReferenceAudios\":false,\"minImageInputs\":0,\"maxImageInputs\":1,\"maxReferenceImages\":1,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0}");

        // Image rules
        id = addImageRule(id, "GoogleFlow 图片", "googleflowreverseapi", null, null, null, null, 10,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":null}");
        id = addImageRule(id, "DashScope wan2.7/2.6 图片", "dashscope", null, "wan2.7-image", null, null, 20,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":10}");
        id = addImageRule(id, "DashScope wan2.6 图片", "dashscope", null, "wan2.6-image", null, null, 21,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":10}");
        id = addImageRule(id, "DashScope qwen-image-2.0", "dashscope", null, "qwen-image-2.0", null, null, 22,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":2}");
        id = addImageRule(id, "DashScope 图片兜底", "dashscope", null, null, null, null, 29,
                "{\"supportReferenceImages\":false,\"minReferenceImages\":0,\"maxReferenceImages\":0}");
        id = addImageRule(id, "OpenAI-compat flux 图片", "openai_compatible", "flux", null, null, null, 30,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":16}");
        id = addImageRule(id, "OpenAI-compat GPT Image", "openai_compatible", null, "gpt-image", null, null, 31,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":16}");
        id = addImageRule(id, "OpenAI-compat Grok 图片", "openai_compatible", null, "grok-imagine-image", null, null, 32,
                "{\"supportReferenceImages\":true,\"minReferenceImages\":0,\"maxReferenceImages\":16}");
        id = addImageRule(id, "OpenAI-compat 图片兜底", "openai_compatible", null, null, null, null, 39,
                "{\"supportReferenceImages\":false,\"minReferenceImages\":0,\"maxReferenceImages\":0}");
        id = addImageRule(id, "NewAPI 图片兜底", "newapi", null, null, null, null, 40,
                "{\"supportReferenceImages\":false,\"minReferenceImages\":0,\"maxReferenceImages\":0}");
        id = addImageRule(id, "Vertex AI 图片兜底", "vertex_ai", null, null, null, null, 41,
                "{\"supportReferenceImages\":false,\"minReferenceImages\":0,\"maxReferenceImages\":0}");
    }

    private long addVideoRule(long id, String name, String platform, String codeContains,
                              String codeStartsWith, String family, String protocol,
                              int sortOrder, String config) {
        rules.add(CapabilityRule.builder()
                .id(id).name(name).modelType(3)
                .platform(platform).codeContains(codeContains).codeStartsWith(codeStartsWith)
                .family(family).protocol(protocol)
                .sortOrder(sortOrder).status(1)
                .capabilityConfig(config).build());
        return id + 1;
    }

    private long addImageRule(long id, String name, String platform, String codeContains,
                              String codeStartsWith, String family, String protocol,
                              int sortOrder, String config) {
        rules.add(CapabilityRule.builder()
                .id(id).name(name).modelType(2)
                .platform(platform).codeContains(codeContains).codeStartsWith(codeStartsWith)
                .family(family).protocol(protocol)
                .sortOrder(sortOrder).status(1)
                .capabilityConfig(config).build());
        return id + 1;
    }
}
