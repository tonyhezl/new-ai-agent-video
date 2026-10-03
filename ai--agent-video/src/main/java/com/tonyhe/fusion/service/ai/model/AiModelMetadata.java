package com.tonyhe.fusion.service.ai.model;

import cn.hutool.core.util.StrUtil;

/**
 * AI 模型显式元数据。
 */
public record AiModelMetadata(
        String platform,
        String normalizedPlatform,
        String modelFamily,
        String modelProtocol
) {

    public String effectiveFamily() {
        return StrUtil.blankToDefault(modelFamily, "generic");
    }

    public String effectiveProtocol() {
        return StrUtil.blankToDefault(modelProtocol, "generic");
    }
}