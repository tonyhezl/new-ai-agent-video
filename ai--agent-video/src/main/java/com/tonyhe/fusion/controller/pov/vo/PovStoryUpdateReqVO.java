package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "POV 故事基础信息更新请求")
@Data
public class PovStoryUpdateReqVO {

    @Schema(description = "故事标题")
    private String title;

    @Schema(description = "黄金钩子文案")
    private String hookText;

    @Schema(description = "完整第一人称独白脚本")
    private String fullScript;

    @Schema(description = "配音音色")
    private String voiceId;

    @Schema(description = "背景音效风格")
    private String bgmStyle;

    @Schema(description = "可见性: PRIVATE/PROJECT/TEAM")
    private String visibility;
}
