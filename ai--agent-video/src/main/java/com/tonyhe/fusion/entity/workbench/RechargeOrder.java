package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("afv_recharge_order")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargeOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long packageId;

    private BigDecimal amountCny;

    private BigDecimal reallyPrice;

    private Integer creditAmount;

    @Builder.Default
    private String status = "PENDING";

    private String payChannel;

    @Builder.Default
    private String payProvider = "vmq";

    private String providerPayId;

    private String payerRemark;

    private LocalDateTime paidAt;

    private LocalDateTime expireAt;

    @Builder.Default
    private Long deletedId = 0L;
}
