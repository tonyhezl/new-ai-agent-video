package com.tonyhe.fusion.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.project.ProjectMember;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.project.ProjectMemberMapper;
import com.tonyhe.fusion.mapper.team.TeamMemberMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.team.TeamMember;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

/**
 * 数据完整性扫描服务
 * <p>
 * 定期扫描"无父级归属""已删除仍可访问""过期未清理"等脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataIntegrityScanService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final RoleAssignmentMapper roleAssignmentMapper;
    private final RoleAssignmentService roleAssignmentService;

    /**
     * 扫描结果
     */
    public static class ScanResult {
        public int orphanedProjectMembers;
        public int orphanedTeamMembers;
        public int expiredRoleAssignments;
        public int disabledUsersWithActiveRoles;
        public List<String> warnings;

        public ScanResult() {
            this.warnings = new ArrayList<>();
        }
    }

    /**
     * 执行全量扫描
     */
    public ScanResult scan() {
        ScanResult result = new ScanResult();

        // 1. 扫描项目成员指向已删除项目
        List<ProjectMember> allProjectMembers = projectMemberMapper.selectList(null);
        Set<Long> projectIds = allProjectMembers.stream()
                .map(ProjectMember::getProjectId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<Long> validProjectIds = projectIds.isEmpty() ? Collections.emptySet()
                : projectMapper.selectByIds(projectIds).stream().map(Project::getId).collect(java.util.stream.Collectors.toSet());
        for (ProjectMember pm : allProjectMembers) {
            if (pm.getProjectId() == null || !validProjectIds.contains(pm.getProjectId())) {
                result.orphanedProjectMembers++;
                result.warnings.add(String.format(
                        "项目成员(id=%d)指向不存在或已删除的项目(projectId=%d)",
                        pm.getId(), pm.getProjectId()));
            }
        }

        // 2. 扫描团队成员指向已删除团队
        List<TeamMember> allTeamMembers = teamMemberMapper.selectList(null);
        Set<Long> teamIds = allTeamMembers.stream()
                .map(TeamMember::getTeamId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<Long> validTeamIds = teamIds.isEmpty() ? Collections.emptySet()
                : teamMapper.selectByIds(teamIds).stream().map(com.tonyhe.fusion.entity.team.Team::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (TeamMember tm : allTeamMembers) {
            if (tm.getTeamId() == null || !validTeamIds.contains(tm.getTeamId())) {
                result.orphanedTeamMembers++;
                result.warnings.add(String.format(
                        "团队成员(id=%d)指向不存在或已删除的团队(teamId=%d)",
                        tm.getId(), tm.getTeamId()));
            }
        }

        // 3. 扫描过期但未清理的角色分配
        List<RoleAssignment> expired = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<RoleAssignment>()
                        .eq(RoleAssignment::getStatus, "ACTIVE")
                        .isNotNull(RoleAssignment::getExpiresAt)
                        .lt(RoleAssignment::getExpiresAt, LocalDateTime.now()));
        result.expiredRoleAssignments = expired.size();
        if (!expired.isEmpty()) {
            result.warnings.add(String.format("发现 %d 条已过期但未清理的角色分配", expired.size()));
        }

        // 4. 扫描禁用用户仍持有的有效作用域角色。
        List<RoleAssignment> activeAssignments = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<RoleAssignment>().eq(RoleAssignment::getStatus, "ACTIVE"));
        Set<Long> assignedUserIds = activeAssignments.stream().map(RoleAssignment::getUserId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, User> usersById = assignedUserIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(assignedUserIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        Set<Long> disabledUsers = new HashSet<>();
        for (RoleAssignment assignment : activeAssignments) {
            User user = usersById.get(assignment.getUserId());
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
                disabledUsers.add(assignment.getUserId());
            }
        }
        result.disabledUsersWithActiveRoles = disabledUsers.size();
        if (!disabledUsers.isEmpty()) {
            result.warnings.add(String.format("发现 %d 个禁用或不存在的用户仍有活跃角色", disabledUsers.size()));
        }

        // 5. 扫描重复的项目成员（同一 user+project 多条记录）
        Map<String, Long> memberCounts = new java.util.HashMap<>();
        for (ProjectMember pm : allProjectMembers) {
            String key = pm.getProjectId() + ":" + pm.getUserId();
            memberCounts.merge(key, 1L, Long::sum);
        }
        memberCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .forEach(e -> result.warnings.add(
                        String.format("项目成员重复: projectUserId=%s, count=%d", e.getKey(), e.getValue())));

        return result;
    }

    /**
     * 自动清理过期角色分配
     */
    public int cleanupExpiredAssignments() {
        return roleAssignmentService.cleanupExpired();
    }

    /**
     * 每天凌晨 3 点执行扫描 + 自动清理
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledScanAndCleanup() {
        log.info("[DataIntegrityScan] 开始定时扫描...");
        ScanResult result = scan();
        int cleaned = cleanupExpiredAssignments();

        log.info("[DataIntegrityScan] 扫描完成: orphanedProjectMembers={}, expiredAssignments={}, cleaned={}, warnings={}",
                result.orphanedProjectMembers, result.expiredRoleAssignments, cleaned, result.warnings.size());

        for (String warning : result.warnings) {
            log.warn("[DataIntegrityScan] {}", warning);
        }
    }
}
