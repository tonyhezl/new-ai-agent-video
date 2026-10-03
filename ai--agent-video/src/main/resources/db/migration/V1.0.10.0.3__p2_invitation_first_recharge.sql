-- P2: 邀请奖励表
CREATE TABLE `afv_invitation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `inviter_id` bigint NOT NULL COMMENT '邀请人 user_id',
  `invitee_id` bigint NOT NULL COMMENT '被邀请人 user_id',
  `invitee_email` varchar(128) DEFAULT NULL COMMENT '被邀请人邮箱',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REWARDED',
  `reward_credit` int NOT NULL DEFAULT 30 COMMENT '奖励创作点数',
  `rewarded_at` datetime DEFAULT NULL COMMENT '发放奖励时间',
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitation_invitee` (`invitee_id`),
  KEY `idx_invitation_inviter` (`inviter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请奖励';

-- P2: 首充优惠记录表
CREATE TABLE `afv_first_recharge_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `order_id` bigint NOT NULL COMMENT '充值订单 ID',
  `bonus_credit` int NOT NULL DEFAULT 50 COMMENT '首充赠送创作点',
  `deleted` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_first_recharge_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首充优惠记录';

-- P2: 系统配置项
INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'invitation_reward_credit', '30', '邀请奖励创作点数（双方各得）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'invitation_reward_credit' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'first_recharge_bonus_credit', '50', '首充额外赠送创作点数', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'first_recharge_bonus_credit' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'cloud_moderation_enabled', 'false', '是否启用云审核', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'cloud_moderation_enabled' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'cloud_moderation_api_key', '', '云审核 API Key', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'cloud_moderation_api_key' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'cloud_moderation_endpoint', '', '云审核 API 地址', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'cloud_moderation_endpoint' AND deleted = 0);
