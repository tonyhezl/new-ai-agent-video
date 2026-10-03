ALTER TABLE `afv_storyboard_scene`
    ADD COLUMN `shot_strategy` JSON NULL COMMENT '分镜拆分策略：primaryType + tags' AFTER `initial_scene_state`,
    ADD COLUMN `beats` JSON NULL COMMENT '场次叙事节拍定义' AFTER `shot_strategy`;

ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `beat_ids` JSON NULL COMMENT '镜头覆盖的剧本节拍ID列表' AFTER `shot_number`;
