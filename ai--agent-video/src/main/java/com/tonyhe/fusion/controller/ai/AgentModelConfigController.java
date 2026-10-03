package com.tonyhe.fusion.controller.ai;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.ai.vo.AgentModelConfigBatchSaveReqVO;
import com.tonyhe.fusion.controller.ai.vo.AgentModelConfigRespVO;
import com.tonyhe.fusion.entity.ai.AgentModelConfig;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.config.ai.AiAgentDefinition;
import com.tonyhe.fusion.service.ai.AgentModelConfigService;
import com.tonyhe.fusion.service.ai.AiAgentService;
import com.tonyhe.fusion.service.ai.AiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "Agent 模型配置")
@RestController
@RequestMapping("/api/ai/agent-model-config")
@RequiredArgsConstructor
public class AgentModelConfigController {

    private final AgentModelConfigService agentModelConfigService;
    private final AiAgentService aiAgentService;
    private final AiModelService aiModelService;

    @GetMapping("/list")
    @Operation(summary = "获取所有 Agent 及其模型绑定配置")
    public CommonResult<List<AgentModelConfigRespVO>> list() {
        List<AiAgentDefinition> agents = aiAgentService.getAll();
        Map<String, Long> configMap = agentModelConfigService.getAgentModelMap();

        List<AgentModelConfigRespVO> result = new ArrayList<>();
        for (AiAgentDefinition agent : agents) {
            AgentModelConfigRespVO vo = new AgentModelConfigRespVO();
            vo.setAgentType(agent.getType());
            vo.setAgentName(agent.getName());
            vo.setEnableTools(Integer.valueOf(1).equals(agent.getEnableTools()));

            Long modelId = configMap.get(agent.getType());
            if (modelId != null) {
                vo.setModelId(modelId);
                AiModel model = aiModelService.getById(modelId);
                if (model != null) {
                    vo.setModelName(model.getName());
                    vo.setModelCode(model.getCode());
                    vo.setSupportReasoning(model.getSupportReasoning());
                    vo.setSupportVision(model.getSupportVision());
                }
            }
            result.add(vo);
        }
        return success(result);
    }

    @PostMapping("/batch-save")
    @Operation(summary = "批量保存 Agent 模型绑定配置")
    @PreAuthorize("@permissionService.has('ai:model:manage')")
    public CommonResult<Boolean> batchSave(@RequestBody AgentModelConfigBatchSaveReqVO reqVO) {
        List<AgentModelConfig> configs = new ArrayList<>();
        if (reqVO.getConfigs() != null) {
            for (AgentModelConfigBatchSaveReqVO.Item item : reqVO.getConfigs()) {
                configs.add(AgentModelConfig.builder()
                        .agentType(item.getAgentType())
                        .modelId(item.getModelId())
                        .build());
            }
        }
        agentModelConfigService.batchSave(configs);
        return success(true);
    }
}
