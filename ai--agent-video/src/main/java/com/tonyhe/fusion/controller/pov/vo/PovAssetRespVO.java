package com.tonyhe.fusion.controller.pov.vo;

import lombok.Data;

import java.util.List;

@Data
public class PovAssetRespVO {
    private Long id;
    private Long storyId;
    private String assetKey;
    private String assetRole;
    private String name;
    private String description;
    private Integer characterNo;
    private Boolean voiceRequired;
    private String voiceDescription;
    private String voiceSampleText;
    private String voiceConfig;
    private String referenceAudioUrl;
    private String voiceStatus;
    private String voiceErrorMessage;
    private Integer sortOrder;
    private List<PovAssetItemRespVO> items;
}

