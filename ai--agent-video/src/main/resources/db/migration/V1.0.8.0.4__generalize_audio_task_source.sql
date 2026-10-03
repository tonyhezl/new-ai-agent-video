-- Audio tasks are shared by storyboard dialogue and independent POV reference audio.
-- POV stories do not have storyboard lineage, and their semantic task type is longer than
-- the original numeric dialogue type values.
ALTER TABLE `afv_audio_task`
  MODIFY COLUMN `project_id` BIGINT NULL COMMENT '关联项目ID（独立模块可为空）',
  MODIFY COLUMN `storyboard_id` BIGINT NULL COMMENT '分镜ID（非分镜来源可为空）',
  MODIFY COLUMN `storyboard_episode_id` BIGINT NULL COMMENT '分镜集ID（非分镜来源可为空）',
  MODIFY COLUMN `storyboard_item_id` BIGINT NULL COMMENT '关联StoryboardItem.id（非分镜来源可为空）',
  MODIFY COLUMN `dialogue_type` VARCHAR(32) NULL COMMENT '音频内容/来源类型，如1、3、5、POV_REFERENCE';
