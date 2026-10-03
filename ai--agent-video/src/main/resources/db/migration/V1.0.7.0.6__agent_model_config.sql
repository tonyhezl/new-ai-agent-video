-- Agent 模型绑定配置表
-- 允许用户为每个 Agent 类型指定使用的 AI 模型，
-- 支持按任务复杂度分配合适的模型（纯推理/高级创作/中档调度/低成本调度）。
CREATE TABLE `afv_agent_model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_type` varchar(64) NOT NULL COMMENT 'Agent 类型标识（如 script_writer、storyboard_frame_gen）',
  `model_id` bigint NOT NULL COMMENT '关联的 AI 模型 ID（afv_ai_model.id）',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `deleted_id` bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除隔离标识',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_type_deleted_id` (`agent_type`, `deleted_id`),
  INDEX `idx_agent_model_config_model_id` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 模型绑定配置';
