package com.tonyhe.fusion.enums.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目成员角色枚举
 * <p>
 * 团队角色映射到项目默认角色：
 * - Team OWNER/ADMIN → Project MANAGER
 * - Team MEMBER → Project EDITOR
 * 项目显式角色优先于团队默认映射。
 */
@Getter
@AllArgsConstructor
public enum ProjectMemberRoleEnum {

    OWNER(1, "项目拥有者"),
    MANAGER(2, "项目管理员"),
    EDITOR(3, "编辑者"),
    VIEWER(4, "只读成员");

    private final Integer role;
    private final String name;

    /**
     * 团队角色 → 项目默认角色映射
     */
    public static ProjectMemberRoleEnum fromTeamRole(Integer teamRole) {
        if (teamRole == null) return VIEWER;
        return switch (teamRole) {
            case 1, 2 -> MANAGER;  // Team OWNER/ADMIN → Project MANAGER
            case 3 -> EDITOR;       // Team MEMBER → Project EDITOR
            default -> VIEWER;
        };
    }

    public static ProjectMemberRoleEnum fromRole(Integer role) {
        if (role == null) return null;
        for (ProjectMemberRoleEnum value : values()) {
            if (value.role.equals(role)) return value;
        }
        return null;
    }

    /**
     * 判断角色是否具有指定动作权限
     */
    public boolean can(com.tonyhe.fusion.security.AccessAction action) {
        return switch (action) {
            case VIEW -> true;  // 所有角色可查看
            case EDIT -> this == OWNER || this == MANAGER || this == EDITOR;
            case DELETE -> this == OWNER || this == MANAGER;
            case MANAGE -> this == OWNER || this == MANAGER;
        };
    }
}
