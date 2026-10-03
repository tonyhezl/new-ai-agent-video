package com.tonyhe.fusion.service.project;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.project.ProjectMemberMapper;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.service.system.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTests {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper memberMapper;

    @Mock
    private AssetMapper assetMapper;

    @Mock
    private AssetItemMapper assetItemMapper;

    @Mock
    private TeamService teamService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ProjectService projectService;

    @Test
    void createRejectsBase64ProjectImages() {
        Project project = Project.builder()
                .name("测试项目")
                .coverUrl(dataUrl("image/png"))
                .artStyleImageUrl(dataUrl("image/jpeg"))
                .build();

        assertThatThrownBy(() -> projectService.create(project))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("coverUrl 不支持 base64");
    }

    @Test
    void updateKeepsNormalImageUrlsUntouched() {
        when(projectMapper.selectById(5L)).thenReturn(Project.builder().id(5L).build());

        Project project = Project.builder()
                .id(5L)
                .coverUrl("https://example.com/project-cover.png")
                .artStyleImageUrl("/media/images/style.png")
                .build();

        projectService.update(project);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getCoverUrl()).isEqualTo("https://example.com/project-cover.png");
        assertThat(projectCaptor.getValue().getArtStyleImageUrl()).isEqualTo("/media/images/style.png");
    }

    @Test
    void listAccessibleByUserUsesCurrentTeamScope() {
        when(userService.isAdmin(9L)).thenReturn(false);
        when(teamService.getCurrentTeamIdByUser(9L)).thenReturn(5L);
        when(projectMapper.selectList(any())).thenReturn(java.util.List.of());

        projectService.listAccessibleByUser(9L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Project>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(projectMapper).selectList(wrapperCaptor.capture());
        verify(teamService).getCurrentTeamIdByUser(9L);
    }

    @Test
    void canAccessProjectDoesNotExposeAnotherUsersPersonalProjectThroughTeam() {
        when(projectMapper.selectById(11L)).thenReturn(Project.builder()
                .id(11L)
                .ownerType(1)
                .ownerId(10L)
                .build());
        when(memberMapper.exists(any())).thenReturn(false);
        when(teamService.getCurrentTeamIdByUser(9L)).thenReturn(5L);

        assertThat(projectService.canAccessProject(11L, 9L)).isFalse();
    }

    private static String dataUrl(String mimeType) {
        return "data:" + mimeType + ";base64,dGVzdA==";
    }
}
