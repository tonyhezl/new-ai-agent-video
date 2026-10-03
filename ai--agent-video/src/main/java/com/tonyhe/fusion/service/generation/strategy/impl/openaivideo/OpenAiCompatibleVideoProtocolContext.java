package com.tonyhe.fusion.service.generation.strategy.impl.openaivideo;

import cn.hutool.json.JSONObject;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;

/**
 * OpenAI 兼容视频协议适配上下文。
 */
public record OpenAiCompatibleVideoProtocolContext(
        AiModel model,
        ApiConfig apiConfig,
        VideoTask task,
        JSONObject modelConfig,
        AiModelMetadata metadata
) {
}