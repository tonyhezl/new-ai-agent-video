package com.tonyhe.fusion.controller.pov.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "第一人称猎奇故事生成请求")
@Data
public class PovStoryGenerateReqVO {

    @Schema(description = "故事灵感/主题(可选，不填则由AI全自动构思)", example = "在老宅暗格里发现一本带锁日记")
    private String topic;

    @Schema(description = "题材类型: URBAN_MYSTERY/DEEP_EXPLORATION/HAUNTED_HOUSE_TESTER/OCCUPATIONAL_TABOO/TECH_PARANOIA/UNEXPLORED_ZONE/ANOMALOUS_SPACE/FOLKLORE_SECRETS", example = "URBAN_MYSTERY")
    private String topicGenre = "URBAN_MYSTERY";

    @Schema(description = "配音音色（DashScope Qwen-Audio-TTS 音色）", example = "longanlingxin")
    private String voiceId = "longanlingxin";

    @Schema(description = "背景音效风格: SUSPENSE_HEARTBEAT(悬疑心跳)/RAINY_NIGHT(雨夜白噪音)/DARK_AMBIENT(暗黑低频)", example = "SUSPENSE_HEARTBEAT")
    private String bgmStyle = "SUSPENSE_HEARTBEAT";

    @Schema(description = "指定大模型ID(可选)")
    private Long modelId;

    @Schema(description = "关联项目ID(可选)")
    private Long projectId;
}
