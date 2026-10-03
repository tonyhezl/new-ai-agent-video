-- ============================================
-- 生图工作台后端与商业化 P0
-- 1. ALTER sys_user 加额度字段
-- 2. ALTER afv_ai_model 加 is_free_model
-- 3. 新建独立任务/结果表
-- 4. 新建游客表
-- 5. 新建模型价格表
-- 6. 新建充值套餐/订单/流水表
-- 7. 新建邮箱验证码表
-- 8. 新建审核词库/命中日志表
-- ============================================

-- 1. sys_user 增量
ALTER TABLE `sys_user`
  ADD COLUMN `credit_balance` INT NOT NULL DEFAULT 0 COMMENT '创作点余额' AFTER `status`,
  ADD COLUMN `credit_version` INT NOT NULL DEFAULT 0 COMMENT '余额乐观锁版本' AFTER `credit_balance`,
  ADD COLUMN `free_quota_used` INT NOT NULL DEFAULT 0 COMMENT '已用免费次数' AFTER `credit_version`,
  ADD COLUMN `free_quota_total` INT NOT NULL DEFAULT 2 COMMENT '总免费次数' AFTER `free_quota_used`,
  ADD COLUMN `guest_id` VARCHAR(64) NULL COMMENT '注册前游客 ID，用于额度去重' AFTER `free_quota_total`;
ALTER TABLE `sys_user` ADD UNIQUE INDEX `uk_sys_user_guest_id` (`guest_id`);

-- 2. afv_ai_model 增量
ALTER TABLE `afv_ai_model`
  ADD COLUMN `is_free_model` TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许免费额度使用' AFTER `default_model`;

-- 3. 生图工作台任务（独立表）
CREATE TABLE `afv_workbench_image_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(64) NOT NULL COMMENT '业务单号，对外展示',
  `user_id` bigint DEFAULT NULL COMMENT '登录用户 ID',
  `guest_id` varchar(64) DEFAULT NULL COMMENT '游客 ID',
  `model_id` bigint NOT NULL COMMENT 'afv_ai_model.id',
  `price_id` bigint NOT NULL COMMENT 'afv_image_model_price.id',
  `quality_level` varchar(32) NOT NULL DEFAULT 'standard' COMMENT 'standard/high/ultra',
  `aspect_ratio` varchar(16) NOT NULL DEFAULT '1:1',
  `credit_cost` int NOT NULL DEFAULT 0 COMMENT '本次应付创作点',
  `is_free_used` tinyint NOT NULL DEFAULT 0 COMMENT '是否消耗免费额度',
  `billed` tinyint NOT NULL DEFAULT 0 COMMENT '是否已实扣，失败回滚后 0',
  `prompt` text COMMENT '提示词原文',
  `negative_prompt` varchar(1024) DEFAULT NULL,
  `status` int NOT NULL DEFAULT 0 COMMENT '0-排队 1-处理中 2-完成 3-失败 4-审核拦截',
  `error_msg` varchar(512) DEFAULT NULL,
  `source` varchar(32) NOT NULL DEFAULT 'workbench' COMMENT 'workbench/api',
  `client_ip` varchar(64) DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workbench_task_no` (`task_no`, `deleted_id`),
  KEY `idx_workbench_task_user` (`user_id`, `status`, `create_time`),
  KEY `idx_workbench_task_guest` (`guest_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生图工作台任务';

CREATE TABLE `afv_workbench_image_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT 'afv_workbench_image_task.id',
  `image_url` varchar(512) NOT NULL COMMENT '结果图 URL',
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `size_bytes` int DEFAULT NULL,
  `seed` varchar(64) DEFAULT NULL,
  `is_main` tinyint NOT NULL DEFAULT 1 COMMENT '主图，批量出图时标识',
  `sort` int NOT NULL DEFAULT 0,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_workbench_result_task` (`task_id`, `is_main`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生图工作台结果图';

-- 4. 游客表
CREATE TABLE `afv_guest` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `guest_id` varchar(64) NOT NULL COMMENT '前端 localStorage UUID',
  `fingerprint` varchar(128) DEFAULT NULL,
  `ip` varchar(64) DEFAULT NULL,
  `free_quota_used` int NOT NULL DEFAULT 0,
  `free_quota_total` int NOT NULL DEFAULT 2,
  `bound_user_id` bigint DEFAULT NULL COMMENT '注册后绑定的 sys_user.id',
  `last_seen_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_guest_id` (`guest_id`, `deleted_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='游客额度';

-- 5. 模型价格表
CREATE TABLE `afv_image_model_price` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_id` bigint NOT NULL COMMENT 'afv_ai_model.id',
  `quality_level` varchar(32) NOT NULL DEFAULT 'standard',
  `aspect_ratio` varchar(16) NOT NULL DEFAULT '1:1',
  `credit_cost` int NOT NULL COMMENT '单次消耗创作点',
  `upstream_cost_cny` decimal(10,4) DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT 0,
  `status` int NOT NULL DEFAULT 1,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_quality_ratio` (`model_id`, `quality_level`, `aspect_ratio`, `deleted_id`),
  KEY `idx_price_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生图模型价格';

-- 6. 充值套餐
CREATE TABLE `afv_recharge_package` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `amount_cny` decimal(10,2) NOT NULL,
  `credit_amount` int NOT NULL,
  `bonus_credit` int NOT NULL DEFAULT 0,
  `sort` int NOT NULL DEFAULT 0,
  `status` int NOT NULL DEFAULT 1,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='充值套餐';

-- 充值订单
CREATE TABLE `afv_recharge_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL COMMENT '必须登录',
  `package_id` bigint DEFAULT NULL,
  `amount_cny` decimal(10,2) NOT NULL COMMENT '套餐金额',
  `really_price` decimal(10,2) DEFAULT NULL COMMENT '实付金额（并发时可能 -0.01）',
  `credit_amount` int NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/WAIT_PAY/PAID/CLOSED',
  `pay_channel` varchar(32) NOT NULL COMMENT 'wechat/alipay',
  `pay_provider` varchar(32) NOT NULL DEFAULT 'vmq' COMMENT 'vmq 或其他免签方案',
  `provider_pay_id` varchar(128) DEFAULT NULL COMMENT 'V免签 payId',
  `payer_remark` varchar(64) DEFAULT NULL,
  `paid_at` datetime DEFAULT NULL,
  `expire_at` datetime DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_order_no` (`order_no`, `deleted_id`),
  KEY `idx_recharge_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='充值订单';

-- 创作点流水
CREATE TABLE `afv_credit_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `type` varchar(32) NOT NULL COMMENT 'RECHARGE/FREE/GENERATE/REFUND',
  `amount` int NOT NULL,
  `balance_after` int NOT NULL,
  `related_id` bigint DEFAULT NULL,
  `related_type` varchar(32) DEFAULT NULL COMMENT 'RECHARGE_ORDER/WORKBENCH_TASK',
  `remark` varchar(256) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_credit_tx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='创作点流水';

-- 7. 邮箱验证码
CREATE TABLE `afv_email_code` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(128) NOT NULL,
  `purpose` varchar(32) NOT NULL COMMENT 'REGISTER/RESET_PASSWORD/CHANGE_EMAIL',
  `code` varchar(8) NOT NULL,
  `used` tinyint NOT NULL DEFAULT 0,
  `expire_at` datetime NOT NULL,
  `client_ip` varchar(64) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_email_code_email_purpose` (`email`, `purpose`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮箱验证码';

-- 8. 审核词库
CREATE TABLE `afv_moderation_word` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `word` varchar(64) NOT NULL COMMENT '原始词，入库前 trim + 小写',
  `normalized` varchar(64) NOT NULL COMMENT '归一化后用于匹配的词',
  `category` varchar(32) NOT NULL COMMENT 'politics/porn/minor/violence/illegal/other',
  `match_mode` varchar(16) NOT NULL DEFAULT 'contains' COMMENT 'exact=整词 contains=子串',
  `severity` tinyint NOT NULL DEFAULT 2 COMMENT '1-提示 2-拦截 3-严重拦截',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
  `remark` varchar(256) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `deleted_id` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_moderation_normalized` (`normalized`, `deleted_id`),
  KEY `idx_moderation_enabled_cat` (`enabled`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提示词审核词库';

-- 审核命中日志
CREATE TABLE `afv_moderation_hit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `guest_id` varchar(64) DEFAULT NULL,
  `prompt_hash` char(64) NOT NULL COMMENT 'SHA-256(归一化后全文)，不存原文',
  `prompt_length` int NOT NULL,
  `hit_word_id` bigint DEFAULT NULL COMMENT 'afv_moderation_word.id',
  `hit_category` varchar(32) NOT NULL,
  `hit_normalized` varchar(64) NOT NULL COMMENT '命中的归一化词，便于统计',
  `ip` varchar(64) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_moderation_hit_time` (`create_time`),
  KEY `idx_moderation_hit_guest` (`guest_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审核命中日志（不含原文）';

-- 种子数据：充值套餐
INSERT INTO `afv_recharge_package` (`name`, `amount_cny`, `credit_amount`, `bonus_credit`, `sort`, `status`) VALUES
  ('入门', 10.00, 100, 0, 1, 1),
  ('进阶', 30.00, 350, 50, 2, 1),
  ('专业', 50.00, 600, 120, 3, 1),
  ('大师', 100.00, 1300, 300, 4, 1);

-- 种子数据：审核词库测试词（无敏感含义，方便单测）
INSERT INTO `afv_moderation_word` (`word`, `normalized`, `category`, `match_mode`, `severity`, `enabled`, `remark`) VALUES
  ('testblockword', 'testblockword', 'other', 'exact', 2, 1, '测试用拦截词'),
  ('testcontains', 'testcontains', 'other', 'contains', 2, 1, '测试用子串拦截词');
