package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

@TableName("afv_pov_shot_asset")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovShotAsset extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storyId;
    private Long shotId;
    private Long assetItemId;
    private Integer referenceOrder;
    private Boolean required;
}

