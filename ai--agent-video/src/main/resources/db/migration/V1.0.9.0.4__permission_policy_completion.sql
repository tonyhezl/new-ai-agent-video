-- Complete the permission catalogue and seed the legacy user role.
-- Existing admin accounts keep wildcard permission in the authorization service.

INSERT INTO `sys_menu`
(`id`, `parent_id`, `type`, `name`, `title`, `path`, `permission_code`, `icon`, `sort`, `visible`, `status`) VALUES
(21,  2, 'BUTTON', 'project-member-manage', '管理项目成员', NULL, 'project:member:manage', 'Users', 5, 0, 1),
(22, 13, 'BUTTON', 'team-member-manage', '管理团队成员', NULL, 'team:member:manage', 'Users', 2, 0, 1),
(23,  7, 'BUTTON', 'script-edit', '编辑剧本', NULL, 'script:edit', 'Edit', 2, 0, 1),
(24,  8, 'BUTTON', 'storyboard-edit', '编辑分镜', NULL, 'storyboard:edit', 'Edit', 2, 0, 1),
(25,  8, 'BUTTON', 'storyboard-generate', '生成分镜', NULL, 'storyboard:generate', 'Sparkles', 3, 0, 1),
(26,  9, 'BUTTON', 'asset-edit', '编辑资产', NULL, 'asset:edit', 'Edit', 2, 0, 1),
(27,  9, 'BUTTON', 'asset-delete', '删除资产', NULL, 'asset:delete', 'Delete', 3, 0, 1),
(28, 10, 'BUTTON', 'generation-create', '创建生成任务', NULL, 'generation:create', 'Plus', 2, 0, 1),
(29, 10, 'BUTTON', 'generation-cancel', '取消生成任务', NULL, 'generation:cancel', 'Close', 3, 0, 1),
(30, 11, 'BUTTON', 'media-delete', '删除媒体', NULL, 'media:delete', 'Delete', 2, 0, 1),
(31, 12, 'BUTTON', 'pov-story-edit', '编辑POV故事', NULL, 'pov:story:edit', 'Edit', 2, 0, 1),
(32, 12, 'BUTTON', 'pov-voice-generate', '生成角色声音', NULL, 'pov:voice:generate', 'Microphone', 3, 0, 1),
(33, 14, 'BUTTON', 'system-menu-manage', '菜单管理', NULL, 'system:menu:manage', 'Menu', 7, 0, 1),
(34, 14, 'BUTTON', 'system-role-manage', '角色管理权限', NULL, 'system:role:manage', 'Shield', 8, 0, 1),
(35, 13, 'BUTTON', 'team-create', '创建团队', NULL, 'team:create', 'Plus', 1, 0, 1)
ON DUPLICATE KEY UPDATE
`title` = VALUES(`title`), `permission_code` = VALUES(`permission_code`), `status` = VALUES(`status`);

-- Preserve the original creative capabilities of the built-in user role. Resource
-- authorization still limits these actions to projects/assets the user can access.
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id
FROM `sys_role` r
JOIN `sys_menu` m ON m.status = 1
WHERE r.code = 'user'
  AND m.permission_code IS NOT NULL
  AND m.permission_code NOT LIKE 'system:%'
  AND m.permission_code NOT LIKE 'ai:%'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_menu` rm
      WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.deleted = 0
  );

-- Normalize nullable SYSTEM scopes so concurrent inserts cannot create duplicates.
ALTER TABLE `sys_role_assignment`
  ADD COLUMN `scope_key` BIGINT GENERATED ALWAYS AS (IFNULL(`scope_id`, 0)) STORED AFTER `scope_id`,
  ADD COLUMN `active_assignment_key` VARCHAR(191) GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 0 THEN CONCAT(`user_id`, ':', `role_id`, ':', `scope_type`, ':', IFNULL(`scope_id`, 0)) ELSE NULL END
  ) STORED AFTER `scope_key`;

ALTER TABLE `sys_role_assignment`
  DROP INDEX `uk_user_role_scope`,
  ADD UNIQUE KEY `uk_active_role_assignment` (`active_assignment_key`);

-- Full role-menu replacement uses logical deletes. Only active rows must be unique,
-- otherwise the third save of the same role would collide with older deleted rows.
ALTER TABLE `sys_role_menu`
  ADD COLUMN `active_role_menu_key` VARCHAR(64) GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 0 THEN CONCAT(`role_id`, ':', `menu_id`) ELSE NULL END
  ) STORED AFTER `menu_id`,
  DROP INDEX `uk_role_menu`,
  ADD UNIQUE KEY `uk_active_role_menu` (`active_role_menu_key`);
