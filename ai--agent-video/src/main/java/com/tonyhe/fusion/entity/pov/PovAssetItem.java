package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

@TableName("afv_pov_asset_item")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovAssetItem extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private String itemKey;
    private String itemType;
    private Long basedOnItemId;
    private String stateDescription;
    private String imagePrompt;
    private String negativePrompt;
    private String imageUrl;
    private String imageTaskId;
    private Long imageModelId;
    private String status;
    private String errorMessage;
    private Integer sortOrder;
}
