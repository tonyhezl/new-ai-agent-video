package com.tonyhe.fusion.service.ai.provider;

import com.google.auth.oauth2.GoogleCredentials;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.service.ai.proxy.AiProxySupport;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.lang.reflect.Field;

@Slf4j
final class VertexAgentScopeProxySupport {

    private static final GeminiToolResponseAwareChatFormatter GEMINI_CHAT_FORMATTER =
            new GeminiToolResponseAwareChatFormatter();

    private VertexAgentScopeProxySupport() {
    }

    static Model create(AiProviderContext context,
                        String projectId,
                        String location,
                        GoogleCredentials credentials,
                        GenerateOptions defaultOptions) {
        GeminiChatModel.Builder builder = GeminiChatModel.builder()
            .formatter(GEMINI_CHAT_FORMATTER)
                .modelName(context.getModelName())
                .streamEnabled(true)
                .project(projectId)
                .location(location)
                .vertexAI(true);

        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }

        if (credentials != null) {
            builder.credentials(credentials);
        }

        GeminiChatModel model = builder.build();
        return applyProxy(model, context.getApiConfig(), context.getModelName());
    }

    static GeminiChatModel applyProxy(GeminiChatModel model, ApiConfig apiConfig, String modelName) {
        if (!AiProxySupport.isEnabled(apiConfig)) {
            return model;
        }

        try {
            Object client = readField(GeminiChatModel.class, "client", model);
            Object apiClient = readField(client.getClass(), "apiClient", client);
            Field httpClientField = findField(apiClient.getClass(), "httpClient");
            OkHttpClient baseHttpClient = (OkHttpClient) httpClientField.get(apiClient);
            OkHttpClient proxiedHttpClient = AiProxySupport.okHttpClient(baseHttpClient, apiConfig);
            if (proxiedHttpClient != baseHttpClient) {
                httpClientField.set(apiClient, proxiedHttpClient);
                log.debug("[VertexAI] 已为 AgentScope Gemini 客户端注入代理: model={}", modelName);
            }
            return model;
        } catch (ReflectiveOperationException e) {
            throw new BusinessException("Vertex AI AgentScope 代理配置失败: " + e.getMessage());
        }
    }

    private static Object readField(Class<?> owner, String fieldName, Object target) throws ReflectiveOperationException {
        return findField(owner, fieldName).get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}