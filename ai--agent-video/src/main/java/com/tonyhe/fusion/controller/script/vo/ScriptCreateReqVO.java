package com.tonyhe.fusion.controller.script.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建剧本请求 VO
 */
@Schema(description = "创建剧本请求")
@Data
public class ScriptCreateReqVO {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    private String title;

    private String content;

    private String rawContent;

    private String storySynopsis;

    private String genre;

    private String targetAudience;

    private Integer durationEstimate;
}
