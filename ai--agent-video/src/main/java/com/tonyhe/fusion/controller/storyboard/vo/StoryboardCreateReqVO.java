package com.tonyhe.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建分镜请求 VO
 */
@Schema(description = "创建分镜请求")
@Data
public class StoryboardCreateReqVO {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    private Long scriptId;

    @NotBlank(message = "分镜标题不能为空")
    private String title;

    private String description;

    private String customColumns;
}
