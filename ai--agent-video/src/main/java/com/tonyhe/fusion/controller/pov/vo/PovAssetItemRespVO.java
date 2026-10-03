package com.tonyhe.fusion.controller.pov.vo;

import lombok.Data;

@Data
public class PovAssetItemRespVO {
    private Long id;
    private Long assetId;
    private String itemKey;
    private String itemType;
    private Long basedOnItemId;
    private String stateDescription;
    private String imagePrompt;
    private String negativePrompt;
    private String imageUrl;
    private String status;
    private String errorMessage;
    private Integer sortOrder;
}

