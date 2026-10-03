-- P3: Add team_id snapshot column to async task tables for consumer authorization
ALTER TABLE `afv_image_task`
  ADD COLUMN `team_id` BIGINT NULL COMMENT '提交时的团队ID快照' AFTER `project_id`;

ALTER TABLE `afv_video_task`
  ADD COLUMN `team_id` BIGINT NULL COMMENT '提交时的团队ID快照' AFTER `project_id`;

ALTER TABLE `afv_audio_task`
  ADD COLUMN `team_id` BIGINT NULL COMMENT '提交时的团队ID快照' AFTER `project_id`;
