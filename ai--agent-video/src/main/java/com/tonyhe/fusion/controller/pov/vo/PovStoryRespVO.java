package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "第一人称猎奇故事响应 VO")
@Data
public class PovStoryRespVO {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "关联项目ID")
    private Long projectId;

    @Schema(description = "故事标题")
    private String title;

    @Schema(description = "黄金钩子文案")
    private String hookText;

    @Schema(description = "题材类型")
    private String topicGenre;

    @Schema(description = "完整第一人称独白脚本")
    private String fullScript;

    @Schema(description = "预估时长(秒)")
    private Integer totalDurationEstimate;

    @Schema(description = "配音音色")
    private String voiceId;

    @Schema(description = "背景音效风格")
    private String bgmStyle;

    @Schema(description = "状态: DRAFT/GENERATING/COMPLETED/FAILED")
    private String status;

    @Schema(description = "可见性: PRIVATE/PROJECT/TEAM")
    private String visibility;

    @Schema(description = "最终合成视频URL")
    private String finalVideoUrl;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "镜头列表")
    private List<PovShotRespVO> shots;

    @Schema(description = "POV故事独立资产列表")
    private List<PovAssetRespVO> assets;
}
