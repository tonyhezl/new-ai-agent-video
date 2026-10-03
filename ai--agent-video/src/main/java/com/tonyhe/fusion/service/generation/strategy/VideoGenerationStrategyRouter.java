package com.tonyhe.fusion.service.generation.strategy;

import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.service.ai.ApiConfigService;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成策略路由器。
 * <p>
 * 优先按接入渠道（platform）选择策略；当平台为 openai_compatible 但模型有特定协议
 * （如 seedance / wan / kling 等）时，按协议路由到对应的专用策略，
 * 避免通用 OpenAI 策略向不兼容的端点发送请求。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoGenerationStrategyRouter {

    private final List<VideoGenerationStrategy> strategies;
    private final AiModelMetadataResolver aiModelMetadataResolver;
    private final ApiConfigService apiConfigService;

    private volatile Map<String, VideoGenerationStrategy> strategyMap;

    /**
     * 协议 → 策略名映射。
     * 当平台路由命中 openai_compatible 但模型协议在此映射中时，优先使用专用策略。
     */
    private static final Map<String, String> PROTOCOL_TO_STRATEGY;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("seedance", "volcengine");
        m.put("wan", "dashscope");
        m.put("kling", "kling");
        m.put("jimeng", "jimeng");
        m.put("sora", "openai_compatible");
        PROTOCOL_TO_STRATEGY = Map.copyOf(m);
    }

    public VideoGenerationStrategy resolve(AiModel model) {
        if (model == null) {
            throw new BusinessException("视频模型不存在");
        }

        Map<String, VideoGenerationStrategy> candidates = getStrategyMap();
        if (candidates.isEmpty()) {
            throw new BusinessException("没有可用的视频生成策略");
        }

        String platform = aiModelMetadataResolver.resolvePlatform(model);
        String normalizedPlatform = aiModelMetadataResolver.normalizePlatform(platform);
        if (StrUtil.isBlank(normalizedPlatform)) {
            throw new BusinessException("视频模型未绑定有效 API 配置，无法确定接入渠道");
        }

        // 1. 先按平台匹配
        VideoGenerationStrategy strategy = candidates.get(normalizedPlatform);
        // volcengine_plan 共用 volcengine 的视频生成策略
        if (strategy == null && "volcengine_plan".equals(normalizedPlatform)) {
            strategy = candidates.get("volcengine");
        }

        // 2. 如果平台命中的是通用策略（openai_compatible），但模型有特定协议，
        //    尝试按协议路由到专用策略（如 seedance → volcengine）
        //    但仅当 API 地址指向该协议的官方域名时才路由，第三方代理保持 openai_compatible
        if (strategy != null && "openai_compatible".equals(normalizedPlatform)) {
            AiModelMetadata metadata = aiModelMetadataResolver.resolve(model, platform);
            String protocol = metadata.effectiveProtocol();
            String preferredStrategyName = PROTOCOL_TO_STRATEGY.get(protocol);
            if (preferredStrategyName != null) {
                String apiUrl = resolveApiUrl(model);
                boolean isOfficialDomain = isOfficialProtocolDomain(preferredStrategyName, apiUrl);
                if (isOfficialDomain) {
                    VideoGenerationStrategy protocolStrategy = candidates.get(preferredStrategyName);
                    if (protocolStrategy != null) {
                        log.info("[VideoStrategyRouter] 平台={} 但协议={} 且 API 地址指向官方域名，优先路由到专用策略: {}",
                                normalizedPlatform, protocol, preferredStrategyName);
                        return protocolStrategy;
                    }
                } else {
                    log.info("[VideoStrategyRouter] 平台={} 协议={} 但 API 地址非官方域名({})，保持 openai_compatible 策略",
                            normalizedPlatform, protocol, apiUrl);
                }
            }
        }

        if (strategy != null) {
            return strategy;
        }

        throw new BusinessException("未找到匹配的视频生成策略: " + platform);
    }

    private Map<String, VideoGenerationStrategy> getStrategyMap() {
        if (strategyMap == null) {
            synchronized (this) {
                if (strategyMap == null) {
                    Map<String, VideoGenerationStrategy> map = new LinkedHashMap<>();
                    for (VideoGenerationStrategy strategy : strategies) {
                        map.putIfAbsent(normalizeStrategyName(strategy.getName()), strategy);
                    }
                    strategyMap = map;
                }
            }
        }
        return strategyMap;
    }

    private String normalizeStrategyName(String name) {
        String normalized = aiModelMetadataResolver.normalizePlatform(name);
        return StrUtil.isBlank(normalized) ? "" : normalized;
    }

    /**
     * 从模型关联的 ApiConfig 解析 API URL
     */
    private String resolveApiUrl(AiModel model) {
        if (model == null || model.getApiConfigId() == null) {
            return null;
        }
        try {
            ApiConfig config = apiConfigService.getById(model.getApiConfigId());
            return config != null ? config.getApiUrl() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 API URL 是否指向指定策略的官方域名。
     * 第三方代理（如 api.supertoken.cc）不应路由到专用 SDK 策略。
     */
    private boolean isOfficialProtocolDomain(String strategyName, String apiUrl) {
        if (StrUtil.isBlank(apiUrl)) {
            // API URL 为空时，可能是官方平台直接使用默认端点，允许路由
            return true;
        }
        String lowerUrl = apiUrl.toLowerCase();
        return switch (strategyName) {
            case "volcengine" -> lowerUrl.contains("volces.com");
            case "dashscope" -> lowerUrl.contains("dashscope.aliyuncs.com") || lowerUrl.contains("bailian.aliyuncs.com");
            default -> true;
        };
    }
}