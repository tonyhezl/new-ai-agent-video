package com.tonyhe.fusion.security;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AgentConversation;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.script.Script;
import com.tonyhe.fusion.entity.script.ScriptEpisode;
import com.tonyhe.fusion.entity.script.ScriptSceneItem;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.entity.pov.PovStory;
import com.tonyhe.fusion.entity.team.TeamMember;
import com.tonyhe.fusion.enums.team.TeamMemberRoleEnum;
import com.tonyhe.fusion.mapper.pov.PovStoryMapper;
import com.tonyhe.fusion.service.ai.AgentConversationService;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.script.ScriptService;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.entity.project.ProjectMember;
import com.tonyhe.fusion.enums.project.ProjectMemberRoleEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


/**
 * 统一资源访问校验服务（RBAC + 资源级 ABAC）
 * <p>
 * 所有校验方法在访问被拒绝时抛出 BusinessException(403)。
 * Admin 用户自动跳过所有资源级检查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceAccessService {

    private final UserService userService;
    private final ProjectService projectService;
    private final ScriptService scriptService;
    private final StoryboardService storyboardService;
    private final AssetService assetService;
    private final AgentConversationService conversationService;
    private final ImageGenerationService imageGenerationService;
    private final VideoGenerationService videoGenerationService;
    private final AudioGenerationService audioGenerationService;
    private final TeamService teamService;
    private final PovStoryMapper povStoryMapper;
    private final AuthorizationService authorizationService;
    private final PermissionMonitorService permissionMonitorService;

    // ========== 团队 ==========

    /**
     * 校验用户是否为团队成员
     */
    public void checkTeamMember(Long teamId, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.TEAM_VIEW);
        if (!teamService.isUserInTeam(teamId, userId)) {
            logAccessDenied("team", teamId, userId, AccessAction.VIEW);
            throw new BusinessException(403, "无权访问该团队");
        }
    }

    /**
     * 校验用户是否具有团队管理权限（OWNER 或 ADMIN 角色）
     */
    public void checkTeamManagement(Long teamId, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.TEAM_MEMBER_MANAGE);
        Integer role = teamService.getTeamRole(teamId, userId);
        if (!TeamMemberRoleEnum.OWNER.getRole().equals(role)
                && !TeamMemberRoleEnum.ADMIN.getRole().equals(role)) {
            logAccessDenied("team-management", teamId, userId, AccessAction.MANAGE);
            throw new BusinessException(403, "需要团队管理员权限");
        }
    }

    // ========== 项目 ==========

    /**
     * 校验用户对项目的访问权限
     */
    public void checkProjectAccess(Long projectId, Long userId) {
        checkProjectAccess(projectId, userId, AccessAction.VIEW);
    }

    /**
     * 校验用户对项目的访问权限（带动作粒度）
     * <p>
     * VIEW: 只要有项目访问权即可。
     * EDIT/DELETE/MANAGE: 需要项目成员角色达到对应级别，
     * 团队角色映射为项目默认角色，项目显式角色优先。
     */
    public void checkProjectAccess(Long projectId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        String permissionCode = switch (action) {
            case VIEW -> PermissionCodes.PROJECT_VIEW;
            case EDIT -> PermissionCodes.PROJECT_UPDATE;
            case DELETE -> PermissionCodes.PROJECT_DELETE;
            case MANAGE -> PermissionCodes.PROJECT_MEMBER_MANAGE;
        };
        authorizationService.requireProjectPermission(userId, projectId, permissionCode);
        checkProjectRoleAccess(projectId, userId, action);
    }

    /**
     * 校验项目内业务模块的功能权限和项目成员动作权限。
     */
    public void checkProjectContentAccess(Long projectId, Long userId, AccessAction action,
                                          String permissionCode) {
        if (isAdmin(userId)) return;
        authorizationService.requireProjectPermission(userId, projectId, permissionCode);
        checkProjectRoleAccess(projectId, userId, action);
    }

    private void checkProjectRoleAccess(Long projectId, Long userId, AccessAction action) {
        Project project = projectService.getById(projectId);
        if (!projectService.canAccessProject(project, userId)) {
            logAccessDenied("project", projectId, userId, action);
            throw new BusinessException(403, "无权访问该项目");
        }
        if (action == AccessAction.VIEW) return;

        // 个人项目拥有者拥有全部权限
        if (project.getOwnerType() != null && project.getOwnerType() == 1
                && userId.equals(project.getOwnerId())) {
            return;
        }

        // 查找项目显式成员角色
        ProjectMemberRoleEnum role = ProjectMemberRoleEnum.fromRole(
                projectService.getMemberRole(projectId, userId));
        // 项目显式角色优先；无显式角色时回退到团队角色映射
        if (role == null && Integer.valueOf(2).equals(project.getOwnerType())) {
            Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
            if (currentTeamId != null && currentTeamId.equals(project.getOwnerId())) {
                Integer teamRole = teamService.getTeamRole(currentTeamId, userId);
                role = ProjectMemberRoleEnum.fromTeamRole(teamRole);
            }
        }
        if (role == null) {
            role = ProjectMemberRoleEnum.VIEWER;
        }
        if (!role.can(action)) {
            logAccessDenied("project", projectId, userId, action);
            throw new BusinessException(403, "项目角色不足，需要 " + action + " 权限");
        }
    }

    // ========== 剧本 ==========

    /**
     * 校验用户对剧本的访问权限（沿 projectId 链路）
     */
    public void checkScriptAccess(Long scriptId, Long userId) {
        checkScriptAccess(scriptId, userId, AccessAction.VIEW);
    }

    /**
     * 校验用户对剧本的访问权限（带动作粒度）
     */
    public void checkScriptAccess(Long scriptId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        Script script = scriptService.getById(scriptId);
        authorizationService.requireProjectPermission(userId, script.getProjectId(),
                action == AccessAction.VIEW ? PermissionCodes.SCRIPT_VIEW : PermissionCodes.SCRIPT_EDIT);
        checkProjectRoleAccess(script.getProjectId(), userId, action);
    }

    /**
     * 校验用户对剧本分集的访问权限（沿 scriptId → projectId 链路）
     */
    public void checkScriptEpisodeAccess(Long episodeId, Long userId) {
        checkScriptEpisodeAccess(episodeId, userId, AccessAction.VIEW);
    }

    public void checkScriptEpisodeAccess(Long episodeId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        ScriptEpisode episode = scriptService.getEpisodeById(episodeId);
        checkScriptAccess(episode.getScriptId(), userId, action);
    }

    /**
     * 校验用户对剧本场次的访问权限（沿 scriptId → projectId 链路）
     */
    public void checkScriptSceneAccess(Long sceneId, Long userId) {
        checkScriptSceneAccess(sceneId, userId, AccessAction.VIEW);
    }

    public void checkScriptSceneAccess(Long sceneId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        ScriptSceneItem scene = scriptService.getSceneById(sceneId);
        if (scene.getScriptId() != null) {
            checkScriptAccess(scene.getScriptId(), userId, action);
        } else if (scene.getEpisodeId() != null) {
            checkScriptEpisodeAccess(scene.getEpisodeId(), userId, action);
        } else {
            throw new BusinessException(403, "无权访问该剧本场次");
        }
    }

    // ========== 分镜 ==========

    /**
     * 校验用户对分镜的访问权限（沿 projectId 链路）
     */
    public void checkStoryboardAccess(Long storyboardId, Long userId) {
        checkStoryboardAccess(storyboardId, userId, AccessAction.VIEW);
    }

    /**
     * 校验用户对分镜的访问权限（带动作粒度）
     */
    public void checkStoryboardAccess(Long storyboardId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        Storyboard storyboard = storyboardService.getById(storyboardId);
        authorizationService.requireProjectPermission(userId, storyboard.getProjectId(),
                action == AccessAction.VIEW ? PermissionCodes.STORYBOARD_VIEW : PermissionCodes.STORYBOARD_EDIT);
        checkProjectRoleAccess(storyboard.getProjectId(), userId, action);
    }

    public void checkStoryboardGenerateAccess(Long storyboardId, Long userId) {
        if (isAdmin(userId)) return;
        Storyboard storyboard = storyboardService.getById(storyboardId);
        authorizationService.requireProjectPermission(userId, storyboard.getProjectId(),
                PermissionCodes.STORYBOARD_GENERATE);
        checkProjectRoleAccess(storyboard.getProjectId(), userId, AccessAction.EDIT);
    }

    public void checkStoryboardEpisodeGenerateAccess(Long episodeId, Long userId) {
        StoryboardEpisode episode = storyboardService.getEpisodeById(episodeId);
        checkStoryboardGenerateAccess(episode.getStoryboardId(), userId);
    }

    /**
     * 校验用户对分镜集的访问权限（沿 storyboardId → projectId 链路）
     */
    public void checkStoryboardEpisodeAccess(Long episodeId, Long userId) {
        checkStoryboardEpisodeAccess(episodeId, userId, AccessAction.VIEW);
    }

    public void checkStoryboardEpisodeAccess(Long episodeId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        StoryboardEpisode episode = storyboardService.getEpisodeById(episodeId);
        checkStoryboardAccess(episode.getStoryboardId(), userId, action);
    }

    /**
     * 校验用户对分镜场次的访问权限（沿 storyboardId → projectId 链路）
     */
    public void checkStoryboardSceneAccess(Long sceneId, Long userId) {
        checkStoryboardSceneAccess(sceneId, userId, AccessAction.VIEW);
    }

    public void checkStoryboardSceneAccess(Long sceneId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        StoryboardScene scene = storyboardService.getSceneById(sceneId);
        if (scene.getStoryboardId() != null) {
            checkStoryboardAccess(scene.getStoryboardId(), userId, action);
        } else if (scene.getEpisodeId() != null) {
            checkStoryboardEpisodeAccess(scene.getEpisodeId(), userId, action);
        } else {
            throw new BusinessException(403, "无权访问该分镜场次");
        }
    }

    /**
     * 校验用户对分镜条目的访问权限（沿 storyboardId → projectId 链路）
     */
    public void checkStoryboardItemAccess(Long itemId, Long userId) {
        checkStoryboardItemAccess(itemId, userId, AccessAction.VIEW);
    }

    public void checkStoryboardItemAccess(Long itemId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        StoryboardItem item = storyboardService.getItemById(itemId);
        checkStoryboardAccess(item.getStoryboardId(), userId, action);
    }

    // ========== 资产 ==========

    /**
     * 校验用户对资产的访问权限
     */
    public void checkAssetAccess(Long assetId, Long userId) {
        checkAssetAccess(assetId, userId, AccessAction.VIEW);
    }

    /**
     * 校验用户对资产的访问权限（带动作粒度）
     */
    public void checkAssetAccess(Long assetId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        Asset asset = assetService.getById(assetId);
        String permissionCode = switch (action) {
            case VIEW -> PermissionCodes.ASSET_VIEW;
            case EDIT, MANAGE -> PermissionCodes.ASSET_EDIT;
            case DELETE -> PermissionCodes.ASSET_DELETE;
        };
        if (asset.getProjectId() != null) {
            authorizationService.requireProjectPermission(userId, asset.getProjectId(), permissionCode);
            checkProjectRoleAccess(asset.getProjectId(), userId, action);
        } else {
            authorizationService.requirePermission(userId, permissionCode);
        }
        if (!assetService.canAccessAsset(asset, userId)) {
            logAccessDenied("asset", assetId, userId, action);
            throw new BusinessException(403, "无权访问该资产");
        }
    }

    // ========== AI 对话 ==========

    /**
     * 校验用户对对话的访问权限（按 conversationId UUID）
     */
    public void checkConversationAccess(String conversationId, Long userId) {
        if (isAdmin(userId)) return;
        AgentConversation conv = conversationService.getByConversationId(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "对话不存在");
        }
        if (!userId.equals(conv.getUserId())) {
            logAccessDenied("conversation", conversationId, userId, AccessAction.VIEW);
            throw new BusinessException(403, "无权访问该对话");
        }
    }

    /**
     * 校验用户对对话的访问权限（按主键 ID）
     */
    public void checkConversationAccessById(Long id, Long userId) {
        if (isAdmin(userId)) return;
        AgentConversation conv = conversationService.getById(id);
        if (conv == null) {
            throw new BusinessException(404, "对话不存在");
        }
        if (!userId.equals(conv.getUserId())) {
            throw new BusinessException(403, "无权访问该对话");
        }
    }

    // ========== 生成任务 ==========

    /**
     * 校验用户对生图任务的访问权限
     */
    public void checkImageTaskAccess(String taskId, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_VIEW);
        ImageTask task = imageGenerationService.getByTaskId(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            logAccessDenied("image-task", taskId, userId, AccessAction.VIEW);
            throw new BusinessException(403, "无权访问该生图任务");
        }
    }

    /**
     * 校验用户对生图任务的访问权限（按主键 ID）
     */
    public void checkImageTaskAccessById(Long id, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_VIEW);
        ImageTask task = imageGenerationService.getById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(403, "无权访问该生图任务");
        }
    }

    /**
     * 校验用户对生视频任务的访问权限
     */
    public void checkVideoTaskAccess(String taskId, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_VIEW);
        VideoTask task = videoGenerationService.getByTaskId(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            logAccessDenied("video-task", taskId, userId, AccessAction.VIEW);
            throw new BusinessException(403, "无权访问该生视频任务");
        }
    }

    /**
     * 校验用户对生视频任务的访问权限（按主键 ID）
     */
    public void checkVideoTaskAccessById(Long id, Long userId) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_VIEW);
        VideoTask task = videoGenerationService.getById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(403, "无权访问该生视频任务");
        }
    }

    // ========== 配音任务 ==========

    /**
     * 校验用户对配音任务的访问权限
     */
    public void checkAudioTaskAccess(Long audioTaskId, Long userId) {
        checkAudioTaskAccess(audioTaskId, userId, AccessAction.VIEW);
    }

    public void checkAudioTaskAccess(Long audioTaskId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        authorizationService.requirePermission(userId,
                action == AccessAction.VIEW ? PermissionCodes.GENERATION_VIEW : PermissionCodes.GENERATION_CREATE);
        AudioTask task = audioGenerationService.getById(audioTaskId);
        if (task == null) {
            throw new BusinessException(404, "配音任务不存在");
        }
        if (userId.equals(task.getUserId())) {
            return;
        }
        if (task.getProjectId() != null) {
            checkProjectRoleAccess(task.getProjectId(), userId, action);
            return;
        }
        if (!userId.equals(task.getUserId())) {
            logAccessDenied("audio-task", audioTaskId, userId, AccessAction.VIEW);
            throw new BusinessException(403, "无权访问该配音任务");
        }
    }

    // ========== POV 故事 ==========

    /**
     * 校验用户对 POV 故事的访问权限。
     * <p>
     * 未绑定项目时仅创建者可见；绑定项目后继承项目成员权限。Admin 自动跳过。
     */
    public void checkPovStoryAccess(Long storyId, Long userId) {
        checkPovStoryAccess(storyId, userId, AccessAction.VIEW);
    }

    /**
     * 校验用户对 POV 故事的访问权限（带动作粒度）
     */
    public void checkPovStoryAccess(Long storyId, Long userId, AccessAction action) {
        if (isAdmin(userId)) return;
        PovStory story = povStoryMapper.selectById(storyId);
        if (story == null) {
            throw new BusinessException(404, "故事不存在: " + storyId);
        }
        if (userId.equals(story.getUserId())) {
            return;
        }
        String visibility = story.getVisibility() == null ? "PRIVATE" : story.getVisibility();
        if ("PRIVATE".equals(visibility) || story.getProjectId() == null) {
            logAccessDenied("pov-story", storyId, userId, action);
            throw new BusinessException(403, "无权访问该故事");
        }
        authorizationService.requireProjectPermission(userId, story.getProjectId(),
                action == AccessAction.VIEW ? PermissionCodes.POV_STORY_VIEW : PermissionCodes.POV_STORY_EDIT);
        if ("TEAM".equals(visibility)) {
            Project project = projectService.getById(story.getProjectId());
            Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
            if (project.getOwnerType() == null || project.getOwnerType() != 2
                    || currentTeamId == null || !currentTeamId.equals(project.getOwnerId())) {
                logAccessDenied("pov-story", storyId, userId, action);
                throw new BusinessException(403, "无权访问该团队故事");
            }
        }
        if ("PROJECT".equals(visibility) || "TEAM".equals(visibility)) {
            checkProjectRoleAccess(story.getProjectId(), userId, action);
            return;
        }
        logAccessDenied("pov-story", storyId, userId, action);
        throw new BusinessException(403, "无权访问该故事");
    }

    // ========== 内部方法 ==========

    private boolean isAdmin(Long userId) {
        return userService.isAdmin(userId);
    }

    private void logAccessDenied(String resourceType, Object resourceId, Long userId, AccessAction action) {
        permissionMonitorService.recordDenied();
        log.warn("[AccessDenied] resource={}, id={}, userId={}, action={}",
                resourceType, resourceId, userId, action);
    }
}
