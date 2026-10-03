package com.tonyhe.fusion.service.system;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import com.tonyhe.fusion.mapper.system.RoleMapper;
import com.tonyhe.fusion.mapper.system.RoleMenuMapper;
import com.tonyhe.fusion.mapper.system.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceTests {

    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleAssignmentMapper roleAssignmentMapper;
    @Mock private RoleMenuMapper roleMenuMapper;
    @Mock private PermissionMonitorService permissionMonitorService;
    @InjectMocks private RoleService service;

    @Test
    void builtInRolesCannotBeDeleted() {
        when(roleMapper.selectById(1L)).thenReturn(Role.builder().id(1L).code("admin").build());
        assertThatThrownBy(() -> service.deleteRole(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内置角色");
        verify(roleMapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    void assignedRoleCannotBeDeleted() {
        when(roleMapper.selectById(3L)).thenReturn(Role.builder().id(3L).code("editor").build());
        when(userRoleMapper.exists(any())).thenReturn(true);
        assertThatThrownBy(() -> service.deleteRole(3L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仍分配给用户");
        verify(roleMapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    void unassignedCustomRoleDeletesMenusAndRole() {
        when(roleMapper.selectById(3L)).thenReturn(Role.builder().id(3L).code("editor").build());
        when(userRoleMapper.exists(any())).thenReturn(false);
        when(roleAssignmentMapper.exists(any())).thenReturn(false);

        service.deleteRole(3L);

        verify(roleMenuMapper).delete(any());
        verify(roleMapper).deleteById(3L);
        verify(permissionMonitorService).incrementPermissionVersion();
    }
}
