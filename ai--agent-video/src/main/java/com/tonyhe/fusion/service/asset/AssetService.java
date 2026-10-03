package com.tonyhe.fusion.service.asset;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 资产服务
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final int OWNER_TYPE_TEAM = 2;

    private final AssetMapper assetMapper;
    private final AssetItemMapper assetItemMapper;
    private final ProjectService projectService;
    private final TeamService teamService;
    private final UserService userService;

    // ========== 资产 ==========

    @Cacheable(value = "asset", key = "#id")
    public Asset getById(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null)
            throw new BusinessException("资产不存在: " + id);
        return asset;
    }

    public List<Asset> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return assetMapper.selectBatchIds(ids);
    }

    public List<Asset> listByProject(Long projectId) {
        return assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByDesc(Asset::getCreateTime));
    }

    public List<Asset> listByProject(Long projectId, String type, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByDesc(Asset::getCreateTime);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Asset::getType, type);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return assetMapper.selectList(wrapper);
    }

    public List<Map<String, Object>> listWithItemsByProject(Long projectId) {
        List<Asset> assets = listByProject(projectId);
        if (assets.isEmpty())
            return List.of();

        List<Long> assetIds = assets.stream().map(Asset::getId).collect(Collectors.toList());
        List<AssetItem> allItems = assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                .in(AssetItem::getAssetId, assetIds)
                .orderByAsc(AssetItem::getSortOrder));

        Map<Long, List<AssetItem>> itemsMap = allItems.stream()
                .collect(Collectors.groupingBy(AssetItem::getAssetId));

        return assets.stream().map(asset -> {
            Map<String, Object> map = BeanUtil.beanToMap(asset, false, true);
            map.put("items", itemsMap.getOrDefault(asset.getId(), List.of()));
            return map;
        }).collect(Collectors.toList());
    }

    public List<AssetItem> listItemsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return assetItemMapper.selectBatchIds(ids);
    }

    public List<AssetItem> listItemsByAssetIds(Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        return assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                .in(AssetItem::getAssetId, assetIds)
                .orderByAsc(AssetItem::getAssetId)
                .orderByAsc(AssetItem::getSortOrder));
    }

    /**
     * 按用户分页查询资产（跨项目），支持可选的 projectId / type / keyword 过滤
     */
    public IPage<Asset> pageByUser(Long userId, Long projectId, String type, String keyword, int page, int size) {
        LambdaQueryWrapper<Asset> wrapper = buildUserQueryWrapper(userId, projectId, type, keyword);
        return assetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public IPage<Asset> pageAccessibleByUser(Long userId, Long projectId, String type, String keyword, int page, int size) {
        LambdaQueryWrapper<Asset> wrapper = buildAccessibleQueryWrapper(userId, projectId, type, keyword);
        return assetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 统计当前用户各类型资产数量（按 projectId / keyword 过滤，不按 type 过滤）
     * 返回 Map: type -> count
     */
    public Map<String, Long> countByUserGroupByType(Long userId, Long projectId, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = buildUserQueryWrapper(userId, projectId, null, keyword);
        List<Asset> all = assetMapper.selectList(
                wrapper.select(Asset::getType));
        return all.stream().collect(
                Collectors.groupingBy(Asset::getType, Collectors.counting()));
    }

    public Map<String, Long> countAccessibleByUserGroupByType(Long userId, Long projectId, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = buildAccessibleQueryWrapper(userId, projectId, null, keyword);
        List<Asset> all = assetMapper.selectList(
                wrapper.select(Asset::getType));
        return all.stream().collect(
                Collectors.groupingBy(Asset::getType, Collectors.counting()));
    }

    public boolean canAccessAsset(Long assetId, Long userId) {
        return canAccessAsset(getById(assetId), userId);
    }

    public boolean canAccessAsset(Asset asset, Long userId) {
        if (asset == null) {
            return false;
        }
        if (userId.equals(asset.getUserId()) || userId.equals(asset.getOwnerId())) {
            return true;
        }
        if (asset.getProjectId() != null) {
            return projectService.canAccessProject(asset.getProjectId(), userId);
        }
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        if (currentTeamId == null) {
            return false;
        }
        if (OWNER_TYPE_TEAM == asset.getOwnerType() && currentTeamId.equals(asset.getOwnerId())) {
            return true;
        }
        return false;
    }

    private LambdaQueryWrapper<Asset> buildUserQueryWrapper(Long userId, Long projectId, String type, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .orderByDesc(Asset::getUpdateTime);
        if (projectId != null) {
            wrapper.eq(Asset::getProjectId, projectId);
        }
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(Asset::getType, type);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return wrapper;
    }

    private LambdaQueryWrapper<Asset> buildAccessibleQueryWrapper(Long userId, Long projectId, String type, String keyword) {
        if (userService.isAdmin(userId)) {
            LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                    .orderByDesc(Asset::getUpdateTime);
            if (projectId != null) {
                wrapper.eq(Asset::getProjectId, projectId);
            }
            if (StrUtil.isNotBlank(type)) {
                wrapper.eq(Asset::getType, type);
            }
            if (StrUtil.isNotBlank(keyword)) {
                wrapper.like(Asset::getName, keyword.trim());
            }
            return wrapper;
        }
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        if (currentTeamId == null) {
            return buildUserQueryWrapper(userId, projectId, type, keyword);
        }
        String accessibleProjectSql = "SELECT p.id FROM afv_project p WHERE p.deleted = 0 AND ("
                + "(p.owner_type = 1 AND p.owner_id = " + userId + ") OR "
                + "(p.owner_type = 2 AND p.owner_id = " + currentTeamId + ") OR "
                + "EXISTS (SELECT 1 FROM afv_project_member pm WHERE pm.deleted = 0 "
                + "AND pm.project_id = p.id AND pm.user_id = " + userId + "))";
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .and(scope -> scope
                        .and(teamOwned -> teamOwned
                                .eq(Asset::getOwnerType, OWNER_TYPE_TEAM)
                                .eq(Asset::getOwnerId, currentTeamId))
                        .or(personalOwned -> personalOwned.eq(Asset::getUserId, userId))
                        .or(projectOwned -> projectOwned.inSql(Asset::getProjectId, accessibleProjectSql)))
                .orderByDesc(Asset::getUpdateTime);
        if (projectId != null) {
            wrapper.eq(Asset::getProjectId, projectId);
        }
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(Asset::getType, type);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return wrapper;
    }

    public List<Asset> listByOwner(Integer ownerType, Long ownerId, String type) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getOwnerType, ownerType)
                .eq(Asset::getOwnerId, ownerId)
                .orderByDesc(Asset::getCreateTime);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Asset::getType, type);
        }
        return assetMapper.selectList(wrapper);
    }

    public List<Asset> listAccessibleByUser(Long userId, String type) {
        return assetMapper.selectList(buildAccessibleQueryWrapper(userId, null, type, null));
    }

    public Asset findByProjectTypeAndName(Long projectId, String type, String name) {
        return assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getType, type)
                .eq(Asset::getName, name)
                .last("LIMIT 1"));
    }

    @CacheEvict(value = { "asset", "assetItem" }, allEntries = true)
    @Transactional
    public Asset create(Asset asset) {
        validateAssetMediaUrls(asset);
        applyCurrentTeamOwnership(asset);
        assetMapper.insert(asset);

        // 自动创建初始子资产，名称使用主资产名称
        AssetItem initialItem = AssetItem.builder()
                .assetId(asset.getId())
                .itemType("initial")
                .name(asset.getName())
                .sortOrder(0)
                .sourceType(asset.getSourceType() != null ? asset.getSourceType() : 1)
                .build();
        assetItemMapper.insert(initialItem);

        // 角色类型资产自动创建三视图子资产，用于后续首尾帧生成时提供多角度参考
        // 仅对 lead/support 角色创建 three_view，extra/background 角色跳过以节省资源
        if ("character".equals(asset.getType())) {
            boolean shouldCreateThreeView = true;
            String clothing = null;
            if (StrUtil.isNotBlank(asset.getProperties())) {
                try {
                    JSONObject props = JSONUtil.parseObj(asset.getProperties());
                    String importance = props.getStr("importance");
                    if ("extra".equals(importance) || "background".equals(importance)) {
                        shouldCreateThreeView = false;
                    }
                    clothing = props.getStr("clothing");
                } catch (Exception e) {
                    // properties 解析失败，默认创建 three_view
                }
            }
            int nextSort = 1;
            if (shouldCreateThreeView) {
                AssetItem threeViewItem = AssetItem.builder()
                        .assetId(asset.getId())
                        .itemType("three_view")
                        .name(asset.getName() + " 三视图")
                        .sortOrder(1)
                        .sourceType(asset.getSourceType() != null ? asset.getSourceType() : 1)
                        .build();
                assetItemMapper.insert(threeViewItem);
                nextSort = 2;
            }

            // 自动创建默认常服子资产（costume），用于分镜生图时提供服装参考
            // 初始图为素色内衣基体；clothing 字段写入常服 properties，供生图读取
            AssetItem defaultCostumeItem = AssetItem.builder()
                    .assetId(asset.getId())
                    .itemType("costume")
                    .name(asset.getName() + " 常服")
                    .sortOrder(nextSort)
                    .sourceType(asset.getSourceType() != null ? asset.getSourceType() : 1)
                    .properties(buildDefaultCostumeProperties(clothing))
                    .build();
            assetItemMapper.insert(defaultCostumeItem);
        }

        return asset;
    }

    /**
     * 将主资产 clothing 写入默认常服 properties。
     * 素色内衣/空值不写入（那是 initial 基体，不是常服语义）。
     */
    private String buildDefaultCostumeProperties(String clothing) {
        if (StrUtil.isBlank(clothing) || isBaseUnderwearClothing(clothing)) {
            return null;
        }
        return JSONUtil.createObj()
                .set("clothing", clothing.trim())
                .toString();
    }

    private boolean isBaseUnderwearClothing(String clothing) {
        String c = clothing.trim();
        if ("无".equals(c)) {
            return true;
        }
        return c.contains("素色紧身衣") || c.contains("中性内衣") || c.contains("素色内衣");
    }

    @CacheEvict(value = "asset", allEntries = true)
    @Transactional
    public Asset update(Asset asset) {
        getById(asset.getId());
        validateAssetMediaUrls(asset);
        assetMapper.updateById(asset);
        return asset;
    }

    @CacheEvict(value = "asset", allEntries = true)
    @Transactional
    public void delete(Long id) {
        assetMapper.deleteById(id);
    }

    // ========== 子资产 ==========

    public AssetItem getItemById(Long id) {
        AssetItem item = assetItemMapper.selectById(id);
        if (item == null)
            throw new BusinessException("子资产不存在: " + id);
        return item;
    }

    @Cacheable(value = "assetItem", key = "'asset:' + #assetId")
    public List<AssetItem> listItems(Long assetId) {
        return assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                .eq(AssetItem::getAssetId, assetId)
                .orderByAsc(AssetItem::getSortOrder));
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem createItem(AssetItem item) {
        validateAssetItemMediaUrls(item);
        assetItemMapper.insert(item);
        syncCoverIfAbsent(item);
        return item;
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem updateItem(AssetItem item) {
        AssetItem existing = assetItemMapper.selectById(item.getId());
        if (existing == null)
            throw new BusinessException("子资产不存在: " + item.getId());
        validateAssetItemMediaUrls(item);
        assetItemMapper.updateById(item);
        // 部分更新时 item 可能缺少 assetId/imageUrl/itemType，用 existing 补全
        if (item.getAssetId() == null) {
            item.setAssetId(existing.getAssetId());
        }
        if (item.getImageUrl() == null) {
            item.setImageUrl(existing.getImageUrl());
        }
        if (item.getItemType() == null) {
            item.setItemType(existing.getItemType());
        }
        syncCoverIfAbsent(item);
        return item;
    }

    @CacheEvict(value = "assetItem", allEntries = true)
    @Transactional
    public void deleteItem(Long id) {
        assetItemMapper.deleteById(id);
    }

    private void validateAssetMediaUrls(Asset asset) {
        if (asset == null) {
            return;
        }
        rejectDataUrl(asset.getCoverUrl(), "coverUrl");
    }

    private void validateAssetItemMediaUrls(AssetItem item) {
        if (item == null) {
            return;
        }
        rejectDataUrl(item.getImageUrl(), "imageUrl");
        rejectDataUrl(item.getThumbnailUrl(), "thumbnailUrl");
    }

    private void rejectDataUrl(String rawUrl, String fieldName) {
        if (StrUtil.isNotBlank(rawUrl) && StrUtil.startWithIgnoreCase(rawUrl.trim(), "data:")) {
            throw new BusinessException(fieldName + " 不支持 base64，请先调用 /api/storage/upload 上传二进制文件");
        }
    }

    private void applyCurrentTeamOwnership(Asset asset) {
        Long creatorUserId = asset.getUserId() != null ? asset.getUserId() : SecurityUtils.getCurrentUserId();
        if (creatorUserId == null) {
            return;
        }
        asset.setUserId(creatorUserId);
        TeamService.OwnerScope ownerScope = teamService.getRequiredCurrentOwnerScopeByUser(creatorUserId);
        asset.setOwnerType(ownerScope.getOwnerType());
        asset.setOwnerId(ownerScope.getOwnerId());
    }

    /**
     * 同步主资产封面：
     * - initial 类型子资产：新增或更新图片时，始终同步为主资产封面
     * - 其他类型子资产：主资产无封面，或封面为远程临时 URL（会过期）而新图片已持久化时，自动填充/覆盖
     */
    private void syncCoverIfAbsent(AssetItem item) {
        if (StrUtil.isBlank(item.getImageUrl()) || item.getAssetId() == null) {
            return;
        }
        Asset asset = assetMapper.selectById(item.getAssetId());
        if (asset == null) {
            return;
        }
        if ("initial".equals(item.getItemType())
                || StrUtil.isBlank(asset.getCoverUrl())
                || isTransientRemoteCover(asset.getCoverUrl(), item.getImageUrl())) {
            asset.setCoverUrl(item.getImageUrl());
            assetMapper.updateById(asset);
        }
    }

    /** 封面为远程 URL（如 DashScope 临时签名链接，会过期）且新图片已持久化为非远程地址时，允许覆盖 */
    private boolean isTransientRemoteCover(String coverUrl, String newImageUrl) {
        return isRemoteUrl(coverUrl) && !isRemoteUrl(newImageUrl);
    }

    private boolean isRemoteUrl(String url) {
        return StrUtil.isNotBlank(url)
                && (StrUtil.startWithIgnoreCase(url, "http://") || StrUtil.startWithIgnoreCase(url, "https://"));
    }
}
