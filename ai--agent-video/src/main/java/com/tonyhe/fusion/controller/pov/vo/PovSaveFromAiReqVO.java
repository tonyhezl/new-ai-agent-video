package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "从 AI 输出内容保存故事请求")
@Data
public class PovSaveFromAiReqVO {

    @Schema(description = "AI 生成的完整文本内容 (包含 JSON)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "AI 内容不能为空")
    private String aiOutputText;

    @Schema(description = "关联项目ID(可选)")
    private Long projectId;

    @Schema(description = "配音音色（DashScope Qwen-Audio-TTS 音色）", example = "longanlingxin")
    private String voiceId;

    @Schema(description = "背景音效风格", example = "SUSPENSE_HEARTBEAT")
    private String bgmStyle;

    @Schema(description = "可见性: PRIVATE/PROJECT/TEAM", example = "PRIVATE")
    private String visibility = "PRIVATE";
}
