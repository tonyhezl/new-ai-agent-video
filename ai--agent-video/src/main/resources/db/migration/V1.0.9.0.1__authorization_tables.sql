-- P1: 作用域角色分配表
-- 支持在 SYSTEM / TEAM / PROJECT 三种作用域下为用户分配角色
CREATE TABLE IF NOT EXISTS `sys_role_assignment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `role_id`     BIGINT       NOT NULL COMMENT '角色ID',
    `scope_type`  VARCHAR(20)  NOT NULL COMMENT '作用域类型：SYSTEM / TEAM / PROJECT',
    `scope_id`    BIGINT       NULL     COMMENT '作用域ID：SYSTEM 为 NULL，TEAM/PROJECT 为对应 ID',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / DISABLED',
    `expires_at`  DATETIME     NULL     COMMENT '过期时间，可选，用于临时授权',
    `created_by`  BIGINT       NULL     COMMENT '创建者用户ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role_scope` (`user_id`, `role_id`, `scope_type`, `scope_id`, `deleted`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_scope` (`scope_type`, `scope_id`),
    INDEX `idx_status_expires` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作用域角色分配表';

-- P2: 系统菜单表
CREATE TABLE IF NOT EXISTS `sys_menu` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '菜单主键',
    `parent_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '父级目录ID，0为根节点',
    `type`            VARCHAR(20)  NOT NULL COMMENT '类型：DIRECTORY / PAGE / BUTTON / API',
    `name`            VARCHAR(100) NOT NULL COMMENT '稳定标识，不随显示文案变化',
    `title`           VARCHAR(100) NOT NULL COMMENT '前端显示名称',
    `path`            VARCHAR(255) NULL     COMMENT '前端路由或 API 路径',
    `component`       VARCHAR(255) NULL     COMMENT '页面组件标识，可为空',
    `permission_code` VARCHAR(100) NULL     COMMENT '权限码，如 project:update',
    `icon`            VARCHAR(100) NULL     COMMENT '图标名称',
    `sort`            INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `visible`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否显示',
    `status`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '启用/禁用',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`id`),
    INDEX `idx_parent_id` (`parent_id`),
    INDEX `idx_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统菜单表';

-- P2: 角色菜单关联表
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
    `id`          BIGINT     NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_id`     BIGINT     NOT NULL COMMENT '角色ID',
    `menu_id`     BIGINT     NOT NULL COMMENT '菜单ID',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

-- P4: 权限变更审计表
CREATE TABLE IF NOT EXISTS `sys_permission_audit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `operator_id` BIGINT       NOT NULL COMMENT '操作者用户ID',
    `target_user_id` BIGINT   NULL     COMMENT '被操作用户ID',
    `action`      VARCHAR(50)  NOT NULL COMMENT '操作类型：ASSIGN / REVOKE / DISABLE / ENABLE',
    `role_id`     BIGINT       NULL     COMMENT '涉及角色ID',
    `scope_type`  VARCHAR(20)  NULL     COMMENT '作用域类型',
    `scope_id`    BIGINT       NULL     COMMENT '作用域ID',
    `detail`      TEXT         NULL     COMMENT '操作详情JSON',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_operator_id` (`operator_id`),
    INDEX `idx_target_user_id` (`target_user_id`),
    INDEX `idx_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限变更审计表';

-- P2: 初始化菜单数据
INSERT INTO `sys_menu` (`id`, `parent_id`, `type`, `name`, `title`, `path`, `permission_code`, `icon`, `sort`) VALUES
(1,  0, 'DIRECTORY', 'dashboard',   '工作台',   '/dashboard',           'dashboard:view',           'Layout',    1),
(2,  0, 'DIRECTORY', 'project',     '项目',     '/projects',            'project:view',             'Folder',    2),
(3,  2, 'PAGE',      'project-list','项目列表', '/projects',            'project:view',             'List',      1),
(4,  2, 'BUTTON',    'project-create','创建项目', NULL,                  'project:create',           'Plus',      2),
(5,  2, 'BUTTON',    'project-update','编辑项目', NULL,                  'project:update',           'Edit',      3),
(6,  2, 'BUTTON',    'project-delete','删除项目', NULL,                  'project:delete',           'Delete',    4),
(7,  0, 'DIRECTORY', 'script',      '剧本',     '/scripts',             'script:view',              'Document',  3),
(8,  0, 'DIRECTORY', 'storyboard',  '分镜',     '/storyboards',         'storyboard:view',          'Film',      4),
(9,  0, 'DIRECTORY', 'asset',       '资产库',   '/assets',              'asset:view',               'Picture',   5),
(10, 0, 'DIRECTORY', 'generation',  '任务中心', '/tasks',               'generation:view',          'Monitor',   6),
(11, 0, 'DIRECTORY', 'media',       '媒体库',   '/media',               'media:read',               'Folder',    7),
(12, 0, 'DIRECTORY', 'pov',         'POV故事',  '/pov',                 'pov:story:view',           'VideoCamera', 8),
(13, 0, 'DIRECTORY', 'team',        '团队',     '/team',                'team:view',                'User',      9),
(14, 0, 'DIRECTORY', 'system',      '系统管理', '/settings',            'system:user:manage',       'Setting',   99),
(15, 14, 'PAGE',     'system-user', '用户管理', '/settings/users',      'system:user:manage',       'User',      1),
(16, 14, 'PAGE',     'system-role', '角色管理', '/settings/roles',      'system:role:manage',       'UserFilled', 2),
(17, 14, 'PAGE',     'system-config','系统配置', '/settings/config',    'system:config:manage',     'Tools',     3),
(18, 14, 'PAGE',     'ai-model',    'AI模型',   '/settings/ai-models',  'ai:model:manage',          'Cpu',       4),
(19, 14, 'PAGE',     'ai-config',   'API配置',  '/settings/api-config', 'ai:config:manage',         'Connection', 5),
(20, 14, 'PAGE',     'storage-config','存储配置','/settings/storage',   'ai:config:manage',         'Files',     6)
ON DUPLICATE KEY UPDATE `title` = VALUES(`title`);
