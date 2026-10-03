package com.tonyhe.fusion.controller.system;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.system.vo.RoleRespVO;
import com.tonyhe.fusion.controller.system.vo.RoleSaveReqVO;
import com.tonyhe.fusion.convert.system.RoleConvert;
import com.tonyhe.fusion.entity.system.Role;
import com.tonyhe.fusion.service.system.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @PostMapping("/create")
    @Operation(summary = "创建角色")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<Long> createRole(@Valid @RequestBody RoleSaveReqVO reqVO) {
        Role role = roleService.createRole(reqVO.getName(), reqVO.getCode(), reqVO.getSort(), reqVO.getRemark());
        return success(role.getId());
    }

    @PutMapping("/update")
    @Operation(summary = "更新角色")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<Boolean> updateRole(@Valid @RequestBody RoleSaveReqVO reqVO) {
        roleService.updateRole(reqVO.getId(), reqVO.getName(), reqVO.getSort(), null, reqVO.getRemark());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除角色")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<Boolean> deleteRole(@RequestParam("id") Long id) {
        roleService.deleteRole(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取角色详情")
    @Parameter(name = "id", description = "角色ID", required = true)
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<RoleRespVO> getRole(@RequestParam("id") Long id) {
        Role role = roleService.getById(id);
        return success(role == null ? null : RoleConvert.INSTANCE.convert(role));
    }

    @GetMapping("/list")
    @Operation(summary = "获取角色列表")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<List<RoleRespVO>> getRoleList() {
        return success(RoleConvert.INSTANCE.convertList(roleService.getEnabledRoles()));
    }
}
