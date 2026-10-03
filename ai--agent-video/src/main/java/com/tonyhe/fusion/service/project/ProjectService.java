package com.tonyhe.fusion.service.project;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.project.ProjectMember;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.project.ProjectMemberMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private static final int OWNER_TYPE_PERSONAL = 1;
    private static final int OWNER_TYPE_TEAM = 2;

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final AssetMapper assetMapper;
    private final AssetItemMapper assetItemMapper;
    private final TeamService teamService;
    private final UserService userService;
    private final PermissionMonitorService permissionMonitorService;

    @Cacheable(value = "project", key = "#id")
    public Project getById(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) throw new BusinessException("项目不存在: " + id);
        return project;
    }

    public PageResult<Project> page(int pageNo, int pageSize) {
        return PageResult.of(projectMapper.selectPage(new Page<>(pageNo, pageSize), null));
    }

    @Cacheable(value = "project", key = "'owner:' + #ownerType + ':' + #ownerId")
    public List<Project> listByOwner(Integer ownerType, Long ownerId) {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getOwnerType, ownerType)
                .eq(Project::getOwnerId, ownerId)
                .orderByDesc(Project::getCreateTime));
    }

    public List<Project> listAccessibleByUser(Long userId) {
        if (userService.isAdmin(userId)) {
            return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .orderByDesc(Project::getCreateTime));
        }
        return projectMapper.selectList(buildAccessibleWrapper(userId)
                .orderByDesc(Project::getCreateTime));
    }

    public PageResult<Project> pageAccessibleByUser(Long userId, int pageNo, int pageSize) {
        if (userService.isAdmin(userId)) {
            return page(pageNo, pageSize);
        }
        return PageResult.of(projectMapper.selectPage(new Page<>(pageNo, pageSize),
                buildAccessibleWrapper(userId).orderByDesc(Project::getCreateTime)));
    }

    public boolean canAccessProject(Long projectId, Long userId) {
        return canAccessProject(getById(projectId), userId);
    }

    public void checkProjectEditAccess(Long projectId, Long userId) {
        Project project = getById(projectId);
        if (project == null) throw new BusinessException(404, "项目不存在");
        if (OWNER_TYPE_PERSONAL == project.getOwnerType() && userId.equals(project.getOwnerId())) return;
        Integer role = getMemberRole(projectId, userId);
        if (role != null && role <= 3) return;
        throw new BusinessException(403, "无编辑权限");
    }

    public void checkProjectDeleteAccess(Long projectId, Long userId) {
        Project project = getById(projectId);
        if (project == null) throw new BusinessException(404, "项目不存在");
        if (OWNER_TYPE_PERSONAL == project.getOwnerType() && userId.equals(project.getOwnerId())) return;
        Integer role = getMemberRole(projectId, userId);
        if (role != null && role <= 2) return;
        throw new BusinessException(403, "无删除权限");
    }

    public void checkProjectAction(Long projectId, Long userId, com.tonyhe.fusion.security.AccessAction action) {
        switch (action) {
            case VIEW -> { if (!canAccessProject(projectId, userId)) throw new BusinessException(403, "无权访问该项目"); }
            case EDIT -> checkProjectEditAccess(projectId, userId);
            case DELETE -> checkProjectDeleteAccess(projectId, userId);
            case MANAGE -> checkProjectDeleteAccess(projectId, userId);
        }
    }

    public boolean canAccessProject(Project project, Long userId) {
        if (project == null) {
            return false;
        }
        // 管理员旁路：与 ResourceAccessService.checkProjectContentAccess 保持一致，
        // 避免 HTTP 端（有 admin 旁路）能操作但 AI 工具端（无旁路）被拒的不一致
        if (userId != null && userService.isAdmin(userId)) {
            return true;
        }
        if (userId == null) {
            log.warn("[canAccessProject] userId 为空，拒绝访问: projectId={}, ownerType={}, ownerId={}",
                    project.getId(), project.getOwnerType(), project.getOwnerId());
            return false;
        }
        if (OWNER_TYPE_PERSONAL == project.getOwnerType() && userId.equals(project.getOwnerId())) {
            return true;
        }
        if (isMember(project.getId(), userId)) {
            return true;
        }
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        if (currentTeamId == null) {
            log.warn("[canAccessProject] 用户无有效团队，拒绝访问: projectId={}, userId={}, ownerType={}, ownerId={}, isMember=false",
                    project.getId(), userId, project.getOwnerType(), project.getOwnerId());
            return false;
        }
        if (OWNER_TYPE_TEAM == project.getOwnerType() && currentTeamId.equals(project.getOwnerId())) {
            return true;
        }
        log.warn("[canAccessProject] 访问被拒: projectId={}, userId={}, ownerType={}, ownerId={}, currentTeamId={}, isMember=false",
                project.getId(), userId, project.getOwnerType(), project.getOwnerId(), currentTeamId);
        return false;
    }

    @CacheEvict(value = "project", allEntries = true)
    @Transactional
    public Project create(Project project) {
        validateProjectMediaUrls(project);
        applyCurrentTeamOwnership(project);
        projectMapper.insert(project);
        return project;
    }

    @CacheEvict(value = "project", allEntries = true)
    @Transactional
    public Project update(Project project) {
        getById(project.getId());
        validateProjectMediaUrls(project);
        projectMapper.updateById(project);
        return project;
    }

    @CacheEvict(value = "project", allEntries = true)
    @Transactional
    public void delete(Long id) {
        // 级联删除项目下的所有资产及其子项
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, id));
        for (Asset asset : assets) {
            assetItemMapper.delete(
                    new LambdaQueryWrapper<AssetItem>().eq(AssetItem::getAssetId, asset.getId()));
        }
        if (!assets.isEmpty()) {
            assetMapper.delete(new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, id));
        }
        // 删除项目成员
        memberMapper.delete(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, id));
        // 删除项目本身
        projectMapper.deleteById(id);
    }

    // ========== 成员管理 ==========

    public boolean isMember(Long projectId, Long userId) {
        return memberMapper.exists(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId));
    }

    public Integer getMemberRole(Long projectId, Long userId) {
        ProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .last("LIMIT 1"));
        return member != null ? member.getRole() : null;
    }

    private LambdaQueryWrapper<Project> buildAccessibleWrapper(Long userId) {
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        String memberProjectSql = "SELECT project_id FROM afv_project_member WHERE deleted = 0 AND user_id = " + userId;
        return new LambdaQueryWrapper<Project>().and(scope -> {
            scope.and(personal -> personal.eq(Project::getOwnerType, OWNER_TYPE_PERSONAL)
                            .eq(Project::getOwnerId, userId))
                    .or(member -> member.inSql(Project::getId, memberProjectSql));
            if (currentTeamId != null) {
                scope.or(team -> team.eq(Project::getOwnerType, OWNER_TYPE_TEAM)
                        .eq(Project::getOwnerId, currentTeamId));
            }
        });
    }

    @Cacheable(value = "projectMember", key = "#projectId")
    public List<ProjectMember> listMembers(Long projectId) {
        return memberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId));
    }

    @CacheEvict(value = "projectMember", allEntries = true)
    @Transactional
    public ProjectMember addMember(Long projectId, Long userId, Integer role) {
        if (isMember(projectId, userId)) {
            throw new BusinessException("该用户已是项目成员");
        }
        ProjectMember member = ProjectMember.builder()
                .projectId(projectId)
                .userId(userId)
                .role(role)
                .build();
        memberMapper.insert(member);
        permissionMonitorService.incrementPermissionVersion();
        return member;
    }

    @CacheEvict(value = "projectMember", allEntries = true)
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        int deleted = memberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId));
        if (deleted > 0) permissionMonitorService.incrementPermissionVersion();
    }

    private void validateProjectMediaUrls(Project project) {
        if (project == null) {
            return;
        }
        rejectDataUrl(project.getCoverUrl(), "coverUrl");
        rejectDataUrl(project.getArtStyleImageUrl(), "artStyleImageUrl");
    }

    private void applyCurrentTeamOwnership(Project project) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            return;
        }
        TeamService.OwnerScope ownerScope = teamService.getRequiredCurrentOwnerScopeByUser(currentUserId);
        project.setOwnerType(ownerScope.getOwnerType());
        project.setOwnerId(ownerScope.getOwnerId());
    }

    private void rejectDataUrl(String rawUrl, String fieldName) {
        if (StrUtil.isNotBlank(rawUrl) && StrUtil.startWithIgnoreCase(rawUrl.trim(), "data:")) {
            throw new BusinessException(fieldName + " 不支持 base64，请先调用 /api/storage/upload 上传二进制文件");
        }
    }
}
