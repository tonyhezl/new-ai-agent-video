package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

@TableName("afv_pov_shot_speaker")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovShotSpeaker extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storyId;
    private Long shotId;
    private Long assetId;
    private Integer characterNo;
    private String dialogueText;
    private String delivery;
    private Integer sortOrder;
}

