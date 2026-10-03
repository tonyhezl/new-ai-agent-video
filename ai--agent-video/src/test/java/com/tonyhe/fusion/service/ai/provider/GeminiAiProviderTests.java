package com.tonyhe.fusion.service.ai.provider;

import com.tonyhe.fusion.entity.ai.AiModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiAiProviderTests {

    @Test
    void createAgentScopeModelShouldEnableDynamicThinkingWhenReasoningEnabled() throws Exception {
        GeminiAiProvider provider = new GeminiAiProvider();
        AiProviderContext context = AiProviderContext.builder()
                .platform("gemini")
                .apiKey("test-key")
                .modelName("gemini-2.5-flash")
                .model(AiModel.builder().supportReasoning(true).build())
                .build();

        Model model = provider.createAgentScopeModel(context);

        assertThat(model).isInstanceOf(GeminiChatModel.class);
        assertThat(extractDefaultOptions((GeminiChatModel) model).getThinkingBudget()).isEqualTo(-1);
        ((GeminiChatModel) model).close();
    }

    private GenerateOptions extractDefaultOptions(GeminiChatModel model) throws Exception {
        Field field = findField(GeminiChatModel.class, "defaultOptions");
        return (GenerateOptions) field.get(model);
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