package com.tonyhe.fusion.controller.system;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.system.Menu;
import com.tonyhe.fusion.service.system.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;

/**
 * 菜单管理控制器
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/api/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/tree")
    @Operation(summary = "获取完整菜单树（管理员，含动作权限节点）")
    @PreAuthorize("@permissionService.has('system:menu:manage')")
    public CommonResult<List<MenuService.MenuTreeNode>> menuTree() {
        List<Menu> menus = menuService.listAllEnabled();
        return success(menuService.buildMenuTree(menus, true));
    }

    @GetMapping("/role/{roleId}")
    @Operation(summary = "获取角色绑定的菜单ID列表")
    @PreAuthorize("@permissionService.has('system:menu:manage')")
    public CommonResult<List<Long>> roleMenuIds(@PathVariable Long roleId) {
        return success(menuService.getMenuIdsByRoleId(roleId));
    }

    @PostMapping("/role/{roleId}/assign")
    @Operation(summary = "绑定角色菜单（全量替换）")
    @PreAuthorize("@permissionService.has('system:menu:manage')")
    public CommonResult<Boolean> assignRoleMenus(@PathVariable Long roleId,
                                                  @RequestBody List<Long> menuIds) {
        menuService.assignRoleMenus(roleId, menuIds);
        return success(true);
    }
}
