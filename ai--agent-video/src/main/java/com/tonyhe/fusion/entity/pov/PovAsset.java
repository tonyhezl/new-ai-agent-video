package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import com.tonyhe.fusion.common.handler.JsonbTypeHandler;
import lombok.*;

@TableName(value = "afv_pov_asset", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovAsset extends BaseEntity {
    @TableId(type = IdType.AUTO)
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
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String voiceConfig;
    private String referenceAudioUrl;
    private String referenceAudioTaskId;
    private String voiceStatus;
    private String voiceErrorMessage;
    private Integer sortOrder;
}

