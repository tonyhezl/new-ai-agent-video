package com.tonyhe.fusion.controller.pov.vo;

import lombok.Data;

@Data
public class PovShotAssetRefRespVO {
    private Long assetId;
    private Long assetItemId;
    private String assetName;
    private String assetRole;
    private String itemKey;
    private String imageUrl;
    private Integer referenceOrder;
    private Boolean required;
}

