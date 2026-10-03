-- 能力推断规则表
-- 将原本硬编码在 Java if-else 中的模型能力推断逻辑迁移到数据库，
-- 支持通过后台管理页面查看、新增、修改、删除。
CREATE TABLE `afv_capability_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(128) NOT NULL COMMENT '规则名称（描述）',
  `model_type` int NOT NULL COMMENT '模型类型：2-图片 3-视频',
  `platform` varchar(64) DEFAULT NULL COMMENT '匹配平台（normalizedPlatform），NULL=不限',
  `code_contains` varchar(128) DEFAULT NULL COMMENT '模型 code 包含关键词（substring），NULL=不限',
  `code_starts_with` varchar(128) DEFAULT NULL COMMENT '模型 code 前缀匹配，NULL=不限',
  `family` varchar(64) DEFAULT NULL COMMENT '匹配 modelFamily，NULL=不限',
  `protocol` varchar(64) DEFAULT NULL COMMENT '匹配 modelProtocol，NULL=不限',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序（越小越优先匹配）',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `capability_config` text NOT NULL COMMENT '能力配置 JSON',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `deleted_id` bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除隔离标识',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  INDEX `idx_cap_rule_type_sort` (`model_type`, `sort_order`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型能力推断规则';

-- ========== 种子数据：视频规则 (model_type=3) ==========

INSERT INTO `afv_capability_rule` (`name`, `model_type`, `platform`, `code_contains`, `code_starts_with`, `family`, `protocol`, `sort_order`, `status`, `capability_config`) VALUES
('GoogleFlow R2V', 3, 'googleflowreverseapi', 'r2v', NULL, NULL, NULL, 10, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":3,"maxReferenceImages":3,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('GoogleFlow 插值', 3, 'googleflowreverseapi', 'interpolation', NULL, NULL, NULL, 11, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":2,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('GoogleFlow I2V', 3, 'googleflowreverseapi', 'i2v', NULL, NULL, NULL, 12, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":1,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('GoogleFlow 视频兜底', 3, 'googleflowreverseapi', NULL, NULL, NULL, NULL, 19, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":0,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('Volcengine Seedance 2.0', 3, 'volcengine', 'seedance-2-0', NULL, NULL, NULL, 20, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":true,"supportReferenceVideos":true,"supportReferenceAudios":true,"minImageInputs":0,"maxImageInputs":null,"maxReferenceImages":9,"maxReferenceVideos":3,"maxReferenceAudios":3}'),
('Volcengine 1.0 lite t2v', 3, 'volcengine', '1-0-lite-t2v', NULL, NULL, NULL, 21, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":0,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('Volcengine 1.0 lite i2v', 3, 'volcengine', '1-0-lite-i2v', NULL, NULL, NULL, 22, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":null,"maxReferenceImages":9,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('Volcengine 1.0 pro fast', 3, 'volcengine', '1-0-pro-fast', NULL, NULL, NULL, 23, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":1,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('Volcengine 1.5/1.0 pro', 3, 'volcengine', '1-5-pro', NULL, NULL, NULL, 24, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('Volcengine 1.0 pro', 3, 'volcengine', '1-0-pro', NULL, NULL, NULL, 25, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('DashScope wan2.7-r2v', 3, 'dashscope', 'wan2.7-r2v', NULL, NULL, NULL, 30, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":true,"supportReferenceAudios":true,"minImageInputs":0,"maxImageInputs":10,"maxReferenceImages":10,"maxReferenceVideos":3,"maxReferenceAudios":3}'),
('DashScope wan2.7-videoedit', 3, 'dashscope', 'wan2.7-videoedit', NULL, NULL, NULL, 31, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":true,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":3,"maxReferenceImages":3,"maxReferenceVideos":1,"maxReferenceAudios":0}'),
('DashScope wan2.7-i2v', 3, 'dashscope', 'wan2.7-i2v', NULL, NULL, NULL, 32, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":true,"supportReferenceAudios":true,"minImageInputs":1,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":1,"maxReferenceAudios":1}'),
('DashScope kf2v', 3, 'dashscope', 'kf2v', NULL, NULL, NULL, 33, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":2,"maxImageInputs":2,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('DashScope i2v 通用', 3, 'dashscope', 'i2v', NULL, NULL, NULL, 34, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":true,"minImageInputs":1,"maxImageInputs":1,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":1}'),
('DashScope t2v 通用', 3, 'dashscope', 't2v', NULL, NULL, NULL, 35, 1, '{"supportFirstFrame":false,"supportLastFrame":false,"supportReferenceImages":false,"supportReferenceVideos":false,"supportReferenceAudios":true,"minImageInputs":0,"maxImageInputs":0,"maxReferenceImages":0,"maxReferenceVideos":0,"maxReferenceAudios":1}'),
('NewAPI 视频默认', 3, 'newapi', NULL, NULL, NULL, NULL, 40, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":1,"maxReferenceImages":1,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('OpenAI-compat Agnes 视频', 3, 'openai_compatible', 'agnes', NULL, 'agnes', 'agnes', 50, 1, '{"supportFirstFrame":true,"supportLastFrame":true,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":null,"maxReferenceImages":null,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('OpenAI-compat Grok 视频', 3, 'openai_compatible', NULL, 'grok-imagine-video', 'grok_video', 'grok_video', 51, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":1,"maxReferenceImages":1,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('OpenAI-compat Sora 视频', 3, 'openai_compatible', NULL, 'sora', 'sora', 'sora', 52, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":1,"maxReferenceImages":1,"maxReferenceVideos":0,"maxReferenceAudios":0}'),
('OpenAI-compat 即梦 video-ds', 3, 'openai_compatible', 'video-ds', NULL, 'jimeng', 'jimeng', 53, 1, '{"supportFirstFrame":true,"supportLastFrame":false,"supportReferenceImages":true,"supportReferenceVideos":false,"supportReferenceAudios":false,"minImageInputs":0,"maxImageInputs":1,"maxReferenceImages":1,"maxReferenceVideos":0,"maxReferenceAudios":0}');

-- ========== 种子数据：图片规则 (model_type=2) ==========

INSERT INTO `afv_capability_rule` (`name`, `model_type`, `platform`, `code_contains`, `code_starts_with`, `family`, `protocol`, `sort_order`, `status`, `capability_config`) VALUES
('GoogleFlow 图片', 2, 'googleflowreverseapi', NULL, NULL, NULL, NULL, 10, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":null}'),
('DashScope wan2.7/2.6 图片', 2, 'dashscope', NULL, 'wan2.7-image', NULL, NULL, 20, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":10}'),
('DashScope wan2.6 图片', 2, 'dashscope', NULL, 'wan2.6-image', NULL, NULL, 21, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":10}'),
('DashScope qwen-image-2.0', 2, 'dashscope', NULL, 'qwen-image-2.0', NULL, NULL, 22, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":2}'),
('DashScope 图片兜底', 2, 'dashscope', NULL, NULL, NULL, NULL, 29, 1, '{"supportReferenceImages":false,"minReferenceImages":0,"maxReferenceImages":0}'),
('OpenAI-compat Grok/GPT/Flux 图片', 2, 'openai_compatible', 'flux', NULL, NULL, NULL, 30, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":16}'),
('OpenAI-compat GPT Image', 2, 'openai_compatible', NULL, 'gpt-image', NULL, NULL, 31, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":16}'),
('OpenAI-compat Grok 图片', 2, 'openai_compatible', NULL, 'grok-imagine-image', NULL, NULL, 32, 1, '{"supportReferenceImages":true,"minReferenceImages":0,"maxReferenceImages":16}'),
('OpenAI-compat 图片兜底', 2, 'openai_compatible', NULL, NULL, NULL, NULL, 39, 1, '{"supportReferenceImages":false,"minReferenceImages":0,"maxReferenceImages":0}'),
('NewAPI/Vertex 图片兜底', 2, 'newapi', NULL, NULL, NULL, NULL, 40, 1, '{"supportReferenceImages":false,"minReferenceImages":0,"maxReferenceImages":0}'),
('Vertex AI 图片兜底', 2, 'vertex_ai', NULL, NULL, NULL, NULL, 41, 1, '{"supportReferenceImages":false,"minReferenceImages":0,"maxReferenceImages":0}');
