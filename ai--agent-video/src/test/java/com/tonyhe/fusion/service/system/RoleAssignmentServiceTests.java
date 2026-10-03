package com.tonyhe.fusion.service.system;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import com.tonyhe.fusion.mapper.system.RoleMapper;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleAssignmentServiceTests {

    @Mock private RoleAssignmentMapper roleAssignmentMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private TeamMapper teamMapper;
    @Mock private PermissionMonitorService permissionMonitorService;

    @InjectMocks private RoleAssignmentService service;

    @Test
    void systemScopeRequiresAndAcceptsNullScopeId() {
        validUserAndRole();
        when(roleAssignmentMapper.exists(any())).thenReturn(false);

        RoleAssignment result = service.assign(1L, 2L, "SYSTEM", null, 9L, null);

        assertThat(result.getScopeType()).isEqualTo("SYSTEM");
        assertThat(result.getScopeId()).isNull();
        verify(roleAssignmentMapper).insert(result);
        verify(permissionMonitorService).incrementPermissionVersion();
    }

    @Test
    void systemScopeRejectsNonNullScopeId() {
        validUserAndRole();
        assertThatThrownBy(() -> service.assign(1L, 2L, "SYSTEM", 10L, 9L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能填写 scopeId");
    }

    @Test
    void projectScopeValidatesTheExactProject() {
        validUserAndRole();
        when(projectMapper.selectById(100L)).thenReturn(Project.builder().id(100L).build());
        when(roleAssignmentMapper.exists(any())).thenReturn(false);

        RoleAssignment result = service.assign(1L, 2L, "PROJECT", 100L, 9L, null);
        assertThat(result.getScopeId()).isEqualTo(100L);

        when(projectMapper.selectById(200L)).thenReturn(null);
        assertThatThrownBy(() -> service.assign(1L, 2L, "PROJECT", 200L, 9L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("项目不存在");
    }

    @Test
    void teamScopeValidatesTeamAndExpiredAssignmentIsRejected() {
        validUserAndRole();
        when(teamMapper.selectById(20L)).thenReturn(Team.builder().id(20L).build());

        assertThatThrownBy(() -> service.assign(1L, 2L, "TEAM", 20L, 9L,
                LocalDateTime.now().minusMinutes(1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("过期时间");
    }

    private void validUserAndRole() {
        when(userMapper.selectById(1L)).thenReturn(User.builder().id(1L).build());
        when(roleMapper.selectById(2L)).thenReturn(Role.builder().id(2L).status(1).build());
    }
}
