package com.tonyhe.fusion.service.media;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardItemMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardMapper;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 媒体库服务
 * <p>
 * 聚合分镜镜头媒体（图片/视频）和资产子资产图片，
 * 提供统一浏览与批量清除媒体 URL 的能力。
 * 使用 SQL 级分页 + 批量查询关联实体，避免 N+1 问题。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaLibraryService {

    private final StoryboardItemMapper storyboardItemMapper;
    private final StoryboardMapper storyboardMapper;
    private final AssetItemMapper assetItemMapper;
    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final UserService userService;
    private final ProjectService projectService;

    /**
     * 媒体条目 VO
     */
    public static class MediaEntry {
        public String id;
        public String source;
        public String mediaType;
        public String url;
        public String thumbnailUrl;
        public String label;
        public String subLabel;
        public Long projectId;
        public String projectName;
        public Object createTime;
        public Long entityId;
        public String field;
    }

    /**
     * 查询媒体列表（SQL 级分页）
     *
     * @param projectId  项目ID（null = 全部）
     * @param mediaType  "image" / "video" / null=全部
     * @param source     "storyboard" / "asset" / null=全部
     * @param page       页码（1-based）
     * @param size       每页数量
     * @return 媒体列表 + 总数
     */
    public Map<String, Object> listMedia(Long projectId, String mediaType, String source,
                                          int page, int size) {
        return listMedia(projectId, mediaType, source, page, size, null);
    }

    /**
     * 查询媒体列表（SQL 级分页），带数据隔离。
     * <p>
     * 当 userId 非 null 且非 admin 时，仅返回用户可访问项目下的媒体。
     *
     * @param userId     当前用户ID（null 表示不做隔离）
     */
    public Map<String, Object> listMedia(Long projectId, String mediaType, String source,
                                          int page, int size, Long userId) {
        Set<Long> accessibleProjectIds = null;
        if (userId != null && !userService.isAdmin(userId)) {
            accessibleProjectIds = getAccessibleProjectIds(userId);
            if (projectId != null) {
                if (!accessibleProjectIds.contains(projectId)) {
                    return buildResult(new ArrayList<>(), 0, page, size);
                }
            } else {
                if (accessibleProjectIds.isEmpty()) {
                    return buildResult(new ArrayList<>(), 0, page, size);
                }
            }
        }
        boolean fetchStoryboard = source == null || "storyboard".equals(source);
        boolean fetchAsset = source == null || "asset".equals(source);

        if (fetchStoryboard && fetchAsset) {
            List<MediaEntry> sbEntries = queryStoryboardMedia(projectId, mediaType, page, size, accessibleProjectIds);
            long sbTotal = countStoryboardMedia(projectId, mediaType, accessibleProjectIds);

            List<MediaEntry> assetEntries = queryAssetMedia(projectId, mediaType, page, size, accessibleProjectIds);
            long assetTotal = countAssetMedia(projectId, mediaType, accessibleProjectIds);

            List<MediaEntry> merged = new ArrayList<>(sbEntries.size() + assetEntries.size());
            merged.addAll(sbEntries);
            merged.addAll(assetEntries);
            merged.sort((a, b) -> {
                if (b.createTime == null && a.createTime == null) return 0;
                if (b.createTime == null) return -1;
                if (a.createTime == null) return 1;
                return b.createTime.toString().compareTo(a.createTime.toString());
            });

            int toIndex = Math.min(size, merged.size());
            List<MediaEntry> pageResult = merged.subList(0, toIndex);
            return buildResult(pageResult, sbTotal + assetTotal, page, size);
        } else if (fetchStoryboard) {
            List<MediaEntry> entries = queryStoryboardMedia(projectId, mediaType, page, size, accessibleProjectIds);
            long total = countStoryboardMedia(projectId, mediaType, accessibleProjectIds);
            return buildResult(entries, total, page, size);
        } else {
            List<MediaEntry> entries = queryAssetMedia(projectId, mediaType, page, size, accessibleProjectIds);
            long total = countAssetMedia(projectId, mediaType, accessibleProjectIds);
            return buildResult(entries, total, page, size);
        }
    }

    private Map<String, Object> buildResult(List<MediaEntry> records, long total, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ========== 分镜媒体 ==========

    private List<MediaEntry> queryStoryboardMedia(Long projectId, String mediaType, int page, int size,
                                                   Set<Long> accessibleProjectIds) {
        LambdaQueryWrapper<StoryboardItem> wrapper = new LambdaQueryWrapper<StoryboardItem>()
                .orderByDesc(StoryboardItem::getCreateTime);

        wrapper.and(w -> w.isNotNull(StoryboardItem::getGeneratedImageUrl)
                .or().isNotNull(StoryboardItem::getFirstFrameImageUrl)
                .or().isNotNull(StoryboardItem::getLastFrameImageUrl)
                .or().isNotNull(StoryboardItem::getImageUrl)
                .or().isNotNull(StoryboardItem::getGeneratedVideoUrl)
                .or().isNotNull(StoryboardItem::getVideoUrl));

        if (projectId != null) {
            Set<Long> sbIds = getStoryboardIdsByProject(projectId);
            if (sbIds.isEmpty()) return new ArrayList<>();
            wrapper.in(StoryboardItem::getStoryboardId, sbIds);
        } else if (accessibleProjectIds != null) {
            Set<Long> sbIds = getStoryboardIdsByProjects(accessibleProjectIds);
            if (sbIds.isEmpty()) return new ArrayList<>();
            wrapper.in(StoryboardItem::getStoryboardId, sbIds);
        }

        IPage<StoryboardItem> pageResult = storyboardItemMapper.selectPage(new Page<>(page, size), wrapper);
        List<StoryboardItem> items = pageResult.getRecords();
        if (items.isEmpty()) return new ArrayList<>();

        // 批量查询 Storyboard 获取 projectId
        Set<Long> sbIds = items.stream().map(StoryboardItem::getStoryboardId)
                .filter(s -> s != null).collect(Collectors.toSet());
        Map<Long, Storyboard> sbMap = new HashMap<>();
        if (!sbIds.isEmpty()) {
            List<Storyboard> sbs = storyboardMapper.selectBatchIds(sbIds);
            for (Storyboard sb : sbs) sbMap.put(sb.getId(), sb);
        }

        // 批量查询 Project 名称
        Set<Long> projectIds = sbMap.values().stream().map(Storyboard::getProjectId)
                .filter(p -> p != null).collect(Collectors.toSet());
        Map<Long, String> projectNameMap = batchQueryProjectNames(projectIds);

        boolean wantImage = mediaType == null || "image".equals(mediaType);
        boolean wantVideo = mediaType == null || "video".equals(mediaType);

        List<MediaEntry> entries = new ArrayList<>();
        for (StoryboardItem item : items) {
            Storyboard sb = sbMap.get(item.getStoryboardId());
            Long pid = sb != null ? sb.getProjectId() : null;
            String pname = pid != null ? projectNameMap.getOrDefault(pid, "") : "";
            String shotLabel = "镜头 #" + (item.getShotNumber() != null ? item.getShotNumber() : item.getId());

            // 选择主要媒体 URL（优先视频 > 生成图 > 首帧 > 尾帧 > 参考图）
            String primaryUrl = null;
            String primaryField = null;
            String primaryType = null;
            String primarySubLabel = null;

            if (wantVideo && StrUtil.isNotBlank(item.getGeneratedVideoUrl())) {
                primaryUrl = item.getGeneratedVideoUrl();
                primaryField = "generatedVideoUrl";
                primaryType = "video";
                primarySubLabel = "生成视频";
            } else if (wantVideo && StrUtil.isNotBlank(item.getVideoUrl())) {
                primaryUrl = item.getVideoUrl();
                primaryField = "videoUrl";
                primaryType = "video";
                primarySubLabel = "成品视频";
            } else if (wantImage && StrUtil.isNotBlank(item.getGeneratedImageUrl())) {
                primaryUrl = item.getGeneratedImageUrl();
                primaryField = "generatedImageUrl";
                primaryType = "image";
                primarySubLabel = "生成图";
            } else if (wantImage && StrUtil.isNotBlank(item.getFirstFrameImageUrl())) {
                primaryUrl = item.getFirstFrameImageUrl();
                primaryField = "firstFrameImageUrl";
                primaryType = "image";
                primarySubLabel = "首帧";
            } else if (wantImage && StrUtil.isNotBlank(item.getLastFrameImageUrl())) {
                primaryUrl = item.getLastFrameImageUrl();
                primaryField = "lastFrameImageUrl";
                primaryType = "image";
                primarySubLabel = "尾帧";
            } else if (wantImage && StrUtil.isNotBlank(item.getImageUrl())) {
                primaryUrl = item.getImageUrl();
                primaryField = "imageUrl";
                primaryType = "image";
                primarySubLabel = "参考图";
            }

            if (primaryUrl != null) {
                entries.add(buildEntry("storyboard", primaryType, primaryUrl, null,
                        shotLabel, primarySubLabel, pid, pname,
                        item.getCreateTime(), item.getId(), primaryField));
            }
        }
        return entries;
    }

    private long countStoryboardMedia(Long projectId, String mediaType, Set<Long> accessibleProjectIds) {
        LambdaQueryWrapper<StoryboardItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.isNotNull(StoryboardItem::getGeneratedImageUrl)
                .or().isNotNull(StoryboardItem::getFirstFrameImageUrl)
                .or().isNotNull(StoryboardItem::getLastFrameImageUrl)
                .or().isNotNull(StoryboardItem::getImageUrl)
                .or().isNotNull(StoryboardItem::getGeneratedVideoUrl)
                .or().isNotNull(StoryboardItem::getVideoUrl));

        if (projectId != null) {
            Set<Long> sbIds = getStoryboardIdsByProject(projectId);
            if (sbIds.isEmpty()) return 0;
            wrapper.in(StoryboardItem::getStoryboardId, sbIds);
        } else if (accessibleProjectIds != null) {
            Set<Long> sbIds = getStoryboardIdsByProjects(accessibleProjectIds);
            if (sbIds.isEmpty()) return 0;
            wrapper.in(StoryboardItem::getStoryboardId, sbIds);
        }

        return storyboardItemMapper.selectCount(wrapper);
    }

    // ========== 资产媒体 ==========

    private List<MediaEntry> queryAssetMedia(Long projectId, String mediaType, int page, int size,
                                             Set<Long> accessibleProjectIds) {
        if ("video".equals(mediaType)) return new ArrayList<>();

        LambdaQueryWrapper<AssetItem> wrapper = new LambdaQueryWrapper<AssetItem>()
                .isNotNull(AssetItem::getImageUrl)
                .ne(AssetItem::getImageUrl, "")
                .orderByDesc(AssetItem::getCreateTime);

        if (projectId != null) {
            Set<Long> assetIds = getAssetIdsByProject(projectId);
            if (assetIds.isEmpty()) return new ArrayList<>();
            wrapper.in(AssetItem::getAssetId, assetIds);
        } else if (accessibleProjectIds != null) {
            Set<Long> assetIds = getAssetIdsByProjects(accessibleProjectIds);
            if (assetIds.isEmpty()) return new ArrayList<>();
            wrapper.in(AssetItem::getAssetId, assetIds);
        }

        IPage<AssetItem> pageResult = assetItemMapper.selectPage(new Page<>(page, size), wrapper);
        List<AssetItem> items = pageResult.getRecords();
        if (items.isEmpty()) return new ArrayList<>();

        // 批量查询 Asset 获取 projectId 和 name
        Set<Long> assetIds = items.stream().map(AssetItem::getAssetId)
                .filter(a -> a != null).collect(Collectors.toSet());
        Map<Long, Asset> assetMap = new HashMap<>();
        if (!assetIds.isEmpty()) {
            List<Asset> assets = assetMapper.selectBatchIds(assetIds);
            for (Asset a : assets) assetMap.put(a.getId(), a);
        }

        // 批量查询 Project 名称
        Set<Long> projectIds = assetMap.values().stream().map(Asset::getProjectId)
                .filter(p -> p != null).collect(Collectors.toSet());
        Map<Long, String> projectNameMap = batchQueryProjectNames(projectIds);

        List<MediaEntry> entries = new ArrayList<>();
        for (AssetItem item : items) {
            Asset asset = assetMap.get(item.getAssetId());
            if (asset == null) continue;
            Long pid = asset.getProjectId();
            String pname = pid != null ? projectNameMap.getOrDefault(pid, "") : "";

            entries.add(buildEntry("asset", "image", item.getImageUrl(),
                    item.getThumbnailUrl(), asset.getName(),
                    item.getItemType() != null ? item.getItemType() : "子资产",
                    pid, pname, item.getCreateTime(), item.getId(), "imageUrl"));
        }
        return entries;
    }

    private long countAssetMedia(Long projectId, String mediaType, Set<Long> accessibleProjectIds) {
        if ("video".equals(mediaType)) return 0;

        LambdaQueryWrapper<AssetItem> wrapper = new LambdaQueryWrapper<AssetItem>()
                .isNotNull(AssetItem::getImageUrl)
                .ne(AssetItem::getImageUrl, "");

        if (projectId != null) {
            Set<Long> assetIds = getAssetIdsByProject(projectId);
            if (assetIds.isEmpty()) return 0;
            wrapper.in(AssetItem::getAssetId, assetIds);
        } else if (accessibleProjectIds != null) {
            Set<Long> assetIds = getAssetIdsByProjects(accessibleProjectIds);
            if (assetIds.isEmpty()) return 0;
            wrapper.in(AssetItem::getAssetId, assetIds);
        }

        return assetItemMapper.selectCount(wrapper);
    }

    // ========== 工具方法 ==========

    private Set<Long> getStoryboardIdsByProject(Long projectId) {
        List<Storyboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<Storyboard>().eq(Storyboard::getProjectId, projectId));
        return storyboards.stream().map(Storyboard::getId).collect(Collectors.toSet());
    }

    private Set<Long> getStoryboardIdsByProjects(Set<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return Collections.emptySet();
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                        .in(Storyboard::getProjectId, projectIds)).stream()
                .map(Storyboard::getId).collect(Collectors.toSet());
    }

    private Set<Long> getAssetIdsByProject(Long projectId) {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, projectId));
        return assets.stream().map(Asset::getId).collect(Collectors.toSet());
    }

    private Set<Long> getAssetIdsByProjects(Set<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return Collections.emptySet();
        return assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .in(Asset::getProjectId, projectIds)).stream()
                .map(Asset::getId).collect(Collectors.toSet());
    }

    private Map<Long, String> batchQueryProjectNames(Set<Long> projectIds) {
        Map<Long, String> map = new HashMap<>();
        if (projectIds == null || projectIds.isEmpty()) return map;
        List<Project> projects = projectMapper.selectBatchIds(projectIds);
        for (Project p : projects) map.put(p.getId(), p.getName());
        return map;
    }

    private MediaEntry buildEntry(String source, String mediaType, String url, String thumbnailUrl,
                                   String label, String subLabel, Long projectId, String projectName,
                                   Object createTime, Long entityId, String field) {
        MediaEntry entry = new MediaEntry();
        entry.id = source + ":" + entityId + ":" + field;
        entry.source = source;
        entry.mediaType = mediaType;
        entry.url = url;
        entry.thumbnailUrl = thumbnailUrl;
        entry.label = label;
        entry.subLabel = subLabel;
        entry.projectId = projectId;
        entry.projectName = projectName;
        entry.createTime = createTime;
        entry.entityId = entityId;
        entry.field = field;
        return entry;
    }

    /**
     * 批量清除媒体 URL
     *
     * @param entries 要清除的条目列表，每项含 source/entityId/field
     * @return 清除的数量
     */
    public int batchClearMedia(List<Map<String, Object>> entries) {
        return batchClearMedia(entries, null);
    }

    /**
     * 批量清除媒体 URL，带逐条归属验证。
     * <p>
     * 当 userId 非 null 且非 admin 时，逐条验证媒体所属项目是否可被用户访问，
     * 不可访问的条目跳过并记录安全日志。
     *
     * @param entries 要清除的条目列表，每项含 source/entityId/field
     * @param userId  当前用户ID（null 表示不做验证）
     * @return 清除的数量
     */
    @Transactional
    public int batchClearMedia(List<Map<String, Object>> entries, Long userId) {
        Set<Long> accessibleProjectIds = null;
        if (userId != null && !userService.isAdmin(userId)) {
            accessibleProjectIds = getAccessibleProjectIds(userId);
        }

        int cleared = 0;
        for (Map<String, Object> entry : entries) {
            String src = (String) entry.get("source");
            Long entityId = entry.get("entityId") != null ? Long.valueOf(entry.get("entityId").toString()) : null;
            String field = (String) entry.get("field");
            if (entityId == null || StrUtil.isBlank(field)) continue;
            String column = allowedMediaColumn(src, field);
            if (column == null) {
                log.warn("[MediaLibrary] 非法媒体字段: source={}, field={}", src, field);
                continue;
            }

            if (accessibleProjectIds != null) {
                Long entryProjectId = resolveEntryProjectId(src, entityId);
                if (entryProjectId == null || !accessibleProjectIds.contains(entryProjectId)) {
                    log.warn("[MediaLibrary] 越权清除拦截: userId={}, source={}, entityId={}, field={}",
                            userId, src, entityId, field);
                    continue;
                }
            }

            if ("storyboard".equals(src)) {
                UpdateWrapper<StoryboardItem> wrapper = new UpdateWrapper<StoryboardItem>()
                        .eq("id", entityId)
                        .set(column, null);
                int rows = storyboardItemMapper.update(null, wrapper);
                if (rows > 0) {
                    cleared++;
                    log.info("[MediaLibrary] 清除分镜媒体: itemId={}, field={}", entityId, field);
                }
            } else if ("asset".equals(src)) {
                UpdateWrapper<AssetItem> wrapper = new UpdateWrapper<AssetItem>()
                        .eq("id", entityId)
                        .set(column, null);
                int rows = assetItemMapper.update(null, wrapper);
                if (rows > 0) {
                    cleared++;
                    log.info("[MediaLibrary] 清除资产媒体: itemId={}, field={}", entityId, field);
                }
            }
        }
        return cleared;
    }

    private String allowedMediaColumn(String source, String field) {
        if ("storyboard".equals(source)) {
            return Map.of(
                    "generatedVideoUrl", "generated_video_url",
                    "videoUrl", "video_url",
                    "generatedImageUrl", "generated_image_url",
                    "firstFrameImageUrl", "first_frame_image_url",
                    "lastFrameImageUrl", "last_frame_image_url",
                    "imageUrl", "image_url"
            ).get(field);
        }
        if ("asset".equals(source)) {
            return Map.of(
                    "imageUrl", "image_url",
                    "thumbnailUrl", "thumbnail_url"
            ).get(field);
        }
        return null;
    }

    private Set<Long> getAccessibleProjectIds(Long userId) {
        return projectService.listAccessibleByUser(userId).stream()
                .map(Project::getId)
                .collect(Collectors.toSet());
    }

    private Long resolveEntryProjectId(String source, Long entityId) {
        if ("storyboard".equals(source)) {
            StoryboardItem item = storyboardItemMapper.selectById(entityId);
            if (item == null || item.getStoryboardId() == null) return null;
            Storyboard sb = storyboardMapper.selectById(item.getStoryboardId());
            return sb != null ? sb.getProjectId() : null;
        } else if ("asset".equals(source)) {
            AssetItem item = assetItemMapper.selectById(entityId);
            if (item == null || item.getAssetId() == null) return null;
            Asset asset = assetMapper.selectById(item.getAssetId());
            return asset != null ? asset.getProjectId() : null;
        }
        return null;
    }
}
