package com.tonyhe.fusion.service.ai;

import com.tonyhe.fusion.entity.ai.AgentModelConfig;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.mapper.ai.AgentModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
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
 * AgentModelConfigService 的单元测试。
 * <p>
 * 重点验证：
 * 1. getModelByAgentType 的三级优先级逻辑
 * 2. 模型不可用（已删除/已禁用）时的 fallback 行为
 * 3. batchSave 的 upsert 逻辑
 * 4. 空参数边界情况
 */
@ExtendWith(MockitoExtension.class)
class AgentModelConfigServiceTest {

    @Mock
    private AgentModelConfigMapper agentModelConfigMapper;

    @Mock
    private AiModelService aiModelService;

    @InjectMocks
    private AgentModelConfigService agentModelConfigService;

    private AiModel activeModel;
    private AiModel disabledModel;

    @BeforeEach
    void setUp() {
        activeModel = new AiModel();
        activeModel.setId(100L);
        activeModel.setName("GPT-5.6-Sol");
        activeModel.setCode("gpt-5.6-sol");
        activeModel.setStatus(1);

        disabledModel = new AiModel();
        disabledModel.setId(200L);
        disabledModel.setName("Grok-4.6");
        disabledModel.setCode("grok-4.6");
        disabledModel.setStatus(0); // 已禁用
    }

    // ========== getModelByAgentType ==========

    @Test
    void getModelByAgentType_nullAgentType_returnsNull() {
        AiModel result = agentModelConfigService.getModelByAgentType(null);
        assertNull(result);
    }

    @Test
    void getModelByAgentType_blankAgentType_returnsNull() {
        AiModel result = agentModelConfigService.getModelByAgentType("  ");
        assertNull(result);
    }

    @Test
    void getModelByAgentType_noBinding_returnsNull() {
        AgentModelConfig config = new AgentModelConfig();
        config.setAgentType("script_writer");
        config.setModelId(100L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config));
        // 触发缓存加载
        agentModelConfigService.getAgentModelMap();

        AiModel result = agentModelConfigService.getModelByAgentType("nonexistent_agent");
        assertNull(result);
    }

    @Test
    void getModelByAgentType_bindingExists_modelActive_returnsModel() {
        AgentModelConfig config = new AgentModelConfig();
        config.setAgentType("script_writer");
        config.setModelId(100L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(aiModelService.getById(100L)).thenReturn(activeModel);

        // 加载缓存
        agentModelConfigService.getAgentModelMap();

        AiModel result = agentModelConfigService.getModelByAgentType("script_writer");
        assertNotNull(result);
        assertEquals("gpt-5.6-sol", result.getCode());
    }

    @Test
    void getModelByAgentType_bindingExists_modelDisabled_returnsNull() {
        AgentModelConfig config = new AgentModelConfig();
        config.setAgentType("script_writer");
        config.setModelId(200L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(aiModelService.getById(200L)).thenReturn(disabledModel);

        agentModelConfigService.getAgentModelMap();

        AiModel result = agentModelConfigService.getModelByAgentType("script_writer");
        assertNull(result, "已禁用的模型应返回 null，触发 fallback 到默认模型");
    }

    @Test
    void getModelByAgentType_bindingExists_modelDeleted_returnsNull() {
        AgentModelConfig config = new AgentModelConfig();
        config.setAgentType("script_writer");
        config.setModelId(999L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(aiModelService.getById(999L)).thenReturn(null); // 模型已删除

        agentModelConfigService.getAgentModelMap();

        AiModel result = agentModelConfigService.getModelByAgentType("script_writer");
        assertNull(result, "已删除的模型应返回 null");
    }

    // ========== getAgentModelMap ==========

    @Test
    void getAgentModelMap_emptyConfigs_returnsEmptyMap() {
        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of());

        Map<String, Long> map = agentModelConfigService.getAgentModelMap();
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void getAgentModelMap_multipleConfigs_returnsCorrectMapping() {
        AgentModelConfig config1 = new AgentModelConfig();
        config1.setAgentType("script_writer");
        config1.setModelId(100L);

        AgentModelConfig config2 = new AgentModelConfig();
        config2.setAgentType("script_doctor");
        config2.setModelId(200L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config1, config2));

        Map<String, Long> map = agentModelConfigService.getAgentModelMap();
        assertEquals(2, map.size());
        assertEquals(100L, map.get("script_writer"));
        assertEquals(200L, map.get("script_doctor"));
    }

    @Test
    void getAgentModelMap_duplicateAgentType_keepsFirst() {
        AgentModelConfig config1 = new AgentModelConfig();
        config1.setAgentType("script_writer");
        config1.setModelId(100L);

        AgentModelConfig config2 = new AgentModelConfig();
        config2.setAgentType("script_writer");
        config2.setModelId(200L);

        when(agentModelConfigMapper.selectList(any())).thenReturn(List.of(config1, config2));

        Map<String, Long> map = agentModelConfigService.getAgentModelMap();
        assertEquals(1, map.size());
        assertEquals(100L, map.get("script_writer"), "重复 agentType 应保留第一个");
    }

    // ========== batchSave ==========

    @Test
    void batchSave_newConfig_inserts() {
        AgentModelConfig newConfig = new AgentModelConfig();
        newConfig.setAgentType("script_writer");
        newConfig.setModelId(100L);

        when(agentModelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        agentModelConfigService.batchSave(List.of(newConfig));

        verify(agentModelConfigMapper).insert(any(AgentModelConfig.class));
        verify(agentModelConfigMapper, never()).updateById(any(AgentModelConfig.class));
    }

    @Test
    void batchSave_existingConfig_updates() {
        AgentModelConfig existing = new AgentModelConfig();
        existing.setId(1L);
        existing.setAgentType("script_writer");
        existing.setModelId(100L);

        AgentModelConfig update = new AgentModelConfig();
        update.setAgentType("script_writer");
        update.setModelId(200L);

        when(agentModelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        agentModelConfigService.batchSave(List.of(update));

        verify(agentModelConfigMapper).updateById(argThat((AgentModelConfig c) ->
                c.getModelId().equals(200L) && c.getId().equals(1L)));
        verify(agentModelConfigMapper, never()).insert(any(AgentModelConfig.class));
    }

    @Test
    void batchSave_blankAgentType_skipped() {
        AgentModelConfig blankConfig = new AgentModelConfig();
        blankConfig.setAgentType("");
        blankConfig.setModelId(100L);

        agentModelConfigService.batchSave(List.of(blankConfig));

        verify(agentModelConfigMapper, never()).selectOne(any());
        verify(agentModelConfigMapper, never()).insert(any(AgentModelConfig.class));
        verify(agentModelConfigMapper, never()).updateById(any(AgentModelConfig.class));
    }

    @Test
    void batchSave_nullModelId_skipped() {
        AgentModelConfig nullModelConfig = new AgentModelConfig();
        nullModelConfig.setAgentType("script_writer");
        nullModelConfig.setModelId(null);

        agentModelConfigService.batchSave(List.of(nullModelConfig));

        verify(agentModelConfigMapper, never()).selectOne(any());
        verify(agentModelConfigMapper, never()).insert(any(AgentModelConfig.class));
        verify(agentModelConfigMapper, never()).updateById(any(AgentModelConfig.class));
    }

    @Test
    void batchSave_mixedConfigs_insertsAndUpdates() {
        AgentModelConfig existing = new AgentModelConfig();
        existing.setId(1L);
        existing.setAgentType("script_doctor");
        existing.setModelId(100L);

        AgentModelConfig updateConfig = new AgentModelConfig();
        updateConfig.setAgentType("script_doctor");
        updateConfig.setModelId(200L);

        AgentModelConfig newConfig = new AgentModelConfig();
        newConfig.setAgentType("prompt_reviewer");
        newConfig.setModelId(300L);

        when(agentModelConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)   // 第一次调用：script_doctor 已存在
                .thenReturn(null);       // 第二次调用：prompt_reviewer 不存在

        agentModelConfigService.batchSave(List.of(updateConfig, newConfig));

        verify(agentModelConfigMapper).updateById(argThat((AgentModelConfig c) -> c.getModelId().equals(200L)));
        verify(agentModelConfigMapper).insert(argThat((AgentModelConfig c) ->
                c.getAgentType().equals("prompt_reviewer") && c.getModelId().equals(300L)));
    }

    @Test
    void batchSave_emptyList_noOp() {
        agentModelConfigService.batchSave(List.of());

        verify(agentModelConfigMapper, never()).selectOne(any());
        verify(agentModelConfigMapper, never()).insert(any(AgentModelConfig.class));
        verify(agentModelConfigMapper, never()).updateById(any(AgentModelConfig.class));
    }
}
