package com.tonyhe.fusion.service.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.entity.team.TeamMember;
import com.tonyhe.fusion.enums.team.TeamMemberRoleEnum;
import com.tonyhe.fusion.mapper.team.TeamMemberMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.security.TokenService;
import com.tonyhe.fusion.service.system.SystemConfigService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.CacheEvict;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int OWNER_TYPE_TEAM = 2;

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TokenService tokenService;
    private final SystemConfigService systemConfigService;
    private final PermissionMonitorService permissionMonitorService;

    private static final String MULTI_TEAM_CONFIG_KEY = "multi_team_enabled";

    @Data
    @AllArgsConstructor
    public static class OwnerScope {
        private Integer ownerType;
        private Long ownerId;
    }

    @Transactional
    public Team createTeam(String name, String description, Long ownerUserId) {
        if (!isMultiTeamEnabled() && getSingleTeam() != null) {
            throw new BusinessException(400, "当前未开启多团队模式，仅支持单团队");
        }
        return createTeamRecord(name, description, ownerUserId, TeamMemberRoleEnum.OWNER.getRole());
    }

    @Transactional
    public Team createInitialTeam(String name, Long ownerUserId) {
        if (getSingleTeam() != null) {
            throw new BusinessException(400, "默认团队已存在");
        }
        return createTeamRecord(name, null, ownerUserId, TeamMemberRoleEnum.OWNER.getRole());
    }

    public Team getSingleTeam() {
        return teamMapper.selectOne(new LambdaQueryWrapper<Team>()
                .orderByAsc(Team::getId)
                .last("LIMIT 1"));
    }

    public Team getRequiredSingleTeam() {
        Team team = getSingleTeam();
        if (team == null) {
            throw new BusinessException(500, "默认团队不存在，请先完成管理员初始化");
        }
        return team;
    }

    public Long getCurrentTeamIdByUser(Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Long authenticatedTeamId = SecurityUtils.getCurrentTeamId();
        if (currentUserId != null && currentUserId.equals(userId)
                && authenticatedTeamId != null
                && isUserInTeam(authenticatedTeamId, userId)) {
            return authenticatedTeamId;
        }
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getStatus, 1)
                .orderByAsc(TeamMember::getTeamId)
                .orderByAsc(TeamMember::getId)
                .last("LIMIT 1"));
        return member != null ? member.getTeamId() : null;
    }

    public Long getRequiredCurrentTeamIdByUser(Long userId) {
        Long teamId = getCurrentTeamIdByUser(userId);
        if (teamId == null) {
            throw new BusinessException(404, "当前用户未加入任何团队");
        }
        return teamId;
    }

    public OwnerScope getRequiredCurrentOwnerScopeByUser(Long userId) {
        return new OwnerScope(OWNER_TYPE_TEAM, getRequiredCurrentTeamIdByUser(userId));
    }

    @Transactional
    public void switchCurrentTeam(Long userId, Long teamId, String accessToken) {
        if (!isUserInTeam(teamId, userId)) {
            throw new BusinessException(403, "无权切换到该团队");
        }
        boolean updated = tokenService.updateCurrentTeam(accessToken, userId, teamId);
        if (!updated) {
            throw new BusinessException(401, "登录态已失效，请重新登录");
        }
        SecurityUtils.refreshCurrentTeamId(teamId);
    }

    public boolean isUserInTeam(Long teamId, Long userId) {
        if (teamId == null) {
            return false;
        }
        return teamMemberMapper.exists(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getStatus, 1));
    }

    public List<Long> listMemberUserIds(Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getStatus, 1))
                .stream()
                .map(TeamMember::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean isUserInSingleTeam(Long userId) {
        Team team = getSingleTeam();
        if (team == null) {
            return false;
        }
        return teamMemberMapper.exists(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, team.getId())
                .eq(TeamMember::getUserId, userId));
    }

    public List<Long> listSingleTeamMemberUserIds() {
        return listMemberUserIds(getRequiredSingleTeam().getId());
    }

    @Transactional
    public void addUserToSingleTeam(Long userId, Integer role) {
        Team team = getRequiredSingleTeam();
        addMember(team.getId(), userId, role);
    }

    public Team getById(Long id) {
        return teamMapper.selectById(id);
    }

    public PageResult<Team> getPage(String name, Integer status, int pageNo, int pageSize) {
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(name != null, Team::getName, name)
                .eq(status != null, Team::getStatus, status)
                .orderByDesc(Team::getId);
        return PageResult.of(teamMapper.selectPage(new Page<>(pageNo, pageSize), wrapper));
    }

    public PageResult<Team> getPageForUser(Long userId, String name, Integer status, int pageNo, int pageSize) {
        List<Long> teamIds = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getUserId, userId)
                        .eq(TeamMember::getStatus, 1))
                .stream().map(TeamMember::getTeamId).distinct().toList();
        if (teamIds.isEmpty()) {
            return new PageResult<>(List.of(), 0L);
        }
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<Team>()
                .in(Team::getId, teamIds)
                .like(name != null, Team::getName, name)
                .eq(status != null, Team::getStatus, status)
                .orderByDesc(Team::getId);
        return PageResult.of(teamMapper.selectPage(new Page<>(pageNo, pageSize), wrapper));
    }

    @Transactional
    public void updateTeam(Long id, String name, String description, String logo, Integer status) {
        Team team = teamMapper.selectById(id);
        if (team == null) throw new BusinessException(404, "团队不存在");
        if (name != null) team.setName(name);
        if (description != null) team.setDescription(description);
        if (logo != null) team.setLogo(logo);
        if (status != null) team.setStatus(status);
        teamMapper.updateById(team);
    }

    @Transactional
    public void deleteTeam(Long id) {
        if (!isMultiTeamEnabled()) {
            throw new BusinessException(400, "当前未开启多团队模式，不支持删除团队");
        }
        Team team = teamMapper.selectById(id);
        if (team == null) throw new BusinessException(404, "团队不存在");
        // 删除所有团队成员
        teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, id));
        // 逻辑删除团队
        teamMapper.deleteById(id);
    }

    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public Long addMember(Long teamId, Long userId, Integer role) {
        boolean exists = teamMemberMapper.exists(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId));
        if (exists) {
            throw new BusinessException(400, "该用户已是团队成员");
        }
        TeamMember member = TeamMember.builder()
                .teamId(teamId).userId(userId)
                .role(role != null ? role : TeamMemberRoleEnum.MEMBER.getRole())
                .status(1).joinTime(LocalDateTime.now())
                .build();
        teamMemberMapper.insert(member);
        permissionMonitorService.incrementPermissionVersion();
        return member.getId();
    }

    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public void removeMember(Long teamId, Long userId) {
        int deleted = teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId));
        if (deleted > 0) permissionMonitorService.incrementPermissionVersion();
    }

    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public void changeMemberRole(Long teamId, Long userId, Integer role) {
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId));
        if (member == null) throw new BusinessException(404, "成员不存在");
        member.setRole(role);
        teamMemberMapper.updateById(member);
        permissionMonitorService.incrementPermissionVersion();
    }

    public List<TeamMember> getMemberList(Long teamId) {
        return teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, teamId));
    }

    private Team createTeamRecord(String name, String description, Long ownerUserId, Integer ownerRole) {
        Team team = Team.builder()
                .name(name)
                .description(description)
                .ownerUserId(ownerUserId)
                .status(1)
                .build();
        teamMapper.insert(team);
        teamMemberMapper.insert(TeamMember.builder()
                .teamId(team.getId())
                .userId(ownerUserId)
                .role(ownerRole)
                .status(1)
                .joinTime(LocalDateTime.now())
                .build());
        return team;
    }

    /**
     * 是否开启多团队模式
     */
    public boolean isMultiTeamEnabled() {
        String value = systemConfigService.getValue(MULTI_TEAM_CONFIG_KEY);
        return "true".equalsIgnoreCase(value);
    }

    /**
     * 获取用户加入的所有团队
     */
    public List<Team> getUserTeams(Long userId) {
        List<TeamMember> members = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getUserId, userId)
                        .eq(TeamMember::getStatus, 1));
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> teamIds = members.stream().map(TeamMember::getTeamId).collect(Collectors.toList());
        return teamMapper.selectBatchIds(teamIds);
    }

    /**
     * 获取用户在团队中的角色
     */
    public Integer getTeamRole(Long teamId, Long userId) {
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getStatus, 1));
        return member != null ? member.getRole() : null;
    }
}
