package com.tonyhe.fusion.service.system;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.system.Menu;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.entity.system.RoleMenu;
import com.tonyhe.fusion.mapper.system.MenuMapper;
import com.tonyhe.fusion.mapper.system.RoleMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单服务
 * <p>
 * 提供菜单树构建、角色菜单绑定和用户可见菜单查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final UserService userService;
    private final RoleAssignmentService roleAssignmentService;
    private final com.tonyhe.fusion.service.team.TeamService teamService;
    private final PermissionMonitorService permissionMonitorService;

    /**
     * 获取所有启用的菜单（管理员用）
     */
    public List<Menu> listAllEnabled() {
        return menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getStatus, 1)
                .orderByAsc(Menu::getSort));
    }

    /**
     * 获取角色绑定的菜单ID列表
     */
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId))
                .stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    /**
     * 绑定角色菜单（全量替换）
     */
    @Transactional
    @CacheEvict(value = {"userPermissions", "userMenus"}, allEntries = true)
    public void assignRoleMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>()
                .eq(RoleMenu::getRoleId, roleId));
        if (menuIds != null) {
            menuIds.stream().distinct().forEach(menuId ->
                    roleMenuMapper.insert(RoleMenu.builder().roleId(roleId).menuId(menuId).build()));
        }
        permissionMonitorService.incrementPermissionVersion();
    }

    /**
     * 批量获取角色绑定的有效权限码，菜单配置是权限计算的唯一事实来源。
     */
    public Set<String> getPermissionCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                .in(RoleMenu::getRoleId, roleIds));
        if (roleMenus.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> menuIds = roleMenus.stream().map(RoleMenu::getMenuId).collect(Collectors.toSet());
        return menuMapper.selectBatchIds(menuIds).stream()
                .filter(menu -> Integer.valueOf(1).equals(menu.getStatus()))
                .map(Menu::getPermissionCode)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    /**
     * 获取用户可见菜单（基于角色绑定）
     */
    @Cacheable(value = "userMenus",
            key = "#userId + ':' + @teamService.getCurrentTeamIdByUser(#userId) + ':' + @permissionMonitorService.getPermissionVersion()",
            condition = "!@userService.isAdmin(#userId)")
    public List<Menu> getUserMenus(Long userId) {
        if (userService.isAdmin(userId)) {
            return listAllEnabled();
        }
        Set<Long> roleIds = userService.getUserRoles(userId).stream()
                .map(Role::getId)
                .collect(Collectors.toSet());
        Long teamId = teamService.getCurrentTeamIdByUser(userId);
        roleAssignmentService.getEffectiveAssignments(userId, "TEAM", teamId).stream()
                .map(RoleAssignment::getRoleId)
                .forEach(roleIds::add);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                .in(RoleMenu::getRoleId, roleIds));
        if (roleMenus.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> menuIds = roleMenus.stream().map(RoleMenu::getMenuId).collect(Collectors.toSet());
        return menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                        .in(Menu::getId, menuIds)
                        .eq(Menu::getStatus, 1)
                        .eq(Menu::getVisible, true)
                        .orderByAsc(Menu::getSort));
    }

    public List<Menu> getMenusForPermissionCodes(Set<String> permissionCodes) {
        if (permissionCodes.contains("*")) {
            return listAllEnabled();
        }
        List<Menu> allMenus = listAllEnabled();
        Map<Long, Menu> byId = allMenus.stream().collect(Collectors.toMap(Menu::getId, menu -> menu));
        Set<Long> included = allMenus.stream()
                .filter(menu -> StrUtil.isNotBlank(menu.getPermissionCode())
                        && permissionCodes.contains(menu.getPermissionCode()))
                .map(Menu::getId)
                .collect(Collectors.toSet());
        for (Long menuId : new ArrayList<>(included)) {
            Menu current = byId.get(menuId);
            while (current != null && current.getParentId() != null && current.getParentId() > 0) {
                included.add(current.getParentId());
                current = byId.get(current.getParentId());
            }
        }
        return allMenus.stream()
                .filter(menu -> included.contains(menu.getId()) && Boolean.TRUE.equals(menu.getVisible()))
                .toList();
    }

    /**
     * 构建菜单树（仅 DIRECTORY 和 PAGE 类型，用于前端侧栏）
     */
    public List<MenuTreeNode> buildMenuTree(List<Menu> menus) {
        return buildMenuTree(menus, false);
    }

    /**
     * 构建菜单树
     *
     * @param includeActions 是否包含 BUTTON（动作）节点；侧栏导航传 false，
     *                       权限管理页传 true 以便管理员查看/勾选操作类权限
     */
    public List<MenuTreeNode> buildMenuTree(List<Menu> menus, boolean includeActions) {
        Map<Long, MenuTreeNode> nodeMap = new LinkedHashMap<>();
        for (Menu menu : menus) {
            String type = menu.getType();
            boolean allowed = "DIRECTORY".equals(type) || "PAGE".equals(type)
                    || (includeActions && "BUTTON".equals(type));
            if (allowed) {
                nodeMap.put(menu.getId(), new MenuTreeNode(menu));
            }
        }
        List<MenuTreeNode> roots = new ArrayList<>();
        for (MenuTreeNode node : nodeMap.values()) {
            Long parentId = node.getMenu().getParentId();
            if (parentId == null || parentId == 0L || !nodeMap.containsKey(parentId)) {
                roots.add(node);
            } else {
                nodeMap.get(parentId).getChildren().add(node);
            }
        }
        return roots;
    }

    /**
     * 菜单树节点
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class MenuTreeNode {
        @com.fasterxml.jackson.annotation.JsonUnwrapped
        private Menu menu;
        @lombok.Builder.Default
        private List<MenuTreeNode> children = new ArrayList<>();

        public MenuTreeNode(Menu menu) {
            this.menu = menu;
            this.children = new ArrayList<>();
        }
    }
}
