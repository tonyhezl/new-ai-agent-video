package com.tonyhe.fusion.service.ai.model;

/**
 * 远程模型补充元数据。
 */
public record RemoteModelMetadata(
        String providerPlatform,
        String displayName,
        String modelFamily,
        String modelProtocol,
        Integer modelType,
        boolean inferred
) {
}