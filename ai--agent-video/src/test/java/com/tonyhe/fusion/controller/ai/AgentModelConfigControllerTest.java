package com.tonyhe.fusion.controller.ai;

import com.tonyhe.fusion.controller.ai.vo.AgentModelConfigBatchSaveReqVO;
import com.tonyhe.fusion.controller.ai.vo.AgentModelConfigRespVO;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.config.ai.AiAgentDefinition;
import com.tonyhe.fusion.entity.ai.AgentModelConfig;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.service.ai.AgentModelConfigService;
import com.tonyhe.fusion.service.ai.AiAgentService;
import com.tonyhe.fusion.service.ai.AiModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentModelConfigController 的单元测试。
 * <p>
 * 重点验证：
 * 1. list 接口正确组装 Agent 定义 + 模型绑定信息
 * 2. batchSave 接口正确传递配置到 service 层
 * 3. 未绑定模型的 Agent 返回 null 模型字段
 */
@ExtendWith(MockitoExtension.class)
class AgentModelConfigControllerTest {

    @Mock
    private AgentModelConfigService agentModelConfigService;

    @Mock
    private AiAgentService aiAgentService;

    @Mock
    private AiModelService aiModelService;

    @InjectMocks
    private AgentModelConfigController controller;

    @Test
    void list_returnsAllAgentsWithBoundModelInfo() {
        AiAgentDefinition agent1 = AiAgentDefinition.builder()
                .type("script_writer").name("AI 剧本创作").enableTools(1).build();
        AiAgentDefinition agent2 = AiAgentDefinition.builder()
                .type("script_doctor").name("剧本医生").enableTools(1).build();
        AiAgentDefinition agent3 = AiAgentDefinition.builder()
                .type("prompt_reviewer").name("Prompt 校正").enableTools(0).build();

        when(aiAgentService.getAll()).thenReturn(List.of(agent1, agent2, agent3));
        when(agentModelConfigService.getAgentModelMap())
                .thenReturn(Map.of("script_writer", 100L, "script_doctor", 200L));

        AiModel model100 = new AiModel();
        model100.setId(100L);
        model100.setName("GPT-5.6-Sol");
        model100.setCode("gpt-5.6-sol");
        model100.setSupportReasoning(false);
        model100.setSupportVision(true);

        AiModel model200 = new AiModel();
        model200.setId(200L);
        model200.setName("Grok-4.6");
        model200.setCode("grok-4.6");
        model200.setSupportReasoning(true);
        model200.setSupportVision(false);

        when(aiModelService.getById(100L)).thenReturn(model100);
        when(aiModelService.getById(200L)).thenReturn(model200);

        CommonResult<List<AgentModelConfigRespVO>> result = controller.list();

        assertNotNull(result);
        List<AgentModelConfigRespVO> data = result.getData();
        assertEquals(3, data.size());

        // script_writer — 有绑定
        AgentModelConfigRespVO vo1 = data.stream()
                .filter(v -> v.getAgentType().equals("script_writer")).findFirst().orElseThrow();
        assertEquals("AI 剧本创作", vo1.getAgentName());
        assertEquals(100L, vo1.getModelId());
        assertEquals("gpt-5.6-sol", vo1.getModelCode());
        assertTrue(vo1.getEnableTools());
        assertFalse(vo1.getSupportReasoning());
        assertTrue(vo1.getSupportVision());

        // script_doctor — 有绑定，reasoning 模型
        AgentModelConfigRespVO vo2 = data.stream()
                .filter(v -> v.getAgentType().equals("script_doctor")).findFirst().orElseThrow();
        assertEquals("剧本医生", vo2.getAgentName());
        assertEquals(200L, vo2.getModelId());
        assertEquals("grok-4.6", vo2.getModelCode());
        assertTrue(vo2.getSupportReasoning());

        // prompt_reviewer — 无绑定
        AgentModelConfigRespVO vo3 = data.stream()
                .filter(v -> v.getAgentType().equals("prompt_reviewer")).findFirst().orElseThrow();
        assertEquals("Prompt 校正", vo3.getAgentName());
        assertNull(vo3.getModelId());
        assertNull(vo3.getModelName());
        assertFalse(vo3.getEnableTools());
    }

    @Test
    void list_boundModelDeleted_modelFieldsNull() {
        AiAgentDefinition agent = AiAgentDefinition.builder()
                .type("script_writer").name("AI 剧本创作").enableTools(1).build();

        when(aiAgentService.getAll()).thenReturn(List.of(agent));
        when(agentModelConfigService.getAgentModelMap())
                .thenReturn(Map.of("script_writer", 999L));
        when(aiModelService.getById(999L)).thenReturn(null); // 模型已删除

        CommonResult<List<AgentModelConfigRespVO>> result = controller.list();

        AgentModelConfigRespVO vo = result.getData().get(0);
        assertEquals("script_writer", vo.getAgentType());
        assertEquals(999L, vo.getModelId());
        assertNull(vo.getModelName(), "模型已删除时 modelName 应为 null");
        assertNull(vo.getModelCode());
    }

    @Test
    void batchSave_validConfigs_passesToService() {
        AgentModelConfigBatchSaveReqVO req = new AgentModelConfigBatchSaveReqVO();
        AgentModelConfigBatchSaveReqVO.Item item1 = new AgentModelConfigBatchSaveReqVO.Item();
        item1.setAgentType("script_writer");
        item1.setModelId(100L);
        AgentModelConfigBatchSaveReqVO.Item item2 = new AgentModelConfigBatchSaveReqVO.Item();
        item2.setAgentType("script_doctor");
        item2.setModelId(200L);
        req.setConfigs(List.of(item1, item2));

        CommonResult<Boolean> result = controller.batchSave(req);

        assertNotNull(result);
        assertTrue(result.getData());
        verify(agentModelConfigService).batchSave(argThat((java.util.List<AgentModelConfig> configs) -> {
            if (configs.size() != 2) return false;
            boolean hasWriter = configs.stream().anyMatch(c ->
                    c.getAgentType().equals("script_writer") && c.getModelId().equals(100L));
            boolean hasDoctor = configs.stream().anyMatch(c ->
                    c.getAgentType().equals("script_doctor") && c.getModelId().equals(200L));
            return hasWriter && hasDoctor;
        }));
    }

    @Test
    void batchSave_nullConfigs_noOp() {
        AgentModelConfigBatchSaveReqVO req = new AgentModelConfigBatchSaveReqVO();
        req.setConfigs(null);

        CommonResult<Boolean> result = controller.batchSave(req);

        assertNotNull(result);
        assertTrue(result.getData());
        verify(agentModelConfigService).batchSave(argThat((java.util.List<AgentModelConfig> list) -> list.isEmpty()));
    }

    @Test
    void batchSave_emptyConfigs_noOp() {
        AgentModelConfigBatchSaveReqVO req = new AgentModelConfigBatchSaveReqVO();
        req.setConfigs(List.of());

        CommonResult<Boolean> result = controller.batchSave(req);

        assertTrue(result.getData());
        verify(agentModelConfigService).batchSave(argThat((java.util.List<AgentModelConfig> list) -> list.isEmpty()));
    }
}
