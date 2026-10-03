package com.tonyhe.fusion.entity.pov;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.math.BigDecimal;

/**
 * 第一人称 POV 分镜明细实体
 * <p>
 * 对应数据库表：afv_pov_shot
 */
@TableName(value = "afv_pov_shot", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PovShot extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属故事ID */
    private Long storyId;

    /** 镜头序号(1..N) */
    private Integer shotOrder;

    /** 叙事阶段: HOOK / EXPLORE / TWIST / CLIMAX */
    private String narrativeStage;

    /** 第一人称独白台词 */
    private String voiceText;

    /** V1 兼容字段：旧视觉提示词 */
    private String visualPrompt;

    /** 视频业务提示词 */
    private String videoPrompt;

    /** 负向提示词 */
    private String negativePrompt;

    /** 机位视角: POV_HANDHELD / POV_BODYCAM / POV_NIGHT_VISION / POV_MACRO */
    private String cameraView;

    /** 运镜方式 */
    private String cameraMovement;

    /** 音效触发点 */
    private String sfxCue;

    /** 镜头时长(秒) */
    private BigDecimal durationSec;

    /** 生图产物URL */
    private String imageUrl;

    /** 生视频产物URL */
    private String videoUrl;

    /** 独白音频URL */
    private String audioUrl;

    /** 生视频任务ID */
    private String videoTaskId;

    /** 独立配音任务ID */
    private String audioTaskId;

    /** 视频状态: PENDING / GENERATING / COMPLETED / FAILED / STALE */
    private String videoStatus;

    /** 配音状态: PENDING / GENERATING / COMPLETED / FAILED / NOT_REQUIRED */
    private String audioStatus;

    /** 状态: PENDING / GENERATING / COMPLETED / FAILED */
    private String status;

    /** 错误信息 */
    private String errorMessage;

    /** 关联的生图/生视频/配音任务ID */
    private String mediaTaskId;

    /** 媒体任务类型: IMAGE/VIDEO/AUDIO */
    private String mediaTaskType;
}
