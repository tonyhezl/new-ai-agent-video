package com.tonyhe.fusion.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * Agent 模型绑定配置实体
 * <p>
 * 对应数据库表：afv_agent_model_config
 * 为每个 Agent 类型指定使用的 AI 模型，支持按任务复杂度分配合适的模型。
 */
@TableName("afv_agent_model_config")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentModelConfig extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Agent 类型标识（如 script_writer、storyboard_frame_gen） */
    private String agentType;

    /** 关联的 AI 模型 ID（afv_ai_model.id） */
    private Long modelId;

    /**
     * 逻辑删除隔离标识。
     * 0 表示未删除；逻辑删除后写入当前记录 ID，用于避免唯一索引被已删除数据占用。
     */
    @Builder.Default
    private Long deletedId = 0L;
}
