package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

@TableName("afv_moderation_word")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationWord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String word;

    private String normalized;

    private String category;

    @Builder.Default
    private String matchMode = "contains";

    @Builder.Default
    private Integer severity = 2;

    @Builder.Default
    private Integer enabled = 1;

    private String remark;

    @Builder.Default
    private Long deletedId = 0L;
}
