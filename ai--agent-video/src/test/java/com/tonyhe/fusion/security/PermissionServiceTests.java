package com.tonyhe.fusion.security;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.MenuService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.system.RoleAssignmentService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTests {

    @Mock private UserService userService;
    @Mock private RoleAssignmentService roleAssignmentService;
    @Mock private TeamService teamService;
    @Mock private ProjectService projectService;
    @Mock private AssetService assetService;
    @Mock private MenuService menuService;
    @Mock private PermissionMonitorService permissionMonitorService;
    @Mock private CacheManager cacheManager;

    @InjectMocks private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        lenient().when(permissionMonitorService.getPermissionVersion()).thenReturn(7L);
        lenient().when(cacheManager.getCache("userPermissions")).thenReturn(new ConcurrentMapCache("testPermissions"));
    }

    @Test
    void adminHasWildcardPermission() {
        when(userService.isAdmin(1L)).thenReturn(true);
        assertThat(permissionService.getUserPermissions(1L)).containsExactly("*");
        assertThat(permissionService.hasPermission(1L, "any:permission")).isTrue();
    }

    @Test
    void adminIgnoresStaleUserPermissionCache() {
        when(userService.isAdmin(1L)).thenReturn(true);
        ConcurrentMapCache cache = (ConcurrentMapCache) cacheManager.getCache("userPermissions");
        cache.put("1:null:7", Set.of(PermissionCodes.DASHBOARD_VIEW));

        assertThat(permissionService.getUserPermissions(1L)).containsExactly("*");
        verify(teamService, never()).getCurrentTeamIdByUser(1L);
        verify(permissionMonitorService, never()).recordCacheHit();
    }

    @Test
    void permissionsComeFromRoleMenuBindings() {
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(Set.of(20L)))
                .thenReturn(Set.of(PermissionCodes.PROJECT_VIEW, PermissionCodes.SCRIPT_VIEW));
        assertThat(permissionService.getUserPermissions(2L))
                .containsExactlyInAnyOrder(PermissionCodes.PROJECT_VIEW, PermissionCodes.SCRIPT_VIEW);
        verify(menuService).getPermissionCodesByRoleIds(Set.of(20L));
    }

    @Test
    void permissionsUseRedisSerializableSetImplementationInCache() {
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(Set.of(20L)))
                .thenReturn(Set.of(PermissionCodes.PROJECT_VIEW, PermissionCodes.GENERATION_CREATE));

        permissionService.getUserPermissions(2L);

        ConcurrentMapCache cache = (ConcurrentMapCache) cacheManager.getCache("userPermissions");
        assertThat(cache.getNativeCache().get("2:null:7")).isInstanceOf(HashSet.class);
    }

    @Test
    void corruptPermissionCacheIsEvictedAndRebuiltFromDatabase() {
        Cache brokenCache = mock(Cache.class);
        when(cacheManager.getCache("userPermissions")).thenReturn(brokenCache);
        when(brokenCache.get("2:null:7"))
                .thenThrow(new SerializationException("invalid cached collection type"));
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(Set.of(20L)))
                .thenReturn(Set.of(PermissionCodes.PROJECT_VIEW, PermissionCodes.GENERATION_CREATE));

        assertThat(permissionService.getUserPermissions(2L))
                .containsExactlyInAnyOrder(PermissionCodes.PROJECT_VIEW, PermissionCodes.GENERATION_CREATE);

        verify(brokenCache).evict("2:null:7");
        verify(brokenCache).put(eq("2:null:7"), isA(HashSet.class));
    }

    @Test
    void noHardcodedCreativePermissionsAreGranted() {
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(anySet())).thenReturn(Set.of(PermissionCodes.PROJECT_VIEW));
        assertThat(permissionService.hasPermission(2L, PermissionCodes.STORYBOARD_GENERATE)).isFalse();
        assertThat(permissionService.hasPermission(2L, PermissionCodes.SYSTEM_USER_MANAGE)).isFalse();
    }

    @Test
    void projectScopedRoleOnlyExtendsMatchingProject() {
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(Set.of(20L))).thenReturn(Set.of(PermissionCodes.PROJECT_VIEW));
        RoleAssignment assignment = RoleAssignment.builder()
                .userId(2L).roleId(30L).scopeType("PROJECT").scopeId(100L).status("ACTIVE").build();
        when(roleAssignmentService.getEffectiveAssignments(2L, "PROJECT", 100L)).thenReturn(List.of(assignment));
        when(roleAssignmentService.getEffectiveAssignments(2L, "PROJECT", 200L)).thenReturn(List.of());
        when(menuService.getPermissionCodesByRoleIds(Set.of(30L))).thenReturn(Set.of(PermissionCodes.STORYBOARD_EDIT));
        assertThat(permissionService.getUserPermissions(2L, 100L)).contains(PermissionCodes.STORYBOARD_EDIT);
        assertThat(permissionService.getUserPermissions(2L, 200L)).doesNotContain(PermissionCodes.STORYBOARD_EDIT);
    }

    @Test
    void cacheSeparatesCurrentTeams() {
        when(userService.isAdmin(2L)).thenReturn(false);
        when(userService.getUserRoles(2L)).thenReturn(List.of());
        when(teamService.getCurrentTeamIdByUser(2L)).thenReturn(10L, 10L, 20L);
        RoleAssignment teamTen = RoleAssignment.builder().roleId(30L).scopeType("TEAM").scopeId(10L).build();
        RoleAssignment teamTwenty = RoleAssignment.builder().roleId(40L).scopeType("TEAM").scopeId(20L).build();
        when(roleAssignmentService.getEffectiveAssignments(2L, "TEAM", 10L)).thenReturn(List.of(teamTen));
        when(roleAssignmentService.getEffectiveAssignments(2L, "TEAM", 20L)).thenReturn(List.of(teamTwenty));
        when(menuService.getPermissionCodesByRoleIds(Set.of(30L))).thenReturn(Set.of(PermissionCodes.SCRIPT_VIEW));
        when(menuService.getPermissionCodesByRoleIds(Set.of(40L))).thenReturn(Set.of(PermissionCodes.ASSET_VIEW));
        assertThat(permissionService.getUserPermissions(2L)).contains(PermissionCodes.SCRIPT_VIEW);
        assertThat(permissionService.getUserPermissions(2L)).contains(PermissionCodes.SCRIPT_VIEW);
        assertThat(permissionService.getUserPermissions(2L)).contains(PermissionCodes.ASSET_VIEW);
        verify(permissionMonitorService, atLeastOnce()).recordCacheHit();
    }

    @Test
    void requirePermissionRecordsDeniedRequest() {
        stubGlobalRole(2L, 20L);
        when(menuService.getPermissionCodesByRoleIds(anySet())).thenReturn(Set.of());
        assertThatThrownBy(() -> permissionService.requirePermission(2L, PermissionCodes.SYSTEM_USER_MANAGE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权限");
        verify(permissionMonitorService).recordDenied();
    }

    private void stubGlobalRole(Long userId, Long roleId) {
        when(userService.isAdmin(userId)).thenReturn(false);
        when(userService.getUserRoles(userId)).thenReturn(List.of(Role.builder().id(roleId).code("custom").build()));
        lenient().when(teamService.getCurrentTeamIdByUser(userId)).thenReturn(null);
        lenient().when(roleAssignmentService.getEffectiveAssignments(userId, "TEAM", null)).thenReturn(List.of());
    }
}
