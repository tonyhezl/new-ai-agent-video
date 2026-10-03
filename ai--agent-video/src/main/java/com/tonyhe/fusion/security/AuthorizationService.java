package com.tonyhe.fusion.security;

import java.util.Set;

/**
 * 授权服务接口
 * <p>
 * 统一权限码校验入口，支持基于角色的权限码（RBAC）和资源级访问控制（ABAC）。
 * 权限来源：系统角色权限 ∪ 作用域角色权限（sys_role_assignment）。
 */
public interface AuthorizationService {

    /**
     * 判断用户是否拥有指定权限码
     */
    boolean hasPermission(Long userId, String permissionCode);

    /**
     * 判断用户是否拥有任意一个指定权限码
     */
    boolean hasAnyPermission(Long userId, String... permissionCodes);

    /**
     * 校验用户是否拥有指定权限码，无权限时抛出 BusinessException(403)
     */
    void requirePermission(Long userId, String permissionCode);

    /**
     * 获取用户所有权限码集合（系统角色 ∪ 作用域角色）
     */
    Set<String> getUserPermissions(Long userId);

    Set<String> getUserPermissions(Long userId, Long projectId);

    boolean hasProjectPermission(Long userId, Long projectId, String permissionCode);

    void requireProjectPermission(Long userId, Long projectId, String permissionCode);

    // ========== 资源级访问控制 ==========

    boolean canAccessTeam(Long userId, Long teamId);

    boolean canAccessProject(Long userId, Long projectId);

    boolean canEditProject(Long userId, Long projectId);

    boolean canDeleteProject(Long userId, Long projectId);

    boolean canAccessAsset(Long userId, Long assetId);

    void requireProjectAccess(Long projectId, AccessAction action);
}
