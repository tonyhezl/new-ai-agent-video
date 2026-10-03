package com.tonyhe.fusion.controller.pov.vo;

import com.tonyhe.fusion.common.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "POV 故事分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class PovStoryPageReqVO extends PageParam {

    @Schema(description = "题材类型: URBAN_MYSTERY/DEEP_EXPLORATION/SECRET_REVEAL/TECH_PARANOIA")
    private String topicGenre;

    @Schema(description = "状态: DRAFT/GENERATING/COMPLETED/FAILED")
    private String status;

    @Schema(description = "搜索关键词（标题或文案）")
    private String keyword;

    @Schema(description = "关联项目ID")
    private Long projectId;
}
