package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.math.BigDecimal;

@TableName("afv_recharge_package")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargePackage extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal amountCny;

    private Integer creditAmount;

    @Builder.Default
    private Integer bonusCredit = 0;

    @Builder.Default
    private Integer sort = 0;

    @Builder.Default
    private Integer status = 1;

    @Builder.Default
    private Long deletedId = 0L;
}
