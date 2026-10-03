package com.tonyhe.fusion.controller.system;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.service.system.DataIntegrityScanService;
import com.tonyhe.fusion.service.system.PermissionAuditService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.tonyhe.fusion.common.CommonResult.success;

/**
 * 权限监控控制器
 */
@Tag(name = "权限监控")
@RestController
@RequestMapping("/api/system/permission-monitor")
@RequiredArgsConstructor
public class PermissionMonitorController {

    private final PermissionMonitorService permissionMonitorService;
    private final DataIntegrityScanService dataIntegrityScanService;
    private final PermissionAuditService permissionAuditService;

    @GetMapping("/metrics")
    @Operation(summary = "获取权限监控指标")
    @PreAuthorize("@permissionService.has('system:config:manage')")
    public CommonResult<Map<String, Object>> metrics() {
        return success(permissionMonitorService.getMetrics());
    }

    @PostMapping("/scan")
    @Operation(summary = "手动触发数据完整性扫描")
    @PreAuthorize("@permissionService.has('system:config:manage')")
    public CommonResult<DataIntegrityScanService.ScanResult> scan() {
        return success(dataIntegrityScanService.scan());
    }

    @PostMapping("/cleanup-expired")
    @Operation(summary = "手动清理过期角色分配")
    @PreAuthorize("@permissionService.has('system:config:manage')")
    public CommonResult<Integer> cleanupExpired() {
        return success(dataIntegrityScanService.cleanupExpiredAssignments());
    }

    @GetMapping("/audit/page")
    @Operation(summary = "分页查询权限变更审计日志")
    @PreAuthorize("@permissionService.has('system:config:manage')")
    public CommonResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.tonyhe.fusion.entity.system.PermissionAudit>> auditPage(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return success(permissionAuditService.page(pageNo, pageSize));
    }
}
