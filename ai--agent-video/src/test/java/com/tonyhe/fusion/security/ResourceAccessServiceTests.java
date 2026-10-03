package com.tonyhe.fusion.security;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AgentConversation;
import com.tonyhe.fusion.entity.asset.Asset;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAccessServiceTests {

    @Mock
    private UserService userService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScriptService scriptService;
    @Mock
    private StoryboardService storyboardService;
    @Mock
    private AssetService assetService;
    @Mock
    private AgentConversationService conversationService;
    @Mock
    private ImageGenerationService imageGenerationService;
    @Mock
    private VideoGenerationService videoGenerationService;
    @Mock
    private AudioGenerationService audioGenerationService;
    @Mock
    private TeamService teamService;
    @Mock
    private PovStoryMapper povStoryMapper;
    @Mock
    private AuthorizationService authorizationService;
    @Mock
    private PermissionMonitorService permissionMonitorService;

    @InjectMocks
    private ResourceAccessService resourceAccessService;

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long NORMAL_USER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;

    // ========== Project access ==========

    @Test
    void adminBypassesProjectAccessCheck() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkProjectAccess(100L, ADMIN_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void normalUserWithAccessPassesProjectCheck() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkProjectAccess(100L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void normalUserWithoutAccessThrows403() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(OTHER_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> resourceAccessService.checkProjectAccess(100L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该项目");
    }

    // ========== Script access (chain to project) ==========

    @Test
    void scriptAccessChainsToProject() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Script script = new Script();
        script.setId(10L);
        script.setProjectId(100L);
        when(scriptService.getById(10L)).thenReturn(script);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkScriptAccess(10L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void scriptAccessDeniedWhenProjectInaccessible() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Script script = new Script();
        script.setId(10L);
        script.setProjectId(100L);
        when(scriptService.getById(10L)).thenReturn(script);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(OTHER_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> resourceAccessService.checkScriptAccess(10L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该项目");
    }

    // ========== Storyboard access (chain to project) ==========

    @Test
    void storyboardAccessChainsToProject() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Storyboard storyboard = Storyboard.builder().id(20L).projectId(100L).build();
        when(storyboardService.getById(20L)).thenReturn(storyboard);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkStoryboardAccess(20L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void storyboardItemAccessChainsToStoryboard() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        StoryboardItem item = StoryboardItem.builder().id(30L).storyboardId(20L).build();
        when(storyboardService.getItemById(30L)).thenReturn(item);
        Storyboard storyboard = Storyboard.builder().id(20L).projectId(100L).build();
        when(storyboardService.getById(20L)).thenReturn(storyboard);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkStoryboardItemAccess(30L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    // ========== Asset access ==========

    @Test
    void assetAccessDeniedForNonOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Asset asset = Asset.builder().id(40L).userId(OTHER_USER_ID).build();
        when(assetService.getById(40L)).thenReturn(asset);
        when(assetService.canAccessAsset(asset, NORMAL_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> resourceAccessService.checkAssetAccess(40L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该资产");
    }

    @Test
    void adminBypassesAssetAccessCheck() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkAssetAccess(40L, ADMIN_USER_ID))
                .doesNotThrowAnyException();
    }

    // ========== Conversation access ==========

    @Test
    void conversationAccessDeniedForNonOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        AgentConversation conv = AgentConversation.builder()
                .id(1L).conversationId("conv-123").userId(OTHER_USER_ID).build();
        when(conversationService.getByConversationId("conv-123")).thenReturn(conv);

        assertThatThrownBy(() -> resourceAccessService.checkConversationAccess("conv-123", NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该对话");
    }

    @Test
    void conversationAccessAllowedForOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        AgentConversation conv = AgentConversation.builder()
                .id(1L).conversationId("conv-123").userId(NORMAL_USER_ID).build();
        when(conversationService.getByConversationId("conv-123")).thenReturn(conv);

        assertThatCode(() -> resourceAccessService.checkConversationAccess("conv-123", NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void conversationAccessThrows404WhenNotFound() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(conversationService.getByConversationId("missing")).thenReturn(null);

        assertThatThrownBy(() -> resourceAccessService.checkConversationAccess("missing", NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对话不存在");
    }

    // ========== Image task access ==========

    @Test
    void imageTaskAccessDeniedForNonOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        ImageTask task = ImageTask.builder().taskId("task-1").userId(OTHER_USER_ID).build();
        when(imageGenerationService.getByTaskId("task-1")).thenReturn(task);

        assertThatThrownBy(() -> resourceAccessService.checkImageTaskAccess("task-1", NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该生图任务");
    }

    @Test
    void imageTaskAccessAllowedForOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        ImageTask task = ImageTask.builder().taskId("task-1").userId(NORMAL_USER_ID).build();
        when(imageGenerationService.getByTaskId("task-1")).thenReturn(task);

        assertThatCode(() -> resourceAccessService.checkImageTaskAccess("task-1", NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    // ========== Video task access ==========

    @Test
    void videoTaskAccessDeniedForNonOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        VideoTask task = VideoTask.builder().taskId("vtask-1").userId(OTHER_USER_ID).build();
        when(videoGenerationService.getByTaskId("vtask-1")).thenReturn(task);

        assertThatThrownBy(() -> resourceAccessService.checkVideoTaskAccess("vtask-1", NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该生视频任务");
    }

    // ========== Team access ==========

    @Test
    void teamMemberAccessAllowedForMember() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.isUserInTeam(1L, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkTeamMember(1L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void teamMemberAccessDeniedForNonMember() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.isUserInTeam(1L, NORMAL_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> resourceAccessService.checkTeamMember(1L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该团队");
    }

    @Test
    void teamManagementAllowedForOwner() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.getTeamRole(1L, NORMAL_USER_ID)).thenReturn(TeamMemberRoleEnum.OWNER.getRole());

        assertThatCode(() -> resourceAccessService.checkTeamManagement(1L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void teamManagementDeniedForRegularMember() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.getTeamRole(1L, NORMAL_USER_ID)).thenReturn(TeamMemberRoleEnum.MEMBER.getRole());

        assertThatThrownBy(() -> resourceAccessService.checkTeamManagement(1L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需要团队管理员权限");
    }

    @Test
    void adminBypassesTeamManagement() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkTeamManagement(1L, ADMIN_USER_ID))
                .doesNotThrowAnyException();
    }

    // ========== POV Story access ==========

    @Test
    void adminBypassesPovStoryAccessCheck() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkPovStoryAccess(50L, ADMIN_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void povStoryOwnerAccessAllowed() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        PovStory story = PovStory.builder().id(50L).userId(NORMAL_USER_ID).build();
        when(povStoryMapper.selectById(50L)).thenReturn(story);

        assertThatCode(() -> resourceAccessService.checkPovStoryAccess(50L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void povStoryAccessDeniedForNonOwnerWithoutProject() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        PovStory story = PovStory.builder().id(50L).userId(OTHER_USER_ID).projectId(null).build();
        when(povStoryMapper.selectById(50L)).thenReturn(story);

        assertThatThrownBy(() -> resourceAccessService.checkPovStoryAccess(50L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该故事");
    }

    @Test
    void povStoryAccessInheritsProjectAccess() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        PovStory story = PovStory.builder().id(50L).userId(OTHER_USER_ID).projectId(100L).visibility("PROJECT").build();
        when(povStoryMapper.selectById(50L)).thenReturn(story);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkPovStoryAccess(50L, NORMAL_USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void povStoryAccessThrows404WhenNotFound() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(povStoryMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> resourceAccessService.checkPovStoryAccess(999L, NORMAL_USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("故事不存在");
    }

    @Test
    void povStoryAccessWithEditActionChainsToProject() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        PovStory story = PovStory.builder().id(50L).userId(OTHER_USER_ID).projectId(100L).visibility("PROJECT").build();
        when(povStoryMapper.selectById(50L)).thenReturn(story);
        Project project = Project.builder().id(100L).ownerType(1).ownerId(NORMAL_USER_ID).build();
        when(projectService.getById(100L)).thenReturn(project);
        when(projectService.canAccessProject(project, NORMAL_USER_ID)).thenReturn(true);

        assertThatCode(() -> resourceAccessService.checkPovStoryAccess(50L, NORMAL_USER_ID, AccessAction.EDIT))
                .doesNotThrowAnyException();
    }
}
