package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

@TableName("afv_moderation_hit_log")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationHitLog extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String guestId;

    private String promptHash;

    private Integer promptLength;

    private Long hitWordId;

    private String hitCategory;

    private String hitNormalized;

    private String ip;
}
