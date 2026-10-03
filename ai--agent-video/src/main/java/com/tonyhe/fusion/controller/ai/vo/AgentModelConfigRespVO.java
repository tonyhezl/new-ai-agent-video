package com.tonyhe.fusion.controller.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Agent 模型绑定配置响应")
public class AgentModelConfigRespVO {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "Agent 类型标识")
    private String agentType;

    @Schema(description = "Agent 显示名称")
    private String agentName;

    @Schema(description = "绑定的模型ID")
    private Long modelId;

    @Schema(description = "绑定的模型名称")
    private String modelName;

    @Schema(description = "绑定的模型 code")
    private String modelCode;

    @Schema(description = "模型是否支持 reasoning")
    private Boolean supportReasoning;

    @Schema(description = "模型是否支持视觉")
    private Boolean supportVision;

    @Schema(description = "Agent 是否启用工具")
    private Boolean enableTools;
}
