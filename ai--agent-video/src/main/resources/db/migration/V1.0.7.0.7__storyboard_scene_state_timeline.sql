ALTER TABLE `afv_storyboard_scene`
    ADD COLUMN `base_scene_asset_item_id` BIGINT NULL COMMENT '场次默认场景子资产ID（镜头可覆盖）' AFTER `int_ext`,
    ADD COLUMN `layout_definition` JSON NULL COMMENT '固定空间布局与锚点定义' AFTER `base_scene_asset_item_id`,
    ADD COLUMN `initial_scene_state` JSON NULL COMMENT '场次开始时的可移动对象状态' AFTER `layout_definition`,
    ADD COLUMN `state_timeline_version` INT NOT NULL DEFAULT 0 COMMENT '场景状态时间线版本' AFTER `initial_scene_state`,
    ADD INDEX `idx_storyboard_scene_base_asset_item` (`base_scene_asset_item_id`);

ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `scene_state_before` JSON NULL COMMENT '镜头开始前的物化场景状态' AFTER `prop_ids`,
    ADD COLUMN `scene_state_delta` JSON NULL COMMENT '镜头内发生的场景状态变化' AFTER `scene_state_before`,
    ADD COLUMN `scene_state_after` JSON NULL COMMENT '镜头结束后的物化场景状态' AFTER `scene_state_delta`,
    ADD COLUMN `state_before_hash` VARCHAR(64) NULL COMMENT '镜头开始状态哈希' AFTER `scene_state_after`,
    ADD COLUMN `state_after_hash` VARCHAR(64) NULL COMMENT '镜头结束状态哈希' AFTER `state_before_hash`,
    ADD COLUMN `scene_state_hash` VARCHAR(64) NULL COMMENT '镜头完整状态版本哈希' AFTER `state_after_hash`,
    ADD COLUMN `state_timeline_version` INT NOT NULL DEFAULT 0 COMMENT '场景状态时间线版本' AFTER `scene_state_hash`,
    ADD COLUMN `continuity_mode` VARCHAR(16) NOT NULL DEFAULT 'CUT' COMMENT '镜头衔接模式：CUT/SEAMLESS' AFTER `state_timeline_version`,
    ADD COLUMN `first_frame_state_hash` VARCHAR(64) NULL COMMENT '首帧生成时使用的开始状态哈希' AFTER `continuity_mode`,
    ADD COLUMN `last_frame_state_hash` VARCHAR(64) NULL COMMENT '尾帧生成时使用的结束状态哈希' AFTER `first_frame_state_hash`,
    ADD COLUMN `generated_video_state_hash` VARCHAR(64) NULL COMMENT '视频生成时使用的完整状态哈希' AFTER `last_frame_state_hash`,
    ADD INDEX `idx_storyboard_item_scene_state_version` (`storyboard_scene_id`, `state_timeline_version`);
