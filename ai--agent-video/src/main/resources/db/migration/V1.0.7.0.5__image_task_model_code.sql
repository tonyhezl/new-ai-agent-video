-- 生图任务表新增 model_code 字段，用于统计不同模型的生图质量
ALTER TABLE afv_image_task ADD COLUMN model_code VARCHAR(128) NULL COMMENT '使用的AI模型编码' AFTER model_id;

-- 生图任务表新增 text_model_code 字段，用于统计不同文本模型的提示词质量
ALTER TABLE afv_image_task ADD COLUMN text_model_code VARCHAR(128) NULL COMMENT '生成提示词的对话文本模型编码' AFTER model_code;

-- 生视频任务表新增 model_code 和 text_model_code 字段
ALTER TABLE afv_video_task ADD COLUMN model_code VARCHAR(128) NULL COMMENT '使用的AI模型编码' AFTER model_id;
ALTER TABLE afv_video_task ADD COLUMN text_model_code VARCHAR(128) NULL COMMENT '生成提示词的对话文本模型编码' AFTER model_code;
