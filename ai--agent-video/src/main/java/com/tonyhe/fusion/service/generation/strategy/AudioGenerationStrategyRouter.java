package com.tonyhe.fusion.service.generation.strategy;

import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频生成策略路由器
 * <p>
 * 按 TTS 模型绑定的 API 配置平台路由到对应的 AudioGenerationStrategy。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationStrategyRouter {

    private final List<AudioGenerationStrategy> strategies;
    private final AiModelMetadataResolver aiModelMetadataResolver;

    private volatile Map<String, AudioGenerationStrategy> strategyMap;

    public AudioGenerationStrategy resolve(AiModel model) {
        if (model == null) {
            throw new BusinessException("TTS 模型不存在");
        }

        Map<String, AudioGenerationStrategy> candidates = getStrategyMap();
        if (candidates.isEmpty()) {
            throw new BusinessException("没有可用的音频生成策略");
        }

        String platform = aiModelMetadataResolver.resolvePlatform(model);
        String normalizedPlatform = aiModelMetadataResolver.normalizePlatform(platform);
        if (StrUtil.isBlank(normalizedPlatform)) {
            throw new BusinessException("TTS 模型未绑定有效 API 配置，无法确定接入渠道");
        }

        AudioGenerationStrategy strategy = candidates.get(normalizedPlatform);
        if (strategy == null && "volcengine_plan".equals(normalizedPlatform)) {
            strategy = candidates.get("volcengine");
        }

        if (strategy != null) {
            return strategy;
        }

        throw new BusinessException("未找到匹配的音频生成策略: " + platform);
    }

    private Map<String, AudioGenerationStrategy> getStrategyMap() {
        if (strategyMap == null) {
            synchronized (this) {
                if (strategyMap == null) {
                    Map<String, AudioGenerationStrategy> map = new LinkedHashMap<>();
                    for (AudioGenerationStrategy strategy : strategies) {
                        map.putIfAbsent(
                                aiModelMetadataResolver.normalizePlatform(strategy.getName()),
                                strategy);
                    }
                    strategyMap = map;
                }
            }
        }
        return strategyMap;
    }
}
