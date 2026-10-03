package com.tonyhe.fusion.entity.generation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.handler.JsonbTypeHandler;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * 生图任务实体
 * <p>
 * 对应数据库表：afv_image_task
 * 记录一次 AI 图片生成任务的完整信息，包括提示词、模型配置和生成参数。
 */
@TableName(value = "afv_image_task", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageTask extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识 */
    private String taskId;

    /** 发起用户ID */
    private Long userId;

    /** 关联项目ID */
    private Long projectId;

    /** 提交时的团队ID快照（用于异步消费者权限校验） */
    private Long teamId;

    /** 生图提示词 */
    private String prompt;

    /** 反向提示词，描述不希望出现的内容 */
    private String negativePrompt;

    /** 提示词模板ID */
    private Long promptTemplateId;

    /** 参考图片URL列表 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String refImageUrls;

    /** 画面比例，如 16:9、1:1 */
    private String ratio;

    /** 分辨率，如 1920x1080 */
    private String resolution;

    /** 宽高比描述 */
    private String aspectRatio;

    /** 图片宽度（像素） */
    private Integer width;

    /** 图片高度（像素） */
    private Integer height;

    /** 生成数量 */
    @Builder.Default
    private Integer count = 1;

    /** 已成功生成的数量 */
    @Builder.Default
    private Integer successCount = 0;

    /** 任务状态：0-排队中 1-处理中 2-已完成 3-失败 */
    @Builder.Default
    private Integer status = 0;

    /** 失败时的错误信息 */
    private String errorMsg;

    /** 使用的AI模型ID */
    private Long modelId;

    /** 使用的AI模型编码（用于统计不同模型的生图质量） */
    private String modelCode;

    /** 生成提示词的对话文本模型编码（用于统计不同文本模型的提示词质量） */
    private String textModelCode;

    /** 任务分类标签 */
    private String category;

    /** 拥有者类型：1-个人 2-团队 */
    private Integer ownerType;

    /** 拥有者ID */
    private Long ownerId;
}
