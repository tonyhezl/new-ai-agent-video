package com.tonyhe.fusion.controller.generation;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.generation.CapabilityRule;
import com.tonyhe.fusion.service.generation.CapabilityRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "模型能力规则管理")
@RestController
@RequestMapping("/api/capability-rule")
@RequiredArgsConstructor
public class CapabilityRuleController {

    private final CapabilityRuleService capabilityRuleService;

    @GetMapping("/list")
    @Operation(summary = "获取能力规则列表")
    @Parameter(name = "modelType", description = "模型类型（可选）：2-图片 3-视频")
    public CommonResult<List<CapabilityRule>> list(@RequestParam(value = "modelType", required = false) Integer modelType) {
        return success(modelType != null
                ? capabilityRuleService.listByType(modelType)
                : capabilityRuleService.listAll());
    }

    @GetMapping("/get")
    @Operation(summary = "获取能力规则详情")
    @Parameter(name = "id", description = "规则ID", required = true)
    public CommonResult<CapabilityRule> get(@RequestParam("id") Long id) {
        return success(capabilityRuleService.getById(id));
    }

    @PostMapping("/create")
    @Operation(summary = "创建能力规则")
    @PreAuthorize("@permissionService.has('ai:model:manage')")
    public CommonResult<Long> create(@RequestBody CapabilityRule rule) {
        return success(capabilityRuleService.create(rule));
    }

    @PutMapping("/update")
    @Operation(summary = "更新能力规则")
    @PreAuthorize("@permissionService.has('ai:model:manage')")
    public CommonResult<Boolean> update(@RequestBody CapabilityRule rule) {
        capabilityRuleService.update(rule);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除能力规则")
    @PreAuthorize("@permissionService.has('ai:model:manage')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        capabilityRuleService.delete(id);
        return success(true);
    }
}
