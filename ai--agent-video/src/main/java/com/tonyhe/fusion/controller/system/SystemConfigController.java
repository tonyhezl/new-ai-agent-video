package com.tonyhe.fusion.controller.system;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.system.SystemConfig;
import com.tonyhe.fusion.service.system.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统配置 Controller
 */
@Tag(name = "系统配置")
@RestController
@RequestMapping("/api/system/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @Operation(summary = "获取所有系统配置")
    @GetMapping
    public CommonResult<List<SystemConfig>> list() {
        return CommonResult.success(systemConfigService.getAll());
    }

    @Operation(summary = "获取单个配置值")
    @GetMapping("/{key}")
    public CommonResult<String> get(@PathVariable String key) {
        return CommonResult.success(systemConfigService.getValue(key));
    }

    @Operation(summary = "保存配置（批量）")
    @PutMapping
    @PreAuthorize("@permissionService.has('system:config:manage')")
    public CommonResult<Boolean> save(@RequestBody Map<String, String> configs) {
        configs.forEach(systemConfigService::setValue);
        return CommonResult.success(true);
    }
}
