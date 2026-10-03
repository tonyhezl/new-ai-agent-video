-- 修复 wan2.7-r2v 能力规则：
-- 1. supportFirstFrame 改为 true（r2v 支持首帧+参考图同时传）
-- 2. 新增 supportFirstFrameWithReference=true，避免 trimVideoTaskInputs 在有首帧时清空参考图
UPDATE `afv_capability_rule`
SET `capability_config` = '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":true,"supportReferenceAudios":true,"supportFirstFrameWithReference":true,"minImageInputs":0,"maxImageInputs":10,"maxReferenceImages":10,"maxReferenceVideos":3,"maxReferenceAudios":3}'
WHERE `model_type` = 3
  AND `platform` = 'dashscope'
  AND `code_contains` = 'wan2.7-r2v'
  AND `status` = 1;
