package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * 第一人称猎奇故事实体
 * <p>
 * 对应数据库表：afv_pov_story
 */
@TableName(value = "afv_pov_story", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovStory extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 关联项目ID（可选） */
    private Long projectId;

    /** 故事标题 */
    private String title;

    /** 黄金钩子文案 */
    private String hookText;

    /** 题材类型: URBAN_MYSTERY / DEEP_EXPLORATION / SECRET_REVEAL / TECH_PARANOIA */
    private String topicGenre;

    /** 完整第一人称独白脚本 */
    private String fullScript;

    /** 预估时长(秒) */
    private Integer totalDurationEstimate;

    /** 配音音色 */
    private String voiceId;

    /** 背景氛围音效类型 */
    private String bgmStyle;

    /** 状态: DRAFT / GENERATING / COMPLETED / FAILED */
    private String status;

    /** 可见性: PRIVATE / PROJECT / TEAM，未绑定项目时默认仅创建者可见 */
    @Builder.Default
    private String visibility = "PRIVATE";

    /** 最终合成视频URL */
    private String finalVideoUrl;

    /** 错误信息 */
    private String errorMessage;
}
