package com.tonyhe.fusion.service.ai.provider;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.GeminiChatModel;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VertexAgentScopeProxySupportTests {

    @Test
    void createShouldEnableDynamicThinkingWhenReasoningEnabled() throws Exception {
        AiProviderContext context = AiProviderContext.builder()
                .model(AiModel.builder().supportReasoning(true).build())
                .modelName("gemini-2.5-flash")
                .build();

        GeminiChatModel model = (GeminiChatModel) VertexAgentScopeProxySupport.create(
                context,
                "demo-project",
                "global",
                GoogleCredentials.create(new AccessToken("test-token", Date.from(Instant.now().plusSeconds(600)))),
                GenerateOptions.builder().thinkingBudget(-1).build());

        try {
            assertEquals(-1, extractDefaultOptions(model).getThinkingBudget());
        } finally {
            model.close();
        }
    }

    @Test
    void applyProxyShouldPatchUnderlyingGoogleGenAiHttpClient() throws Exception {
        GeminiChatModel model = GeminiChatModel.builder()
                .modelName("gemini-2.5-flash-lite")
                .streamEnabled(true)
                .project("demo-project")
                .location("global")
                .vertexAI(true)
                .credentials(GoogleCredentials.create(new AccessToken("test-token", Date.from(Instant.now().plusSeconds(600)))))
                .build();

        ApiConfig apiConfig = ApiConfig.builder()
                .proxyType("http")
                .proxyHost("127.0.0.1")
                .proxyPort(7890)
                .build();

        try {
            VertexAgentScopeProxySupport.applyProxy(model, apiConfig, "gemini-2.5-flash-lite");

            OkHttpClient httpClient = extractHttpClient(model);
            Proxy proxy = httpClient.proxy();
            assertNotNull(proxy);
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            assertEquals("127.0.0.1", address.getHostString());
            assertEquals(7890, address.getPort());
        } finally {
            model.close();
        }
    }

    private OkHttpClient extractHttpClient(GeminiChatModel model) throws Exception {
        Field clientField = findField(GeminiChatModel.class, "client");
        Object client = clientField.get(model);
        Field apiClientField = findField(client.getClass(), "apiClient");
        Object apiClient = apiClientField.get(client);
        Field httpClientField = findField(apiClient.getClass(), "httpClient");
        return (OkHttpClient) httpClientField.get(apiClient);
    }

    private GenerateOptions extractDefaultOptions(GeminiChatModel model) throws Exception {
        Field defaultOptionsField = findField(GeminiChatModel.class, "defaultOptions");
        return (GenerateOptions) defaultOptionsField.get(model);
    }

    private Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
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