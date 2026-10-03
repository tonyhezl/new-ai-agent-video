package com.tonyhe.fusion.controller.pov.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PovAssetVoiceUpdateReqVO {
    @NotBlank
    private String voiceId;
    private String voiceName;
    private Double speed;
    private Double pitch;
    private String emotion;
    private String customVoiceId;
    private String sampleText;
    private String referenceAudioUrl;
}
