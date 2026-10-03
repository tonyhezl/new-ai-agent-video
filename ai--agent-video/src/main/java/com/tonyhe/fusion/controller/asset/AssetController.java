package com.tonyhe.fusion.controller.asset;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.asset.vo.AssetCreateReqVO;
import com.tonyhe.fusion.controller.asset.vo.AssetItemCreateReqVO;
import com.tonyhe.fusion.controller.asset.vo.AssetItemUpdateReqVO;
import com.tonyhe.fusion.controller.asset.vo.AssetUpdateReqVO;
import com.tonyhe.fusion.convert.asset.AssetConvert;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.tonyhe.fusion.service.asset.AssetMetadataRegistry;
import com.tonyhe.fusion.service.asset.AssetMetadataRegistry.FieldDef;

import java.util.List;
import java.util.Map;

/**
 * 资产管理 Controller
 */
@Tag(name = "资产管理")
@RestController
@RequestMapping("/api/asset")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;

    // ========== 元数据 ==========

    @Operation(summary = "查询资产属性字段定义")
    @GetMapping("/metadata/{assetType}")
    public CommonResult<Map<String, Object>> getMetadata(@PathVariable String assetType) {
        List<FieldDef> fields = AssetMetadataRegistry.getFields(assetType);
        if (fields == null) {
            return CommonResult.error(400, "不支持的资产类型: " + assetType);
        }
        return CommonResult.success(Map.of(
                "assetType", assetType,
                "fields", fields
        ));
    }

    // ========== 资产 ==========

    @Operation(summary = "获取资产详情")
    @GetMapping("/{id}")
    public CommonResult<Asset> get(@PathVariable Long id) {
        resourceAccessService.checkAssetAccess(id, SecurityUtils.getCurrentUserId());
        return CommonResult.success(assetService.getById(id));
    }

    @Operation(summary = "按项目+类型查询资产列表")
    @GetMapping("/list")
    public CommonResult<List<Asset>> list(@RequestParam Long projectId,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String keyword) {
        resourceAccessService.checkProjectContentAccess(projectId, SecurityUtils.getCurrentUserId(),
                AccessAction.VIEW, PermissionCodes.ASSET_VIEW);
        return CommonResult.success(assetService.listByProject(projectId, type, keyword));
    }

    @Operation(summary = "按项目查询资产及其所有子资产")
    @GetMapping("/list-with-items")
    public CommonResult<List<Map<String, Object>>> listWithItems(@RequestParam Long projectId) {
        resourceAccessService.checkProjectContentAccess(projectId, SecurityUtils.getCurrentUserId(),
                AccessAction.VIEW, PermissionCodes.ASSET_VIEW);
        return CommonResult.success(assetService.listWithItemsByProject(projectId));
    }

    @Operation(summary = "分页查询当前用户的资产（跨项目），含类型统计")
    @GetMapping("/all")
    public CommonResult<Map<String, Object>> listAll(@RequestParam(required = false) Long projectId,
                                                      @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        authorizationService.requirePermission(userId, PermissionCodes.ASSET_VIEW);
        IPage<Asset> pageResult = assetService.pageAccessibleByUser(userId, projectId, type, keyword, page, size);
        Map<String, Long> typeCounts = assetService.countAccessibleByUserGroupByType(userId, null, null);
        return CommonResult.success(Map.of(
                "records", pageResult.getRecords(),
                "total", pageResult.getTotal(),
                "page", pageResult.getCurrent(),
                "size", pageResult.getSize(),
                "typeCounts", typeCounts
        ));
    }

    @Operation(summary = "创建资产")
    @PostMapping
    public CommonResult<Asset> create(@Valid @RequestBody AssetCreateReqVO reqVO) {
        if (reqVO.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), SecurityUtils.getCurrentUserId(),
                    AccessAction.EDIT, PermissionCodes.ASSET_EDIT);
        } else {
            authorizationService.requirePermission(SecurityUtils.requireCurrentUserId(), PermissionCodes.ASSET_EDIT);
        }
        Asset asset = AssetConvert.INSTANCE.convert(reqVO);
        // userId 由后端决定，owner 归属由 service 按当前团队绑定
        asset.setUserId(SecurityUtils.getCurrentUserId());
        return CommonResult.success(assetService.create(asset));
    }

    @Operation(summary = "更新资产")
    @PutMapping
    public CommonResult<Asset> update(@Valid @RequestBody AssetUpdateReqVO reqVO) {
        resourceAccessService.checkAssetAccess(reqVO.getId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        Asset asset = AssetConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(assetService.update(asset));
    }

    @Operation(summary = "删除资产")
    @DeleteMapping("/{id}")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        resourceAccessService.checkAssetAccess(id, SecurityUtils.getCurrentUserId(), AccessAction.DELETE);
        assetService.delete(id);
        return CommonResult.success(true);
    }

    // ========== 子资产 ==========

    @Operation(summary = "获取子资产详情")
    @GetMapping("/item/{id}")
    public CommonResult<AssetItem> getItem(@PathVariable Long id) {
        AssetItem item = assetService.getItemById(id);
        resourceAccessService.checkAssetAccess(item.getAssetId(), SecurityUtils.getCurrentUserId());
        return CommonResult.success(item);
    }

    @Operation(summary = "获取子资产列表")
    @GetMapping("/{assetId}/items")
    public CommonResult<List<AssetItem>> listItems(@PathVariable Long assetId) {
        resourceAccessService.checkAssetAccess(assetId, SecurityUtils.getCurrentUserId());
        return CommonResult.success(assetService.listItems(assetId));
    }

    @Operation(summary = "创建子资产")
    @PostMapping("/item")
    public CommonResult<AssetItem> createItem(@Valid @RequestBody AssetItemCreateReqVO reqVO) {
        if (reqVO.getAssetId() != null) {
            resourceAccessService.checkAssetAccess(reqVO.getAssetId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        }
        AssetItem item = AssetConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(assetService.createItem(item));
    }

    @Operation(summary = "更新子资产")
    @PutMapping("/item")
    public CommonResult<AssetItem> updateItem(@Valid @RequestBody AssetItemUpdateReqVO reqVO) {
        AssetItem existing = assetService.getItemById(reqVO.getId());
        resourceAccessService.checkAssetAccess(existing.getAssetId(), SecurityUtils.getCurrentUserId(), AccessAction.EDIT);
        AssetItem item = AssetConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(assetService.updateItem(item));
    }

    @Operation(summary = "删除子资产")
    @DeleteMapping("/item/{id}")
    public CommonResult<Boolean> deleteItem(@PathVariable Long id) {
        AssetItem item = assetService.getItemById(id);
        resourceAccessService.checkAssetAccess(item.getAssetId(), SecurityUtils.getCurrentUserId(), AccessAction.DELETE);
        assetService.deleteItem(id);
        return CommonResult.success(true);
    }
}
