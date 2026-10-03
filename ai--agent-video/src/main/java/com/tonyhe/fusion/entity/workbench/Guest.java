package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

@TableName("afv_guest")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Guest extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String guestId;

    private String fingerprint;

    private String ip;

    @Builder.Default
    private Integer freeQuotaUsed = 0;

    @Builder.Default
    private Integer freeQuotaTotal = 2;

    private Long boundUserId;

    private LocalDateTime lastSeenAt;

    @Builder.Default
    private Long deletedId = 0L;
}
