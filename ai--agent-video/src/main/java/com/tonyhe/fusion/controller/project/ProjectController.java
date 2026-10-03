package com.tonyhe.fusion.controller.project;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageParam;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.config.ArtStylePresets;
import com.tonyhe.fusion.controller.project.vo.ProjectCreateReqVO;
import com.tonyhe.fusion.controller.project.vo.ProjectUpdateReqVO;
import com.tonyhe.fusion.convert.project.ProjectConvert;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.project.ProjectMember;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.SystemConfigService;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 视频项目 Controller
 */
@Tag(name = "视频项目管理")
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final SystemConfigService systemConfigService;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;

    @Operation(summary = "项目分页（管理员）")
    @GetMapping("/page")
    @PreAuthorize("@permissionService.has('project:view')")
    public CommonResult<PageResult<Project>> page(PageParam pageParam) {
        return CommonResult.success(projectService.pageAccessibleByUser(SecurityUtils.requireCurrentUserId(),
                pageParam.getPageNo(), pageParam.getPageSize()));
    }

    @Operation(summary = "获取项目详情")
    @GetMapping("/{id}")
    public CommonResult<Project> get(@PathVariable Long id) {
        resourceAccessService.checkProjectAccess(id, SecurityUtils.getCurrentUserId());
        return CommonResult.success(projectService.getById(id));
    }

    @Operation(summary = "按归属查询项目列表")
    @GetMapping("/list")
    public CommonResult<List<Project>> list() {
        return CommonResult.success(projectService.listAccessibleByUser(SecurityUtils.getCurrentUserId()));
    }

    @Operation(summary = "创建项目")
    @PostMapping
    public CommonResult<Project> create(@Valid @RequestBody ProjectCreateReqVO reqVO) {
        authorizationService.requirePermission(SecurityUtils.requireCurrentUserId(), PermissionCodes.PROJECT_CREATE);
        Project project = ProjectConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(projectService.create(project));
    }

    @Operation(summary = "更新项目")
    @PutMapping
    public CommonResult<Project> update(@Valid @RequestBody ProjectUpdateReqVO reqVO) {
        resourceAccessService.checkProjectAccess(reqVO.getId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        Project project = ProjectConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(projectService.update(project));
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{id}")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        resourceAccessService.checkProjectAccess(id, SecurityUtils.getCurrentUserId(), AccessAction.DELETE);
        projectService.delete(id);
        return CommonResult.success(true);
    }

    // ========== 成员管理 ==========

    @Operation(summary = "获取项目成员列表")
    @GetMapping("/{projectId}/members")
    public CommonResult<List<ProjectMember>> listMembers(@PathVariable Long projectId) {
        resourceAccessService.checkProjectAccess(projectId, SecurityUtils.getCurrentUserId(), AccessAction.MANAGE);
        return CommonResult.success(projectService.listMembers(projectId));
    }

    @Operation(summary = "添加项目成员")
    @PostMapping("/{projectId}/members")
    public CommonResult<ProjectMember> addMember(@PathVariable Long projectId,
                                                  @RequestParam Long userId,
                                                  @RequestParam(defaultValue = "3") Integer role) {
        resourceAccessService.checkProjectAccess(projectId, SecurityUtils.getCurrentUserId(), AccessAction.MANAGE);
        return CommonResult.success(projectService.addMember(projectId, userId, role));
    }

    @Operation(summary = "移除项目成员")
    @DeleteMapping("/{projectId}/members/{userId}")
    public CommonResult<Boolean> removeMember(@PathVariable Long projectId, @PathVariable Long userId) {
        resourceAccessService.checkProjectAccess(projectId, SecurityUtils.getCurrentUserId(), AccessAction.MANAGE);
        projectService.removeMember(projectId, userId);
        return CommonResult.success(true);
    }

    // ========== 画风预设 ==========

    @Operation(summary = "获取预设画风列表")
    @GetMapping("/presets/art-styles")
    public CommonResult<List<ArtStylePresets.ArtStylePreset>> listArtStylePresets() {
        List<ArtStylePresets.ArtStylePreset> presets = ArtStylePresets.getAll();
        // 填充每个预设的公网 URL 列表
        for (ArtStylePresets.ArtStylePreset preset : presets) {
            List<String> paths = preset.getReferenceImagePaths();
            if (paths == null || paths.isEmpty()) {
                preset.setReferenceImagePublicUrls(java.util.Collections.emptyList());
                continue;
            }
            List<String> publicUrls = new java.util.ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                // 兼容旧格式 art_preset_url:{key} 和新格式 art_preset_url:{key}_{index}
                String configKey = paths.size() == 1
                        ? "art_preset_url:" + preset.getKey()
                        : "art_preset_url:" + preset.getKey() + "_" + i;
                String publicUrl = systemConfigService.getValue(configKey);
                publicUrls.add(publicUrl);
            }
            preset.setReferenceImagePublicUrls(publicUrls);
        }
        return CommonResult.success(presets);
    }
}
