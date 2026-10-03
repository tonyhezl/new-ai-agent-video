package com.tonyhe.fusion.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.ai.AgentModelConfig;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.mapper.ai.AgentModelConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 模型绑定配置服务
 * <p>
 * 管理每个 Agent 类型与 AI 模型的绑定关系。
 * 使用 Spring Cache 缓存查询结果，避免每次 Agent 执行时查库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentModelConfigService {

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AiModelService aiModelService;

    /**
     * 获取所有 Agent 模型绑定配置
     */
    public List<AgentModelConfig> listAll() {
        return agentModelConfigMapper.selectList(new LambdaQueryWrapper<>());
    }

    /**
     * 获取所有绑定的 agentType → modelId 映射（带缓存）
     */
    @Cacheable(value = "agentModelConfig", key = "'all'")
    public Map<String, Long> getAgentModelMap() {
        List<AgentModelConfig> configs = listAll();
        return configs.stream()
                .collect(Collectors.toMap(
                        AgentModelConfig::getAgentType,
                        AgentModelConfig::getModelId,
                        (existing, replacement) -> existing));
    }

    /**
     * 根据 Agent 类型获取绑定的 AI 模型
     * <p>
     * 优先从缓存读取绑定关系，再加载对应的 AiModel。
     * 如果绑定的模型已被删除或禁用，返回 null（由调用方 fallback 到默认模型）。
     *
     * @param agentType Agent 类型标识
     * @return 绑定的 AiModel，未配置或模型不可用则返回 null
     */
    public AiModel getModelByAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return null;
        }
        Map<String, Long> map = getAgentModelMap();
        Long modelId = map.get(agentType);
        if (modelId == null) {
            return null;
        }
        AiModel model = aiModelService.getById(modelId);
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())) {
            log.warn("[AgentModelConfig] Agent {} 绑定的模型 {} 不可用（已删除或已禁用），将 fallback 到默认模型",
                    agentType, modelId);
            return null;
        }
        return model;
    }

    /**
     * 批量保存 Agent 模型绑定配置
     * <p>
     * 对于传入的每个 agentType → modelId：
     * - 已存在配置 → 更新 modelId
     * - 不存在配置 → 新增
     * <p>
     * 未传入的 agentType 保持原配置不变（不做删除）。
     *
     * @param configs 要保存的配置列表
     */
    @Transactional
    @CacheEvict(value = "agentModelConfig", allEntries = true)
    public void batchSave(List<AgentModelConfig> configs) {
        for (AgentModelConfig config : configs) {
            if (config.getAgentType() == null || config.getAgentType().isBlank()) {
                continue;
            }
            if (config.getModelId() == null) {
                continue;
            }

            AgentModelConfig existing = agentModelConfigMapper.selectOne(
                    new LambdaQueryWrapper<AgentModelConfig>()
                            .eq(AgentModelConfig::getAgentType, config.getAgentType()));

            if (existing != null) {
                existing.setModelId(config.getModelId());
                agentModelConfigMapper.updateById(existing);
            } else {
                agentModelConfigMapper.insert(AgentModelConfig.builder()
                        .agentType(config.getAgentType())
                        .modelId(config.getModelId())
                        .build());
            }
        }
        log.info("[AgentModelConfig] 批量保存 {} 条 Agent 模型绑定配置", configs.size());
    }
}
