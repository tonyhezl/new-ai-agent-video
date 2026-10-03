ALTER TABLE `afv_storyboard_item`
  ADD COLUMN `first_frame_effective_prompt` TEXT NULL COMMENT '首帧图片任务实际提交的最终提示词' AFTER `first_frame_prompt`,
  ADD COLUMN `last_frame_effective_prompt` TEXT NULL COMMENT '尾帧图片任务实际提交的最终提示词' AFTER `last_frame_prompt`;
