CREATE TABLE IF NOT EXISTS `afv_pov_asset` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `story_id` BIGINT NOT NULL COMMENT '所属POV故事ID',
  `asset_key` VARCHAR(96) NOT NULL COMMENT '故事内稳定资产键',
  `asset_role` VARCHAR(32) NOT NULL COMMENT 'SCENE/KEY_PROP/POV_CHARACTER/NPC_ENTITY/VEHICLE_BUILDING/STYLE_BOARD',
  `name` VARCHAR(128) NOT NULL COMMENT '资产名称',
  `description` TEXT NULL COMMENT '跨镜头一致性描述',
  `character_no` INT NULL COMMENT '故事内稳定角色编号',
  `voice_required` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要角色参考音频',
  `voice_description` VARCHAR(512) NULL COMMENT '声音特征描述',
  `voice_sample_text` VARCHAR(512) NULL COMMENT '参考音频样本文本',
  `voice_config` JSON NULL COMMENT '本故事角色音色配置快照',
  `reference_audio_url` VARCHAR(1024) NULL COMMENT '角色参考音频URL',
  `reference_audio_task_id` VARCHAR(64) NULL COMMENT '参考音频任务ID',
  `voice_status` VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED/PENDING/GENERATING/READY/FAILED',
  `voice_error_message` VARCHAR(1024) NULL COMMENT '参考音频错误信息',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pov_asset_key` (`story_id`, `asset_key`),
  UNIQUE KEY `uk_pov_character_no` (`story_id`, `character_no`),
  KEY `idx_pov_asset_story` (`story_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POV故事独立资产';

CREATE TABLE IF NOT EXISTS `afv_pov_asset_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `asset_id` BIGINT NOT NULL COMMENT '所属POV资产ID',
  `item_key` VARCHAR(96) NOT NULL COMMENT '资产内稳定状态键',
  `item_type` VARCHAR(32) NOT NULL DEFAULT 'initial' COMMENT 'initial/variant',
  `based_on_item_id` BIGINT NULL COMMENT '变体依赖的主图子项ID',
  `state_description` TEXT NULL COMMENT '资产状态描述',
  `image_prompt` TEXT NOT NULL COMMENT '资产图片提示词',
  `negative_prompt` TEXT NULL COMMENT '资产图片负向提示词',
  `image_url` VARCHAR(1024) NULL COMMENT '资产图片URL',
  `image_task_id` VARCHAR(64) NULL COMMENT '生图任务ID',
  `image_model_id` BIGINT NULL COMMENT '生图模型ID，用于主图完成后自动生成变体',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/READY/FAILED',
  `error_message` VARCHAR(1024) NULL COMMENT '错误信息',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pov_asset_item_key` (`asset_id`, `item_key`),
  KEY `idx_pov_asset_item_asset` (`asset_id`),
  KEY `idx_pov_asset_item_task` (`image_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POV故事资产图片与状态变体';

CREATE TABLE IF NOT EXISTS `afv_pov_shot_asset` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `story_id` BIGINT NOT NULL COMMENT '所属POV故事ID',
  `shot_id` BIGINT NOT NULL COMMENT '所属POV镜头ID',
  `asset_item_id` BIGINT NOT NULL COMMENT '引用的POV资产子项ID',
  `reference_order` INT NOT NULL DEFAULT 0 COMMENT '期望参考顺序',
  `required` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否为必需参考',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pov_shot_asset` (`shot_id`, `asset_item_id`),
  KEY `idx_pov_shot_asset_order` (`shot_id`, `reference_order`),
  KEY `idx_pov_shot_asset_story` (`story_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POV镜头资产引用';

CREATE TABLE IF NOT EXISTS `afv_pov_shot_speaker` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `story_id` BIGINT NOT NULL COMMENT '所属POV故事ID',
  `shot_id` BIGINT NOT NULL COMMENT '所属POV镜头ID',
  `asset_id` BIGINT NOT NULL COMMENT '发声的POV角色资产ID',
  `character_no` INT NOT NULL COMMENT '故事内稳定角色编号',
  `dialogue_text` TEXT NOT NULL COMMENT '本镜头台词',
  `delivery` VARCHAR(255) NULL COMMENT '语气与表达方式',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '发声顺序',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pov_shot_speaker_order` (`shot_id`, `sort_order`),
  KEY `idx_pov_shot_speaker_story` (`story_id`),
  KEY `idx_pov_shot_speaker_asset` (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POV镜头发声角色';

ALTER TABLE `afv_pov_shot`
  ADD COLUMN `video_prompt` TEXT NULL COMMENT '视频业务提示词' AFTER `visual_prompt`,
  ADD COLUMN `camera_movement` VARCHAR(64) NULL COMMENT '运镜方式' AFTER `camera_view`,
  ADD COLUMN `video_task_id` VARCHAR(64) NULL COMMENT '生视频任务ID' AFTER `audio_url`,
  ADD COLUMN `audio_task_id` VARCHAR(64) NULL COMMENT '独立配音任务ID' AFTER `video_task_id`,
  ADD COLUMN `video_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/COMPLETED/FAILED/STALE' AFTER `audio_task_id`,
  ADD COLUMN `audio_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/COMPLETED/FAILED/NOT_REQUIRED' AFTER `video_status`;

CREATE INDEX `idx_pov_shot_video_task` ON `afv_pov_shot` (`video_task_id`);
CREATE INDEX `idx_pov_shot_audio_task` ON `afv_pov_shot` (`audio_task_id`);
