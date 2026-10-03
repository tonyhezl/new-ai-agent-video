package com.tonyhe.fusion.service.ai.provider;

import com.tonyhe.fusion.controller.ai.vo.RemoteModelVO;
import com.tonyhe.fusion.enums.ai.AiModelTypeEnum;
import com.tonyhe.fusion.service.ai.dashscope.DashScopeGenerationSupport;
import com.tonyhe.fusion.service.ai.proxy.AiProxySupport;
import io.agentscope.core.model.DashScopeChatModel.Builder;
import io.agentscope.core.model.Model;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope 提供商。
 */
@Component
@Slf4j
public class DashScopeAiProvider extends AbstractAiProvider {

    @Override
    public boolean supports(String platform) {
        return platform != null && "dashscope".equalsIgnoreCase(platform);
    }

    @Override
    public ChatModel createChatModel(AiProviderContext context) {
        String apiKey = context.getApiKey();
        Map<String, Object> config = context.getConfig();
        String modelName = context.getModelName();

        requireApiKey(apiKey, "DashScope");

        String compatibleBaseUrl = DashScopeGenerationSupport.resolveCompatibleBaseUrl(context.getBaseUrl());

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        apiBuilder.restClientBuilder(AiProxySupport.restClientBuilder(
            context.getApiConfig(), 60 * 1000, 3 * 60 * 1000));
        apiBuilder.webClientBuilder(AiProxySupport.webClientBuilder(
            context.getApiConfig(), "dashscope-provider", Duration.ofSeconds(60)));
        apiBuilder.baseUrl(compatibleBaseUrl);
        apiBuilder.completionsPath("/chat/completions");

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(modelName);
        applyDouble(config, "temperature", optionsBuilder::temperature);
        applyDouble(config, "topP", optionsBuilder::topP);
        applyInt(config, "maxTokens", optionsBuilder::maxTokens);

        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    @Override
    public Model createAgentScopeModel(AiProviderContext context) {
        Builder builder = io.agentscope.core.model.DashScopeChatModel.builder()
                .apiKey(context.getApiKey())
                .modelName(context.getModelName())
                .baseUrl(DashScopeGenerationSupport.resolveRootBaseUrl(context.getBaseUrl()))
                .stream(true);
        if (isReasoningEnabled(context)) {
            builder.enableThinking(true);
        }
        builder.httpTransport(AiProxySupport.agentScopeHttpTransport(context.getApiConfig()));
        return builder.build();
    }

    @Override
    public List<RemoteModelVO> listRemoteModels(AiProviderContext context) {
        requireApiKey(context.getApiKey(), "DashScope");
        String baseUrl = resolveApiBaseUrl(context.getBaseUrl());
        String url = joinUrl(baseUrl, "/models");
        log.info("[DashScopeAiProvider] 获取远程模型列表: {}", url);
        String response = executeGet(url, Map.of("Authorization", "Bearer " + context.getApiKey()), context.getApiConfig());
        List<RemoteModelVO> models = new ArrayList<>(parseDataArrayModels(response, "dashscope"));
        models.forEach(model -> {
            if (model.getModelType() == null) {
                model.setModelType(DashScopeGenerationSupport.inferRemoteModelType(model.getId()));
            }
        });
        mergeKnownGenerationModels(models);
        return models;
    }

    private String resolveApiBaseUrl(String baseUrl) {
        return DashScopeGenerationSupport.resolveCompatibleBaseUrl(baseUrl);
    }

    private void mergeKnownGenerationModels(List<RemoteModelVO> models) {
        Set<String> existingIds = new LinkedHashSet<>();
        for (RemoteModelVO model : models) {
            if (model.getId() != null) {
                existingIds.add(model.getId());
            }
        }
        addKnownModels(models, existingIds, AiModelTypeEnum.IMAGE.getType(),
                "wan2.7-image-pro",
                "wan2.7-image",
                "qwen-image-2.0-pro",
                "qwen-image-2.0",
                "qwen-image-max",
                "qwen-image-plus",
                "qwen-image");
        addKnownModels(models, existingIds, AiModelTypeEnum.VIDEO.getType(),
                "wan2.7-t2v",
                "wan2.7-i2v",
                "wan2.7-r2v",
                "wan2.7-videoedit",
                "wan2.6-t2v",
                "wan2.6-i2v",
                "wan2.6-i2v-flash",
                "wan2.2-kf2v-flash",
                "wanx2.1-t2v-plus",
                "wanx2.1-t2v-turbo",
                "wanx2.1-i2v-plus",
                "wanx2.1-i2v-turbo",
                "wanx2.1-kf2v-plus");
    }

    private void addKnownModels(List<RemoteModelVO> models, Set<String> existingIds, Integer modelType,
                                String... modelIds) {
        for (String modelId : modelIds) {
            if (existingIds.add(modelId)) {
                models.add(RemoteModelVO.builder()
                        .id(modelId)
                        .ownedBy("dashscope")
                        .modelType(modelType)
                        .build());
            }
        }
    }
}
