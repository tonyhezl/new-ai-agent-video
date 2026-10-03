-- P3: Add visibility column to afv_pov_story for access control
ALTER TABLE `afv_pov_story`
  ADD COLUMN `visibility` VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
  COMMENT '可见性: PRIVATE/PROJECT/TEAM，未绑定项目时默认仅创建者可见'
  AFTER `status`;
