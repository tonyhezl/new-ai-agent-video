package com.tonyhe.fusion.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import com.tonyhe.fusion.mapper.system.RoleMapper;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 作用域角色分配服务
 * <p>
 * 管理用户在 SYSTEM / TEAM / PROJECT 作用域下的角色分配，
 * 支持临时授权（过期时间）和启用/禁用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAssignmentService {

    private final RoleAssignmentMapper roleAssignmentMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final TeamMapper teamMapper;
    private final PermissionMonitorService permissionMonitorService;

    /**
     * 为用户分配作用域角色
     */
    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public RoleAssignment assign(Long userId, Long roleId, String scopeType, Long scopeId,
                                  Long createdBy, LocalDateTime expiresAt) {
        validateAssignment(userId, roleId, scopeType, scopeId, expiresAt);
        LambdaQueryWrapper<RoleAssignment> existsWrapper = new LambdaQueryWrapper<RoleAssignment>()
                .eq(RoleAssignment::getUserId, userId)
                .eq(RoleAssignment::getRoleId, roleId)
                .eq(RoleAssignment::getScopeType, scopeType);
        if (scopeId == null) {
            existsWrapper.isNull(RoleAssignment::getScopeId);
        } else {
            existsWrapper.eq(RoleAssignment::getScopeId, scopeId);
        }
        boolean exists = roleAssignmentMapper.exists(existsWrapper);
        if (exists) {
            log.info("[RoleAssignment] 已存在: userId={}, roleId={}, scope={}:{}", userId, roleId, scopeType, scopeId);
            return null;
        }
        RoleAssignment assignment = RoleAssignment.builder()
                .userId(userId)
                .roleId(roleId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .status("ACTIVE")
                .expiresAt(expiresAt)
                .createdBy(createdBy)
                .build();
        roleAssignmentMapper.insert(assignment);
        permissionMonitorService.incrementPermissionVersion();
        return assignment;
    }

    /**
     * 撤销用户的作用域角色
     */
    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public void revoke(Long userId, Long roleId, String scopeType, Long scopeId) {
        LambdaQueryWrapper<RoleAssignment> wrapper = new LambdaQueryWrapper<RoleAssignment>()
                .eq(RoleAssignment::getUserId, userId)
                .eq(RoleAssignment::getRoleId, roleId)
                .eq(RoleAssignment::getScopeType, scopeType);
        if (scopeId == null) {
            wrapper.isNull(RoleAssignment::getScopeId);
        } else {
            wrapper.eq(RoleAssignment::getScopeId, scopeId);
        }
        if (roleAssignmentMapper.delete(wrapper) > 0) {
            permissionMonitorService.incrementPermissionVersion();
        }
    }

    /**
     * 查询用户在指定作用域下的有效角色分配
     */
    public List<RoleAssignment> getEffectiveAssignments(Long userId, String scopeType, Long scopeId) {
        LambdaQueryWrapper<RoleAssignment> wrapper = new LambdaQueryWrapper<RoleAssignment>()
                .eq(RoleAssignment::getUserId, userId)
                .eq(RoleAssignment::getStatus, "ACTIVE")
                .and(w -> w.isNull(RoleAssignment::getExpiresAt)
                        .or().gt(RoleAssignment::getExpiresAt, LocalDateTime.now()));
        if (scopeType != null) {
            if (scopeId == null) {
                wrapper.eq(RoleAssignment::getScopeType, "SYSTEM")
                        .isNull(RoleAssignment::getScopeId);
            } else {
                wrapper.and(w -> w.eq(RoleAssignment::getScopeType, "SYSTEM")
                        .or(sub -> sub.eq(RoleAssignment::getScopeType, scopeType)
                                .eq(RoleAssignment::getScopeId, scopeId)));
            }
        }
        return roleAssignmentMapper.selectList(wrapper);
    }

    /**
     * 获取用户所有有效角色（含系统级 + 作用域级）
     */
    public List<Role> getEffectiveRoles(Long userId, String scopeType, Long scopeId) {
        List<RoleAssignment> assignments = getEffectiveAssignments(userId, scopeType, scopeId);
        if (assignments.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = assignments.stream().map(RoleAssignment::getRoleId).toList();
        return roleMapper.selectByIds(roleIds);
    }

    public List<ScopeOption> listTeamScopes() {
        return teamMapper.selectList(new LambdaQueryWrapper<Team>()
                        .eq(Team::getStatus, 1).orderByAsc(Team::getName))
                .stream().map(team -> new ScopeOption(team.getId(), team.getName())).toList();
    }

    public List<ScopeOption> listProjectScopes() {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .orderByAsc(Project::getName))
                .stream().map(project -> new ScopeOption(project.getId(), project.getName())).toList();
    }

    public record ScopeOption(Long id, String name) {}

    /**
     * 禁用用户在指定作用域下的所有角色分配
     */
    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public void disableByScope(Long userId, String scopeType, Long scopeId) {
        List<RoleAssignment> assignments = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<RoleAssignment>()
                        .eq(RoleAssignment::getUserId, userId)
                        .eq(RoleAssignment::getScopeType, scopeType)
                        .eq(scopeId != null, RoleAssignment::getScopeId, scopeId));
        for (RoleAssignment assignment : assignments) {
            assignment.setStatus("DISABLED");
            roleAssignmentMapper.updateById(assignment);
        }
        if (!assignments.isEmpty()) {
            permissionMonitorService.incrementPermissionVersion();
        }
    }

    /**
     * 清理已过期的角色分配
     */
    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public int cleanupExpired() {
        List<RoleAssignment> expired = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<RoleAssignment>()
                        .eq(RoleAssignment::getStatus, "ACTIVE")
                        .isNotNull(RoleAssignment::getExpiresAt)
                        .lt(RoleAssignment::getExpiresAt, LocalDateTime.now()));
        for (RoleAssignment assignment : expired) {
            assignment.setStatus("DISABLED");
            roleAssignmentMapper.updateById(assignment);
        }
        if (!expired.isEmpty()) {
            permissionMonitorService.incrementPermissionVersion();
        }
        return expired.size();
    }

    private void validateAssignment(Long userId, Long roleId, String scopeType, Long scopeId,
                                    LocalDateTime expiresAt) {
        if (userId == null || userMapper.selectById(userId) == null) {
            throw new BusinessException(400, "用户不存在");
        }
        Role role = roleId == null ? null : roleMapper.selectById(roleId);
        if (role == null || !Integer.valueOf(1).equals(role.getStatus())) {
            throw new BusinessException(400, "角色不存在或已禁用");
        }
        if (!List.of("SYSTEM", "TEAM", "PROJECT").contains(scopeType)) {
            throw new BusinessException(400, "scopeType 仅支持 SYSTEM、TEAM、PROJECT");
        }
        if ("SYSTEM".equals(scopeType)) {
            if (scopeId != null) {
                throw new BusinessException(400, "SYSTEM 作用域不能填写 scopeId");
            }
        } else if (scopeId == null) {
            throw new BusinessException(400, scopeType + " 作用域必须填写 scopeId");
        } else if ("TEAM".equals(scopeType) && teamMapper.selectById(scopeId) == null) {
            throw new BusinessException(400, "团队不存在");
        } else if ("PROJECT".equals(scopeType) && projectMapper.selectById(scopeId) == null) {
            throw new BusinessException(400, "项目不存在");
        }
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "过期时间必须晚于当前时间");
        }
    }
}
