package com.tonyhe.fusion.service.project;

import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.project.ProjectMemberMapper;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceDataIsolationTests {

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

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long NORMAL_USER_ID = 2L;

    @Test
    void adminSeesAllProjects() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);
        Project p1 = Project.builder().id(1L).name("Project A").build();
        Project p2 = Project.builder().id(2L).name("Project B").build();
        when(projectMapper.selectList(any())).thenReturn(List.of(p1, p2));

        List<Project> result = projectService.listAccessibleByUser(ADMIN_USER_ID);

        assertThat(result).hasSize(2);
        verify(teamService, org.mockito.Mockito.never()).getCurrentTeamIdByUser(ADMIN_USER_ID);
    }

    @Test
    void normalUserWithoutTeamSeesOnlyOwnProjects() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.getCurrentTeamIdByUser(NORMAL_USER_ID)).thenReturn(null);
        Project ownProject = Project.builder().id(1L).name("My Project").build();
        when(projectMapper.selectList(any())).thenReturn(List.of(ownProject));

        List<Project> result = projectService.listAccessibleByUser(NORMAL_USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("My Project");
    }

    @Test
    void normalUserWithTeamSeesTeamAndMemberProjects() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(teamService.getCurrentTeamIdByUser(NORMAL_USER_ID)).thenReturn(5L);
        Project teamProject = Project.builder().id(1L).name("Team Project").build();
        Project memberProject = Project.builder().id(2L).name("Member Project").build();
        when(projectMapper.selectList(any())).thenReturn(List.of(teamProject, memberProject));

        List<Project> result = projectService.listAccessibleByUser(NORMAL_USER_ID);

        assertThat(result).hasSize(2);
    }
}
