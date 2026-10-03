package com.tonyhe.fusion.controller.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Agent 模型绑定批量保存请求")
public class AgentModelConfigBatchSaveReqVO {

    @Schema(description = "配置列表")
    private List<Item> configs;

    @Data
    @Schema(description = "单个 Agent 模型绑定")
    public static class Item {
        @Schema(description = "Agent 类型标识", requiredMode = Schema.RequiredMode.REQUIRED)
        private String agentType;

        @Schema(description = "绑定的模型ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long modelId;
    }
}
