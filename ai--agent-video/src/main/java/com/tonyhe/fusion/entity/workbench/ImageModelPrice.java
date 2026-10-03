package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.math.BigDecimal;

@TableName("afv_image_model_price")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageModelPrice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long modelId;

    @Builder.Default
    private String qualityLevel = "standard";

    @Builder.Default
    private String aspectRatio = "1:1";

    private Integer creditCost;

    private BigDecimal upstreamCostCny;

    @Builder.Default
    private Integer isDefault = 0;

    @Builder.Default
    private Integer status = 1;

    @Builder.Default
    private Long deletedId = 0L;
}
