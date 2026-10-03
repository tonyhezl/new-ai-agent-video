package com.tonyhe.fusion.entity.generation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * AI 配音任务实体
 * <p>
 * 对应数据库表：afv_audio_task
 * 每条记录对应一次 TTS 合成；既可来自分镜对白，也可来自独立模块的参考音频。
 */
@TableName(value = "afv_audio_task", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioTask extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long projectId;

    /** 提交时的团队ID快照（用于异步消费者权限校验） */
    private Long teamId;

    private Long userId;

    private Long storyboardId;

    private Long storyboardEpisodeId;

    private Long storyboardItemId;

    @Builder.Default
    private Integer seq = 0;

    private String text;

    private String characterName;

    private Long characterAssetId;

    private String voiceConfig;

    private String dialogueType;

    private Long modelId;

    private String modelCode;

    private String audioUrl;

    private Integer durationMs;

    private Integer sampleRate;

    @Builder.Default
    private Integer status = 0;

    private String errorMessage;

    @Builder.Default
    private Integer retryCount = 0;

    private String parentTaskId;
}
