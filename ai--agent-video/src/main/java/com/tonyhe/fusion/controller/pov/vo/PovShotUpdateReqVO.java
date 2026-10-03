package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "POV 分镜更新请求")
@Data
public class PovShotUpdateReqVO {

    @Schema(description = "第一人称独白台词")
    private String voiceText;

    @Schema(description = "POV视觉生图提示词")
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

    @Schema(description = "图片URL")
    private String imageUrl;

    @Schema(description = "视频URL")
    private String videoUrl;

    @Schema(description = "音频URL")
    private String audioUrl;
}
