-- 为 POV 分镜表添加媒体任务 ID 字段，用于异步轮询生成结果
ALTER TABLE `afv_pov_shot` ADD COLUMN `media_task_id` VARCHAR(64) NULL COMMENT '关联的生图/生视频/配音任务ID' AFTER `error_message`;
ALTER TABLE `afv_pov_shot` ADD COLUMN `media_task_type` VARCHAR(16) NULL COMMENT '媒体任务类型: IMAGE/VIDEO/AUDIO' AFTER `media_task_id`;
