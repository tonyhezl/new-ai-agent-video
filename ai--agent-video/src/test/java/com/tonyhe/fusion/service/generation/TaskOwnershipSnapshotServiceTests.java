package com.tonyhe.fusion.service.generation;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import com.tonyhe.fusion.service.team.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskOwnershipSnapshotServiceTests {

    @Mock private ProjectMapper projectMapper;
    @Mock private TeamMapper teamMapper;
    @Mock private TeamService teamService;
    @InjectMocks private TaskOwnershipSnapshotService service;

    @Test
    void teamProjectUsesProjectOwnerInsteadOfCallersCurrentTeam() {
        when(projectMapper.selectById(100L))
                .thenReturn(Project.builder().id(100L).ownerType(2).ownerId(20L).build());

        assertThat(service.resolveTeamId(7L, 100L, 99L)).isEqualTo(20L);
        verifyNoInteractions(teamService);
    }

    @Test
    void personalTaskFallsBackToRequestedThenCurrentTeam() {
        assertThat(service.resolveTeamId(7L, null, 20L)).isEqualTo(20L);
        when(teamService.getCurrentTeamIdByUser(7L)).thenReturn(30L);
        assertThat(service.resolveTeamId(7L, null, null)).isEqualTo(30L);
    }

    @Test
    void deletedProjectOrTeamRejectsResultWrite() {
        when(projectMapper.selectById(100L)).thenReturn(null);
        assertThatThrownBy(() -> service.assertParentActive(100L, 20L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("项目已删除");

        when(projectMapper.selectById(100L)).thenReturn(Project.builder().id(100L).build());
        when(teamMapper.selectById(20L)).thenReturn(null);
        assertThatThrownBy(() -> service.assertParentActive(100L, 20L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("团队已删除");
    }

    @Test
    void activeParentsAllowResultWrite() {
        when(projectMapper.selectById(100L)).thenReturn(Project.builder().id(100L).build());
        when(teamMapper.selectById(20L)).thenReturn(Team.builder().id(20L).build());
        assertThatCode(() -> service.assertParentActive(100L, 20L)).doesNotThrowAnyException();
    }
}
