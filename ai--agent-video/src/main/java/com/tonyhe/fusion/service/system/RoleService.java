package com.tonyhe.fusion.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.mapper.system.RoleMapper;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import com.tonyhe.fusion.mapper.system.RoleMenuMapper;
import com.tonyhe.fusion.mapper.system.UserRoleMapper;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.entity.system.RoleMenu;
import com.tonyhe.fusion.entity.system.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleAssignmentMapper roleAssignmentMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final PermissionMonitorService permissionMonitorService;

    @Transactional
    @CacheEvict(value = {"userRoles", "userPermissions", "userMenus"}, allEntries = true)
    public Role createRole(String name, String code, Integer sort, String remark) {
        boolean exists = roleMapper.exists(new LambdaQueryWrapper<Role>().eq(Role::getCode, code));
        if (exists) {
            throw new BusinessException(400, "角色编码已存在");
        }
        Role role = Role.builder()
                .name(name).code(code).sort(sort != null ? sort : 0).status(1).remark(remark)
                .build();
        roleMapper.insert(role);
        permissionMonitorService.incrementPermissionVersion();
        return role;
    }

    @Transactional
    @CacheEvict(value = {"userRoles", "userPermissions", "userMenus"}, allEntries = true)
    public void updateRole(Long id, String name, Integer sort, Integer status, String remark) {
        Role role = roleMapper.selectById(id);
        if (role == null) throw new BusinessException(404, "角色不存在");
        if (name != null) role.setName(name);
        if (sort != null) role.setSort(sort);
        if (status != null) role.setStatus(status);
        if (remark != null) role.setRemark(remark);
        roleMapper.updateById(role);
        permissionMonitorService.incrementPermissionVersion();
    }

    @Transactional
    @CacheEvict(value = {"userRoles", "userPermissions", "userMenus"}, allEntries = true)
    public void deleteRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) throw new BusinessException(404, "角色不存在");
        if ("admin".equals(role.getCode()) || "user".equals(role.getCode())) {
            throw new BusinessException(400, "内置角色不允许删除");
        }
        boolean assigned = userRoleMapper.exists(new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id))
                || roleAssignmentMapper.exists(new LambdaQueryWrapper<RoleAssignment>()
                .eq(RoleAssignment::getRoleId, id));
        if (assigned) {
            throw new BusinessException(400, "角色仍分配给用户，请先撤销相关授权");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRoleId, id));
        roleMapper.deleteById(id);
        permissionMonitorService.incrementPermissionVersion();
    }

    public Role getById(Long id) {
        return roleMapper.selectById(id);
    }

    public List<Role> getEnabledRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().eq(Role::getStatus, 1));
    }

    public List<Role> getAllRoles() {
        return roleMapper.selectList(null);
    }
}
