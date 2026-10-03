package com.tonyhe.fusion.controller.pov.vo;

import lombok.Data;

@Data
public class PovShotSpeakerRespVO {
    private Long assetId;
    private Integer characterNo;
    private String characterName;
    private String dialogueText;
    private String delivery;
    private String referenceAudioUrl;
    private Integer sortOrder;
}

