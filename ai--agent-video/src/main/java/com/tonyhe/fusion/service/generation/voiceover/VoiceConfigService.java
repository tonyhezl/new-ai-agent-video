package com.tonyhe.fusion.service.generation.voiceover;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ApiConfigService;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategy;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategyRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色音色配置服务
 * <p>
 * 在 Asset.properties JSON 中读写 voiceConfig 字段，无需改表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceConfigService {

    private static final int MODEL_TYPE_TTS = 4;
    private static final String SAMPLE_TEXT = "你好，这是我的声音样本，用于视频生成时的音色参考。";

    private final AssetService assetService;
    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final AudioGenerationStrategyRouter audioStrategyRouter;

    /**
     * 获取角色音色配置
     */
    public VoiceConfig getVoiceConfig(Long assetId) {
        Asset asset = assetService.getById(assetId);
        return extractVoiceConfig(asset);
    }

    /**
     * 设置角色音色配置
     */
    @CacheEvict(value = "asset", allEntries = true)
    public VoiceConfig setVoiceConfig(Long assetId, VoiceConfig voiceConfig) {
        Asset asset = assetService.getById(assetId);
        JSONObject properties = parseProperties(asset.getProperties());
        properties.set("voiceConfig", JSONUtil.parseObj(JSONUtil.toJsonStr(voiceConfig)));
        asset.setProperties(properties.toString());
        assetService.update(asset);
        log.info("[VoiceConfig] 角色音色已更新: assetId={}, voiceId={}", assetId, voiceConfig.getVoiceId());
        return voiceConfig;
    }

    /**
     * 从 Asset 提取 voiceConfig（内部方法，不触发缓存）
     */
    VoiceConfig extractVoiceConfig(Asset asset) {
        if (asset == null || StrUtil.isBlank(asset.getProperties())) {
            return null;
        }
        try {
            JSONObject properties = JSONUtil.parseObj(asset.getProperties());
            JSONObject voiceConfig = properties.getJSONObject("voiceConfig");
            if (voiceConfig == null) {
                return null;
            }
            return JSONUtil.toBean(voiceConfig, VoiceConfig.class);
        } catch (Exception e) {
            log.warn("[VoiceConfig] 解析失败: assetId={}", asset.getId());
            return null;
        }
    }

    private JSONObject parseProperties(String propertiesJson) {
        if (StrUtil.isBlank(propertiesJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(propertiesJson);
        } catch (Exception e) {
            log.warn("[VoiceConfig] properties 解析失败，重置: {}", e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * 使用当前音色配置生成一段样本音频，并保存为 referenceAudioUrl。
     * 用于视频生成时传入参考音频进行音色克隆。
     */
    @CacheEvict(value = "asset", allEntries = true)
    public VoiceConfig generateSampleAudio(Long assetId) {
        VoiceConfig voiceConfig = getVoiceConfig(assetId);
        if (voiceConfig == null || StrUtil.isBlank(voiceConfig.getVoiceId())) {
            throw new BusinessException("请先配置音色（voiceId）再生成参考音频");
        }

        // 解析 TTS 模型
        AiModel model = resolveTtsModel();
        ApiConfig apiConfig = resolveApiConfig(model);
        AudioGenerationStrategy strategy = audioStrategyRouter.resolve(model);

        // 构建样本 AudioTask
        String effectiveVoiceId = StrUtil.isNotBlank(voiceConfig.getCustomVoiceId())
                ? voiceConfig.getCustomVoiceId() : voiceConfig.getVoiceId();
        AudioTask sampleTask = AudioTask.builder()
                .taskId(IdUtil.fastSimpleUUID())
                .text(SAMPLE_TEXT)
                .voiceConfig(JSONUtil.toJsonStr(voiceConfig))
                .modelId(model.getId())
                .modelCode(model.getCode())
                .build();

        // 注入鉴权信息
        AiModel modelWithAuth = injectAuthInfo(model, apiConfig);
        String baseUrl = resolveTtsEndpoint(apiConfig);
        String apiKey = apiConfig.getApiKey();

        log.info("[VoiceConfig] 开始生成参考音频: assetId={}, voiceId={}", assetId, effectiveVoiceId);
        AudioGenerationStrategy.AudioSynthesizeResult result =
                strategy.synthesize(sampleTask, modelWithAuth, apiKey, baseUrl);

        // 保存 referenceAudioUrl
        voiceConfig.setReferenceAudioUrl(result.audioUrl());
        Asset asset = assetService.getById(assetId);
        JSONObject properties = parseProperties(asset.getProperties());
        properties.set("voiceConfig", JSONUtil.parseObj(JSONUtil.toJsonStr(voiceConfig)));
        asset.setProperties(properties.toString());
        assetService.update(asset);

        log.info("[VoiceConfig] 参考音频已生成并保存: assetId={}, referenceAudioUrl={}", assetId, result.audioUrl());
        return voiceConfig;
    }

    private AiModel resolveTtsModel() {
        AiModel model = aiModelService.getDefaultByType(MODEL_TYPE_TTS);
        if (model != null) {
            return model;
        }
        List<AiModel> ttsModels = aiModelService.getListByType(MODEL_TYPE_TTS);
        if (ttsModels.isEmpty()) {
            throw new BusinessException("未配置 TTS 模型，请先在 AI 服务管理中添加 TTS 模型");
        }
        return ttsModels.get(0);
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model != null && model.getApiConfigId() != null) {
            ApiConfig config = apiConfigService.getById(model.getApiConfigId());
            if (config != null) {
                return config;
            }
        }
        throw new BusinessException("未找到 TTS API 配置，请在系统设置中配置火山引擎语音 API Key");
    }

    private String resolveTtsEndpoint(ApiConfig apiConfig) {
        if (StrUtil.isNotBlank(apiConfig.getApiUrl())) {
            return apiConfig.getApiUrl();
        }
        String platform = apiConfig.getPlatform();
        if ("volcengine_plan".equalsIgnoreCase(platform)) {
            return "https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional";
        }
        return null;
    }

    private AiModel injectAuthInfo(AiModel model, ApiConfig apiConfig) {
        JSONObject config = JSONUtil.parseObj(model.getConfig() != null ? model.getConfig() : "{}");
        JSONObject auth = JSONUtil.createObj()
                .set("apiKey", apiConfig.getApiKey());
        if (StrUtil.isNotBlank(apiConfig.getAppId())) {
            auth.set("appId", apiConfig.getAppId());
        }
        if (StrUtil.isNotBlank(apiConfig.getAppSecret())) {
            auth.set("appSecret", apiConfig.getAppSecret());
        }
        config.set("_auth", auth);
        AiModel copy = new AiModel();
        copy.setId(model.getId());
        copy.setCode(model.getCode());
        copy.setName(model.getName());
        copy.setModelType(model.getModelType());
        copy.setModelProtocol(model.getModelProtocol());
        copy.setApiConfigId(model.getApiConfigId());
        copy.setConfig(config.toString());
        return copy;
    }

    /**
     * 音色配置
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class VoiceConfig {
        private String voiceId;
        private String voiceName;
        private Double speed;
        private Double pitch;
        private String emotion;
        private String customVoiceId;
        private String referenceAudioUrl;
    }
}
