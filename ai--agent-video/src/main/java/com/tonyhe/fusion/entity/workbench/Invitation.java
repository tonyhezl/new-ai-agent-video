package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

@TableName("afv_invitation")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invitation extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inviterId;

    private Long inviteeId;

    private String inviteeEmail;

    @Builder.Default
    private String status = "PENDING";

    @Builder.Default
    private Integer rewardCredit = 30;

    private LocalDateTime rewardedAt;
}
