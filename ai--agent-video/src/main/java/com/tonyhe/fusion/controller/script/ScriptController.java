package com.tonyhe.fusion.controller.script;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.script.vo.EpisodeCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.EpisodeUpdateReqVO;
import com.tonyhe.fusion.controller.script.vo.SceneCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.SceneUpdateReqVO;
import com.tonyhe.fusion.controller.script.vo.ScriptCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.ScriptUpdateReqVO;
import com.tonyhe.fusion.convert.script.ScriptConvert;
import com.tonyhe.fusion.entity.script.ScriptSceneItem;
import com.tonyhe.fusion.entity.script.Script;
import com.tonyhe.fusion.entity.script.ScriptEpisode;
import com.tonyhe.fusion.service.script.ScriptService;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剧本 Controller（含分集、分场次）
 */
@Tag(name = "剧本管理")
@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final ResourceAccessService resourceAccessService;

    // ========== 剧本 ==========

    @Operation(summary = "获取剧本详情")
    @GetMapping("/{id}")
    public CommonResult<Script> get(@PathVariable Long id) {
        resourceAccessService.checkScriptAccess(id, SecurityUtils.getCurrentUserId());
        return CommonResult.success(scriptService.getById(id));
    }

    @Operation(summary = "按项目查询剧本列表")
    @GetMapping("/list")
    public CommonResult<List<Script>> list(@RequestParam Long projectId) {
        resourceAccessService.checkProjectContentAccess(projectId, SecurityUtils.getCurrentUserId(),
                AccessAction.VIEW, PermissionCodes.SCRIPT_VIEW);
        return CommonResult.success(scriptService.listByProject(projectId));
    }

    @Operation(summary = "创建剧本")
    @PostMapping
    public CommonResult<Script> create(@Valid @RequestBody ScriptCreateReqVO reqVO) {
        resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), SecurityUtils.getCurrentUserId(),
                AccessAction.EDIT, PermissionCodes.SCRIPT_EDIT);
        Script script = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.create(script));
    }

    @Operation(summary = "更新剧本")
    @PutMapping
    public CommonResult<Script> update(@Valid @RequestBody ScriptUpdateReqVO reqVO) {
        resourceAccessService.checkScriptAccess(reqVO.getId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        Script script = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.update(script));
    }

    @Operation(summary = "删除剧本")
    @DeleteMapping("/{id}")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        resourceAccessService.checkScriptAccess(id, SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        scriptService.delete(id);
        return CommonResult.success(true);
    }

    // ========== 分集 ==========

    @Operation(summary = "获取分集列表")
    @GetMapping("/{scriptId}/episodes")
    public CommonResult<List<ScriptEpisode>> listEpisodes(@PathVariable Long scriptId) {
        resourceAccessService.checkScriptAccess(scriptId, SecurityUtils.getCurrentUserId());
        return CommonResult.success(scriptService.listEpisodes(scriptId));
    }

    @Operation(summary = "获取分集详情")
    @GetMapping("/episode/{id}")
    public CommonResult<ScriptEpisode> getEpisode(@PathVariable Long id) {
        resourceAccessService.checkScriptEpisodeAccess(id, SecurityUtils.getCurrentUserId());
        return CommonResult.success(scriptService.getEpisodeById(id));
    }

    @Operation(summary = "创建分集")
    @PostMapping("/episode")
    public CommonResult<ScriptEpisode> createEpisode(@Valid @RequestBody EpisodeCreateReqVO reqVO) {
        resourceAccessService.checkScriptAccess(reqVO.getScriptId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        ScriptEpisode episode = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.createEpisode(episode));
    }

    @Operation(summary = "更新分集")
    @PutMapping("/episode")
    public CommonResult<ScriptEpisode> updateEpisode(@Valid @RequestBody EpisodeUpdateReqVO reqVO) {
        resourceAccessService.checkScriptEpisodeAccess(reqVO.getId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        ScriptEpisode episode = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.updateEpisode(episode));
    }

    @Operation(summary = "删除分集")
    @DeleteMapping("/episode/{id}")
    public CommonResult<Boolean> deleteEpisode(@PathVariable Long id) {
        resourceAccessService.checkScriptEpisodeAccess(id, SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        scriptService.deleteEpisode(id);
        return CommonResult.success(true);
    }

    // ========== 分场次 ==========

    @Operation(summary = "获取分场次列表（按分集）")
    @GetMapping("/episode/{episodeId}/scenes")
    public CommonResult<List<ScriptSceneItem>> listScenes(@PathVariable Long episodeId) {
        resourceAccessService.checkScriptEpisodeAccess(episodeId, SecurityUtils.getCurrentUserId());
        return CommonResult.success(scriptService.listScenesByEpisode(episodeId));
    }

    @Operation(summary = "获取分场次详情")
    @GetMapping("/scene/{id}")
    public CommonResult<ScriptSceneItem> getScene(@PathVariable Long id) {
        resourceAccessService.checkScriptSceneAccess(id, SecurityUtils.getCurrentUserId());
        return CommonResult.success(scriptService.getSceneById(id));
    }

    @Operation(summary = "创建分场次")
    @PostMapping("/scene")
    public CommonResult<ScriptSceneItem> createScene(@Valid @RequestBody SceneCreateReqVO reqVO) {
        if (reqVO.getScriptId() != null) {
            resourceAccessService.checkScriptAccess(reqVO.getScriptId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        } else if (reqVO.getEpisodeId() != null) {
            resourceAccessService.checkScriptEpisodeAccess(reqVO.getEpisodeId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        }
        ScriptSceneItem scene = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.createScene(scene));
    }

    @Operation(summary = "更新分场次")
    @PutMapping("/scene")
    public CommonResult<ScriptSceneItem> updateScene(@Valid @RequestBody SceneUpdateReqVO reqVO) {
        resourceAccessService.checkScriptSceneAccess(reqVO.getId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        ScriptSceneItem scene = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.updateScene(scene));
    }

    @Operation(summary = "删除分场次")
    @DeleteMapping("/scene/{id}")
    public CommonResult<Boolean> deleteScene(@PathVariable Long id) {
        resourceAccessService.checkScriptSceneAccess(id, SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        scriptService.deleteScene(id);
        return CommonResult.success(true);
    }
}
