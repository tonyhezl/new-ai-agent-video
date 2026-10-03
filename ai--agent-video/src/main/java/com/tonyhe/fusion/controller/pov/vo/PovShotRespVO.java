package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "POV 镜头响应 VO")
@Data
public class PovShotRespVO {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "所属故事ID")
    private Long storyId;

    @Schema(description = "镜头序号")
    private Integer shotOrder;

    @Schema(description = "叙事阶段: HOOK/EXPLORE/TWIST/CLIMAX")
    private String narrativeStage;

    @Schema(description = "第一人称独白台词")
    private String voiceText;

    @Schema(description = "V1兼容视觉提示词")
    private String visualPrompt;

    @Schema(description = "视频业务提示词")
    private String videoPrompt;

    @Schema(description = "负向提示词")
    private String negativePrompt;

    @Schema(description = "机位视角")
    private String cameraView;

    @Schema(description = "运镜方式")
    private String cameraMovement;

    @Schema(description = "音效触发点")
    private String sfxCue;

    @Schema(description = "镜头时长(秒)")
    private BigDecimal durationSec;

    @Schema(description = "生图产物URL")
    private String imageUrl;

    @Schema(description = "生视频产物URL")
    private String videoUrl;

    @Schema(description = "独白音频URL")
    private String audioUrl;

    @Schema(description = "状态: PENDING/GENERATING/COMPLETED/FAILED")
    private String status;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "视频任务状态")
    private String videoStatus;

    @Schema(description = "配音任务状态")
    private String audioStatus;

    @Schema(description = "镜头引用的故事资产")
    private java.util.List<PovShotAssetRefRespVO> assetRefs;

    @Schema(description = "镜头发声角色")
    private java.util.List<PovShotSpeakerRespVO> speakers;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
