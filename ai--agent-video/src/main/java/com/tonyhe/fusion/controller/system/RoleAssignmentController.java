package com.tonyhe.fusion.controller.system;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.system.PermissionAuditService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.system.RoleAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;

/**
 * 作用域角色分配控制器
 */
@Tag(name = "作用域角色分配")
@RestController
@RequestMapping("/api/system/role-assignment")
@RequiredArgsConstructor
public class RoleAssignmentController {

    private final RoleAssignmentService roleAssignmentService;
    private final PermissionAuditService permissionAuditService;
    private final PermissionMonitorService permissionMonitorService;

    @PostMapping("/assign")
    @Operation(summary = "分配作用域角色")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<RoleAssignment> assign(@RequestBody AssignReqVO reqVO) {
        Long operatorId = SecurityUtils.requireCurrentUserId();
        RoleAssignment assignment = roleAssignmentService.assign(
                reqVO.getUserId(), reqVO.getRoleId(), reqVO.getScopeType(),
                reqVO.getScopeId(), operatorId, reqVO.getExpiresAt());
        if (assignment != null) {
            permissionAuditService.record(operatorId, reqVO.getUserId(), "ASSIGN",
                    reqVO.getRoleId(), reqVO.getScopeType(), reqVO.getScopeId(), null);
        }
        return success(assignment);
    }

    @PostMapping("/revoke")
    @Operation(summary = "撤销作用域角色")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<Boolean> revoke(@RequestBody AssignReqVO reqVO) {
        Long operatorId = SecurityUtils.requireCurrentUserId();
        roleAssignmentService.revoke(reqVO.getUserId(), reqVO.getRoleId(),
                reqVO.getScopeType(), reqVO.getScopeId());
        permissionAuditService.record(operatorId, reqVO.getUserId(), "REVOKE",
                reqVO.getRoleId(), reqVO.getScopeType(), reqVO.getScopeId(), null);
        return success(true);
    }

    @GetMapping("/list")
    @Operation(summary = "查询用户有效角色分配")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<List<RoleAssignment>> list(@RequestParam Long userId,
                                                    @RequestParam(required = false) String scopeType,
                                                    @RequestParam(required = false) Long scopeId) {
        return success(roleAssignmentService.getEffectiveAssignments(userId, scopeType, scopeId));
    }

    @GetMapping("/scopes/teams")
    @Operation(summary = "获取可授权团队选项")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<List<RoleAssignmentService.ScopeOption>> teamScopes() {
        return success(roleAssignmentService.listTeamScopes());
    }

    @GetMapping("/scopes/projects")
    @Operation(summary = "获取可授权项目选项")
    @PreAuthorize("@permissionService.has('system:role:manage')")
    public CommonResult<List<RoleAssignmentService.ScopeOption>> projectScopes() {
        return success(roleAssignmentService.listProjectScopes());
    }

    @Data
    public static class AssignReqVO {
        private Long userId;
        private Long roleId;
        private String scopeType;
        private Long scopeId;
        private LocalDateTime expiresAt;
    }
}
