package com.tonyhe.fusion.security;

/**
 * 权限码常量，统一管理所有功能权限标识。
 * <p>
 * 命名规则：资源:动作，必要时增加范围后缀。
 */
public final class PermissionCodes {

    private PermissionCodes() {}

    // ========== 工作台 ==========
    public static final String DASHBOARD_VIEW = "dashboard:view";

    // ========== 项目 ==========
    public static final String PROJECT_VIEW = "project:view";
    public static final String PROJECT_CREATE = "project:create";
    public static final String PROJECT_UPDATE = "project:update";
    public static final String PROJECT_DELETE = "project:delete";
    public static final String PROJECT_MEMBER_MANAGE = "project:member:manage";

    // ========== 团队 ==========
    public static final String TEAM_VIEW = "team:view";
    public static final String TEAM_CREATE = "team:create";
    public static final String TEAM_MEMBER_MANAGE = "team:member:manage";

    // ========== 剧本 ==========
    public static final String SCRIPT_VIEW = "script:view";
    public static final String SCRIPT_EDIT = "script:edit";

    // ========== 分镜 ==========
    public static final String STORYBOARD_VIEW = "storyboard:view";
    public static final String STORYBOARD_EDIT = "storyboard:edit";
    public static final String STORYBOARD_GENERATE = "storyboard:generate";

    // ========== 资产 ==========
    public static final String ASSET_VIEW = "asset:view";
    public static final String ASSET_EDIT = "asset:edit";
    public static final String ASSET_DELETE = "asset:delete";

    // ========== 生成任务 ==========
    public static final String GENERATION_VIEW = "generation:view";
    public static final String GENERATION_CREATE = "generation:create";
    public static final String GENERATION_CANCEL = "generation:cancel";

    // ========== 媒体 ==========
    public static final String MEDIA_READ = "media:read";
    public static final String MEDIA_DELETE = "media:delete";

    // ========== POV ==========
    public static final String POV_STORY_VIEW = "pov:story:view";
    public static final String POV_STORY_EDIT = "pov:story:edit";
    public static final String POV_VOICE_GENERATE = "pov:voice:generate";

    // ========== 系统 ==========
    public static final String SYSTEM_USER_MANAGE = "system:user:manage";
    public static final String SYSTEM_ROLE_MANAGE = "system:role:manage";
    public static final String SYSTEM_MENU_MANAGE = "system:menu:manage";
    public static final String SYSTEM_CONFIG_MANAGE = "system:config:manage";

    // ========== AI 配置 ==========
    public static final String AI_MODEL_MANAGE = "ai:model:manage";
    public static final String AI_CONFIG_MANAGE = "ai:config:manage";
}
