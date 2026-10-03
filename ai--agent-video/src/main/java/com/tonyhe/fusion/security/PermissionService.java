package com.tonyhe.fusion.security;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.service.system.RoleAssignmentService;
import com.tonyhe.fusion.service.system.MenuService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.entity.asset.Asset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限服务实现（P1 阶段）
 * <p>
 * 基于角色映射权限码，admin 角色自动拥有所有权限。
 * 后续可扩展为读取 sys_role_assignment 作用域授权表实现细粒度 ABAC。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService implements AuthorizationService {

    private static final String ADMIN_ROLE_CODE = "admin";

    private final UserService userService;
    private final RoleAssignmentService roleAssignmentService;
    private final TeamService teamService;
    private final ProjectService projectService;
    private final AssetService assetService;
    private final MenuService menuService;
    private final PermissionMonitorService permissionMonitorService;
    private final CacheManager cacheManager;

    @Override
    public Set<String> getUserPermissions(Long userId) {
        // 管理员权限不能依赖普通用户权限缓存。角色提升、缓存版本重用或历史脏数据
        // 都不应使管理员丢失通配权限并被前端路由守卫误判为 403。
        if (userService.isAdmin(userId)) {
            return Set.of("*");
        }
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        String cacheKey = userId + ":" + currentTeamId + ":" + permissionMonitorService.getPermissionVersion();
        Cache cache = cacheManager.getCache("userPermissions");
        if (cache != null) {
            try {
                Cache.ValueWrapper cached = cache.get(cacheKey);
                if (cached != null && cached.get() instanceof Set<?> cachedSet) {
                    permissionMonitorService.recordCacheHit();
                    @SuppressWarnings("unchecked")
                    Set<String> permissions = (Set<String>) cachedSet;
                    return Set.copyOf(permissions);
                }
            } catch (RuntimeException e) {
                log.warn("[PermissionCache] 读取失败，驱逐后从数据库重建: key={}, error={}",
                        cacheKey, e.getMessage());
                try {
                    cache.evict(cacheKey);
                } catch (RuntimeException evictError) {
                    log.warn("[PermissionCache] 驱逐失败: key={}, error={}",
                            cacheKey, evictError.getMessage());
                }
            }
        }
        permissionMonitorService.recordCacheMiss();
        Set<Long> roleIds = new HashSet<>();
        userService.getUserRoles(userId).stream().map(Role::getId).forEach(roleIds::add);
        List<RoleAssignment> assignments = roleAssignmentService.getEffectiveAssignments(userId, "TEAM", currentTeamId);
        assignments.stream().map(RoleAssignment::getRoleId).forEach(roleIds::add);
        // JDK immutable Set implementations are final, so Jackson NON_FINAL typing omits
        // their collection type and Redis later mistakes the first permission for a class name.
        Set<String> permissions = new HashSet<>(menuService.getPermissionCodesByRoleIds(roleIds));
        if (cache != null) {
            try {
                cache.put(cacheKey, permissions);
            } catch (RuntimeException e) {
                log.warn("[PermissionCache] 写入失败，本次使用数据库权限结果: key={}, error={}",
                        cacheKey, e.getMessage());
            }
        }
        return Set.copyOf(permissions);
    }

    @Override
    public Set<String> getUserPermissions(Long userId, Long projectId) {
        Set<String> permissions = new HashSet<>(getUserPermissions(userId));
        if (permissions.contains("*") || projectId == null) {
            return permissions;
        }
        Set<Long> projectRoleIds = roleAssignmentService
                .getEffectiveAssignments(userId, "PROJECT", projectId).stream()
                .filter(a -> "PROJECT".equals(a.getScopeType()) && projectId.equals(a.getScopeId()))
                .map(RoleAssignment::getRoleId)
                .collect(java.util.stream.Collectors.toSet());
        permissions.addAll(menuService.getPermissionCodesByRoleIds(projectRoleIds));
        return permissions;
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        Set<String> permissions = getUserPermissions(userId);
        return permissions.contains("*") || permissions.contains(permissionCode);
    }

    @Override
    public boolean hasProjectPermission(Long userId, Long projectId, String permissionCode) {
        Set<String> permissions = getUserPermissions(userId, projectId);
        return permissions.contains("*") || permissions.contains(permissionCode);
    }

    @Override
    public void requireProjectPermission(Long userId, Long projectId, String permissionCode) {
        if (!hasProjectPermission(userId, projectId, permissionCode)) {
            permissionMonitorService.recordDenied();
            throw new BusinessException(403, "无权限: " + permissionCode);
        }
    }

    /**
     * SpEL 便捷方法，从 SecurityContext 获取当前用户并校验权限码。
     * 用法：@PreAuthorize("@permissionService.has('system:user:manage')")
     */
    public boolean has(String permissionCode) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return false;
        return hasPermission(userId, permissionCode);
    }

    public boolean hasAny(String... permissionCodes) {
        Long userId = SecurityUtils.getCurrentUserId();
        return userId != null && hasAnyPermission(userId, permissionCodes);
    }

    @Override
    public boolean hasAnyPermission(Long userId, String... permissionCodes) {
        Set<String> permissions = getUserPermissions(userId);
        if (permissions.contains("*")) return true;
        for (String code : permissionCodes) {
            if (permissions.contains(code)) return true;
        }
        return false;
    }

    @Override
    public void requirePermission(Long userId, String permissionCode) {
        if (!hasPermission(userId, permissionCode)) {
            log.warn("[PermissionDenied] userId={}, permission={}", userId, permissionCode);
            permissionMonitorService.recordDenied();
            throw new BusinessException(403, "无权限: " + permissionCode);
        }
    }

    // ========== 资源级访问控制实现 ==========

    @Override
    public boolean canAccessTeam(Long userId, Long teamId) {
        if (userService.isAdmin(userId)) return true;
        return teamService.isUserInTeam(teamId, userId);
    }

    @Override
    public boolean canAccessProject(Long userId, Long projectId) {
        if (userService.isAdmin(userId)) return true;
        return projectService.canAccessProject(projectId, userId);
    }

    @Override
    public boolean canEditProject(Long userId, Long projectId) {
        if (userService.isAdmin(userId)) return true;
        try {
            projectService.checkProjectEditAccess(projectId, userId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    @Override
    public boolean canDeleteProject(Long userId, Long projectId) {
        if (userService.isAdmin(userId)) return true;
        try {
            projectService.checkProjectDeleteAccess(projectId, userId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    @Override
    public boolean canAccessAsset(Long userId, Long assetId) {
        if (userService.isAdmin(userId)) return true;
        Asset asset = assetService.getById(assetId);
        return asset != null && assetService.canAccessAsset(asset, userId);
    }

    @Override
    public void requireProjectAccess(Long projectId, AccessAction action) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (userService.isAdmin(userId)) return;
        if (!projectService.canAccessProject(projectId, userId)) {
            throw new BusinessException(403, "无权访问该项目");
        }
        if (action != AccessAction.VIEW) {
            projectService.checkProjectAction(projectId, userId, action);
        }
    }

}
