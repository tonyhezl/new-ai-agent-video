-- Agent 消息表增加 token 用量字段
-- 记录每次 LLM 推理调用的 token 消耗，用于 AFP 成本估算和可视化
ALTER TABLE `afv_agent_message`
  ADD COLUMN `input_tokens` int DEFAULT NULL COMMENT '输入 token 数（LLM 推理调用）' AFTER `reasoning_duration_ms`,
  ADD COLUMN `output_tokens` int DEFAULT NULL COMMENT '输出 token 数（LLM 推理调用）' AFTER `input_tokens`,
  ADD COLUMN `model_code` varchar(128) DEFAULT NULL COMMENT '本次推理使用的模型 code' AFTER `output_tokens`;
