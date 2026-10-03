package com.tonyhe.fusion.service.storyboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.controller.storyboard.vo.StoryboardSceneStateTimelineRespVO;
import com.tonyhe.fusion.controller.storyboard.vo.StoryboardSceneStateTimelineUpdateReqVO;
import com.tonyhe.fusion.controller.storyboard.vo.StoryboardShotSceneStateUpdateReqVO;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.mapper.storyboard.StoryboardItemMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutAnchor;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutDefinition;
import com.tonyhe.fusion.service.storyboard.state.FixedFurnitureLayout;
import com.tonyhe.fusion.service.storyboard.state.SceneObjectState;
import com.tonyhe.fusion.service.storyboard.state.SceneStateChange;
import com.tonyhe.fusion.service.storyboard.state.SceneStateDelta;
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SceneStateTimelineService {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9:_-]{1,80}");
    private static final Set<String> OPERATIONS = Set.of(
            "UPSERT", "ADD", "MOVE", "SET_STATE", "SHOW", "HIDE", "REMOVE");
    private static final Set<String> CONTINUITY_MODES = Set.of("CUT", "SEAMLESS");
    private static final Set<String> FURNITURE_ARRANGEMENTS = Set.of("grid", "row", "line", "cluster", "custom");

    private final StoryboardMapper storyboardMapper;
    private final StoryboardSceneMapper sceneMapper;
    private final StoryboardItemMapper itemMapper;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public StoryboardSceneStateTimelineRespVO getTimeline(Long sceneId) {
        StoryboardScene scene = requireScene(sceneId);
        List<StoryboardItem> items = listItems(sceneId);
        return buildResponse(scene, items);
    }

    @Transactional
    public StoryboardSceneStateTimelineRespVO updateTimeline(
            Long sceneId,
            StoryboardSceneStateTimelineUpdateReqVO request) {
        StoryboardScene scene = requireScene(sceneId);
        SceneLayoutDefinition layout = normalizeLayout(request.getLayoutDefinition());
        SceneStateSnapshot initialState = normalizeSnapshot(request.getInitialState());

        List<StoryboardItem> existingItems = listItems(sceneId);
        Map<Long, StoryboardItem> itemMap = existingItems.stream()
                .collect(Collectors.toMap(StoryboardItem::getId, item -> item));
        List<StoryboardShotSceneStateUpdateReqVO> requestedShots = request.getShots() == null
                ? List.of() : request.getShots();
        Set<Long> requestItemIds = new HashSet<>();
        for (StoryboardShotSceneStateUpdateReqVO shot : requestedShots) {
            if (shot == null || shot.getItemId() == null || !requestItemIds.add(shot.getItemId())) {
                throw new BusinessException("镜头状态列表包含空ID或重复ID");
            }
            if (!itemMap.containsKey(shot.getItemId())) {
                throw new BusinessException("镜头不属于当前场次: " + shot.getItemId());
            }
        }
        if (!requestItemIds.equals(itemMap.keySet())) {
            throw new BusinessException("整场时间线更新必须包含当前场次的全部镜头");
        }
        validateAssetReferences(scene, request.getBaseSceneAssetItemId(), layout, initialState,
                requestedShots);

        UpdateWrapper<StoryboardScene> sceneUpdate = new UpdateWrapper<StoryboardScene>()
                .eq("id", sceneId)
                .set("base_scene_asset_item_id", request.getBaseSceneAssetItemId())
                .set("layout_definition", writeJson(layout))
                .set("initial_scene_state", writeJson(initialState));
        sceneMapper.update(null, sceneUpdate);

        for (StoryboardShotSceneStateUpdateReqVO shot : requestedShots) {
            SceneStateDelta delta = normalizeDelta(shot.getDelta());
            String continuityMode = normalizeContinuityMode(shot.getContinuityMode());
            itemMapper.update(null, new UpdateWrapper<StoryboardItem>()
                    .eq("id", shot.getItemId())
                    .set("scene_asset_item_id", shot.getSceneAssetItemId())
                    .set("scene_state_delta", writeJson(delta))
                    .set("continuity_mode", continuityMode));
        }

        rebuild(sceneId);
        return getTimeline(sceneId);
    }

    @Transactional
    public void rebuild(Long sceneId) {
        StoryboardScene scene = requireScene(sceneId);
        SceneLayoutDefinition layout = normalizeLayout(readJson(
                scene.getLayoutDefinition(), SceneLayoutDefinition.class, new SceneLayoutDefinition()));
        SceneStateSnapshot current = normalizeSnapshot(readJson(
                scene.getInitialSceneState(), SceneStateSnapshot.class, new SceneStateSnapshot()));
        String canonicalLayout = writeJson(layout);
        int version = (scene.getStateTimelineVersion() == null ? 0 : scene.getStateTimelineVersion()) + 1;

        for (StoryboardItem item : listItems(sceneId)) {
            SceneStateSnapshot before = normalizeSnapshot(current);
            SceneStateDelta delta = normalizeDelta(readJson(
                    item.getSceneStateDelta(), SceneStateDelta.class, new SceneStateDelta()));
            SceneStateSnapshot after = applyDelta(before, delta);
            Long effectiveSceneAssetItemId = item.getSceneAssetItemId() != null
                    ? item.getSceneAssetItemId() : scene.getBaseSceneAssetItemId();
            String contextHash = contextHash(effectiveSceneAssetItemId, layout);
            String beforeJson = writeJson(before);
            String deltaJson = writeJson(delta);
            String afterJson = writeJson(after);
            String beforeHash = hash(contextHash + "|" + beforeJson);
            String afterHash = hash(contextHash + "|" + afterJson);
            String continuityMode = normalizeContinuityMode(item.getContinuityMode());
            String sceneStateHash = hash(beforeHash + "|" + deltaJson + "|" + afterHash + "|" + continuityMode);

            itemMapper.update(null, new UpdateWrapper<StoryboardItem>()
                    .eq("id", item.getId())
                    .set("scene_state_before", beforeJson)
                    .set("scene_state_delta", deltaJson)
                    .set("scene_state_after", afterJson)
                    .set("state_before_hash", beforeHash)
                    .set("state_after_hash", afterHash)
                    .set("scene_state_hash", sceneStateHash)
                    .set("state_timeline_version", version)
                    .set("continuity_mode", continuityMode));
            current = after;
        }

        sceneMapper.update(null, new UpdateWrapper<StoryboardScene>()
                .eq("id", sceneId)
                .set("layout_definition", canonicalLayout)
                .set("initial_scene_state", writeJson(normalizeSnapshot(readJson(
                        scene.getInitialSceneState(), SceneStateSnapshot.class, new SceneStateSnapshot()))))
                .set("state_timeline_version", version));
        log.info("场景状态时间线已重建: sceneId={}, version={}", sceneId, version);
    }

    public SceneStateSnapshot parseSnapshot(String json) {
        return normalizeSnapshot(readJson(json, SceneStateSnapshot.class, new SceneStateSnapshot()));
    }

    public SceneStateDelta parseDelta(String json) {
        return normalizeDelta(readJson(json, SceneStateDelta.class, new SceneStateDelta()));
    }

    public SceneLayoutDefinition parseLayout(String json) {
        return normalizeLayout(readJson(json, SceneLayoutDefinition.class, new SceneLayoutDefinition()));
    }

    private StoryboardSceneStateTimelineRespVO buildResponse(
            StoryboardScene scene,
            List<StoryboardItem> items) {
        List<StoryboardSceneStateTimelineRespVO.ShotStateVO> shots = items.stream()
                .map(item -> StoryboardSceneStateTimelineRespVO.ShotStateVO.builder()
                        .itemId(item.getId())
                        .shotNumber(item.getShotNumber())
                        .sortOrder(item.getSortOrder())
                        .content(item.getContent())
                        .sceneAssetItemId(item.getSceneAssetItemId())
                        .effectiveSceneAssetItemId(item.getSceneAssetItemId() != null
                                ? item.getSceneAssetItemId() : scene.getBaseSceneAssetItemId())
                        .continuityMode(normalizeContinuityMode(item.getContinuityMode()))
                        .before(parseSnapshot(item.getSceneStateBefore()))
                        .delta(parseDelta(item.getSceneStateDelta()))
                        .after(parseSnapshot(item.getSceneStateAfter()))
                        .stateBeforeHash(item.getStateBeforeHash())
                        .stateAfterHash(item.getStateAfterHash())
                        .sceneStateHash(item.getSceneStateHash())
                        .firstFrameStale(isStale(item.getFirstFrameImageUrl(),
                                item.getStateBeforeHash(), item.getFirstFrameStateHash()))
                        .lastFrameStale(isStale(item.getLastFrameImageUrl(),
                                item.getStateAfterHash(), item.getLastFrameStateHash()))
                        .videoStale(isStale(item.getGeneratedVideoUrl(),
                                item.getSceneStateHash(), item.getGeneratedVideoStateHash()))
                        .build())
                .toList();
        return StoryboardSceneStateTimelineRespVO.builder()
                .sceneId(scene.getId())
                .baseSceneAssetItemId(scene.getBaseSceneAssetItemId())
                .layoutDefinition(parseLayout(scene.getLayoutDefinition()))
                .initialState(parseSnapshot(scene.getInitialSceneState()))
                .timelineVersion(scene.getStateTimelineVersion())
                .shots(shots)
                .build();
    }

    private void validateAssetReferences(
            StoryboardScene scene,
            Long baseSceneAssetItemId,
            SceneLayoutDefinition layout,
            SceneStateSnapshot initialState,
            List<StoryboardShotSceneStateUpdateReqVO> shots) {
        Storyboard storyboard = storyboardMapper.selectById(scene.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException("分镜脚本不存在: " + scene.getStoryboardId());
        }

        Set<Long> sceneItemIds = new HashSet<>();
        Set<Long> propItemIds = new HashSet<>();
        if (baseSceneAssetItemId != null) {
            sceneItemIds.add(baseSceneAssetItemId);
        }
        Set<String> layoutObjectIds = normalizeLayout(layout).getAnchors().stream()
                .map(anchor -> "layout:" + anchor.getId())
                .collect(Collectors.toSet());
        collectAssetItemIds(initialState, layoutObjectIds, propItemIds);
        List<SceneStateDelta> normalizedDeltas = new ArrayList<>();
        if (shots != null) {
            for (StoryboardShotSceneStateUpdateReqVO shot : shots) {
                if (shot != null && shot.getSceneAssetItemId() != null) {
                    sceneItemIds.add(shot.getSceneAssetItemId());
                }
                SceneStateDelta delta = normalizeDelta(shot != null ? shot.getDelta() : null);
                normalizedDeltas.add(delta);
                for (SceneStateChange change : delta.getChanges()) {
                    collectAssetItemId(change.getObjectId(), layoutObjectIds, propItemIds);
                    collectContainerAssetItemId(change.getContainerId(), layoutObjectIds, propItemIds);
                }
            }
        }
        collectContainerAssetItemIds(initialState, layoutObjectIds, propItemIds);
        validateContainerReferences(layout, initialState, normalizedDeltas);

        Set<Long> itemIds = new HashSet<>(sceneItemIds);
        itemIds.addAll(propItemIds);
        Map<Long, AssetItem> assetItems = assetService.listItemsByIds(itemIds).stream()
                .collect(Collectors.toMap(AssetItem::getId, item -> item));
        Set<Long> assetIds = assetItems.values().stream()
                .map(AssetItem::getAssetId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, Asset> assets = assetService.listByIds(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset));

        for (Long itemId : itemIds) {
            AssetItem item = assetItems.get(itemId);
            Asset asset = item != null ? assets.get(item.getAssetId()) : null;
            if (item == null || asset == null) {
                throw new BusinessException("场景状态引用的资产子项不存在: " + itemId);
            }
            if (!storyboard.getProjectId().equals(asset.getProjectId())) {
                throw new BusinessException("场景状态引用了其他项目的资产子项: " + itemId);
            }
            if (sceneItemIds.contains(itemId) && !"scene".equals(asset.getType())) {
                throw new BusinessException("场次或镜头场景变体必须是场景类型: " + itemId);
            }
            if (propItemIds.contains(itemId) && !"prop".equals(asset.getType())) {
                throw new BusinessException("可移动对象必须引用道具资产，固定物体请使用 layout: ID: " + itemId);
            }
        }
    }

    private void collectAssetItemIds(
            SceneStateSnapshot snapshot,
            Set<String> layoutObjectIds,
            Set<Long> ids) {
        if (snapshot == null || snapshot.getObjects() == null) {
            return;
        }
        for (SceneObjectState object : snapshot.getObjects()) {
            collectAssetItemId(object != null ? object.getObjectId() : null, layoutObjectIds, ids);
        }
    }

    private void collectContainerAssetItemIds(
            SceneStateSnapshot snapshot,
            Set<String> layoutObjectIds,
            Set<Long> ids) {
        if (snapshot == null || snapshot.getObjects() == null) {
            return;
        }
        for (SceneObjectState object : snapshot.getObjects()) {
            collectContainerAssetItemId(object != null ? object.getContainerId() : null, layoutObjectIds, ids);
        }
    }

    private void collectContainerAssetItemId(String containerId, Set<String> layoutObjectIds, Set<Long> ids) {
        String normalized = normalizeContainerId(containerId);
        if (normalized == null) {
            return;
        }
        collectAssetItemId(normalized, layoutObjectIds, ids);
    }

    private void collectAssetItemId(String objectId, Set<String> layoutObjectIds, Set<Long> ids) {
        String normalized = normalizeObjectId(objectId);
        if (normalized.startsWith("layout:")) {
            if (!layoutObjectIds.contains(normalized)) {
                throw new BusinessException("场景状态引用了不存在的布局锚点: " + normalized);
            }
            return;
        }
        if (!normalized.startsWith("assetItem:")) {
            throw new BusinessException("场景对象ID必须使用 assetItem:<ID> 或 layout:<锚点ID>: " + normalized);
        }
        try {
            ids.add(Long.parseLong(normalized.substring("assetItem:".length())));
        } catch (NumberFormatException e) {
            throw new BusinessException("非法道具对象ID: " + normalized);
        }
    }

    /**
     * 容器不允许从位置文本推断。固定容器必须在布局中显式标记；道具容器必须已进入当前或更早镜头的状态，
     * 且显式标记为可收纳。这样 "收起" 不会把普通课桌、桌面或椅子变成抽屉。
     */
    private void validateContainerReferences(
            SceneLayoutDefinition layout,
            SceneStateSnapshot initialState,
            List<SceneStateDelta> deltas) {
        Map<String, Boolean> containerCapabilities = new LinkedHashMap<>();
        for (SceneLayoutAnchor anchor : layout.getAnchors()) {
            containerCapabilities.put("layout:" + anchor.getId(), Boolean.TRUE.equals(anchor.getCanContainObjects()));
        }
        for (SceneObjectState object : initialState.getObjects()) {
            containerCapabilities.put(object.getObjectId(), Boolean.TRUE.equals(object.getCanContainObjects()));
        }
        for (SceneObjectState object : initialState.getObjects()) {
            validateContainerReference(object.getObjectId(), object.getContainerId(), containerCapabilities);
        }
        for (SceneStateDelta delta : deltas) {
            // 同一镜头中新增的纸箱、柜体等可以在该镜头的其他变化中被使用，但必须显式声明可收纳。
            for (SceneStateChange change : delta.getChanges()) {
                if (!containerCapabilities.containsKey(change.getObjectId())) {
                    containerCapabilities.put(change.getObjectId(), Boolean.FALSE);
                }
                if (change.getCanContainObjects() != null) {
                    containerCapabilities.put(change.getObjectId(), change.getCanContainObjects());
                }
            }
            for (SceneStateChange change : delta.getChanges()) {
                validateContainerReference(change.getObjectId(), change.getContainerId(), containerCapabilities);
            }
        }
    }

    private void validateContainerReference(
            String objectId,
            String containerId,
            Map<String, Boolean> containerCapabilities) {
        String normalized = normalizeContainerId(containerId);
        if (normalized == null) {
            return;
        }
        if (normalized.equals(objectId)) {
            throw new BusinessException("对象不能将自身作为容器: " + normalized);
        }
        Boolean canContainObjects = containerCapabilities.get(normalized);
        if (canContainObjects == null) {
            throw new BusinessException("容器必须先作为固定布局锚点或场景对象明确声明: " + normalized);
        }
        if (!canContainObjects) {
            throw new BusinessException("引用的对象未标记为可收纳容器，不能作为 containerId: " + normalized);
        }
    }

    SceneStateSnapshot applyDelta(SceneStateSnapshot source, SceneStateDelta delta) {
        Map<String, SceneObjectState> objects = new TreeMap<>();
        for (SceneObjectState object : normalizeSnapshot(source).getObjects()) {
            objects.put(object.getObjectId(), copyObject(object));
        }
        for (SceneStateChange change : delta.getChanges()) {
            String operation = change.getOperation();
            SceneObjectState target = objects.get(change.getObjectId());
            if (target == null) {
                target = SceneObjectState.builder()
                        .objectId(change.getObjectId())
                        .objectName(StrUtil.blankToDefault(change.getObjectName(), change.getObjectId()))
                        .visible(true)
                        .build();
                objects.put(change.getObjectId(), target);
            }
            if (StrUtil.isNotBlank(change.getObjectName())) target.setObjectName(change.getObjectName().trim());
            if (StrUtil.isNotBlank(change.getLocation())) target.setLocation(change.getLocation().trim());
            if (StrUtil.isNotBlank(change.getState())) target.setState(change.getState().trim());
            if (StrUtil.isNotBlank(change.getContainerId())) target.setContainerId(change.getContainerId().trim());
            if ("MOVE".equals(operation) && StrUtil.isBlank(change.getContainerId())) target.setContainerId(null);
            if (change.getCanContainObjects() != null) target.setCanContainObjects(change.getCanContainObjects());
            if (change.getVisible() != null) target.setVisible(change.getVisible());
            if ("SHOW".equals(operation)) target.setVisible(true);
            if ("HIDE".equals(operation) || "REMOVE".equals(operation)) target.setVisible(false);
            if ("REMOVE".equals(operation) && StrUtil.isBlank(change.getState())) {
                target.setState("已移出场景");
            }
        }
        return SceneStateSnapshot.builder().objects(new ArrayList<>(objects.values())).build();
    }

    private SceneLayoutDefinition normalizeLayout(SceneLayoutDefinition input) {
        List<SceneLayoutAnchor> anchors = input == null || input.getAnchors() == null
                ? List.of() : input.getAnchors();
        if (anchors.size() > 100) throw new BusinessException("场景布局锚点不能超过100个");
        Map<String, SceneLayoutAnchor> normalized = new TreeMap<>();
        for (SceneLayoutAnchor anchor : anchors) {
            if (anchor == null || StrUtil.isBlank(anchor.getId()) || !STABLE_ID.matcher(anchor.getId().trim()).matches()) {
                throw new BusinessException("布局锚点ID只能包含字母、数字、冒号、下划线和连字符");
            }
            String id = anchor.getId().trim();
            if (normalized.containsKey(id)) throw new BusinessException("布局锚点ID重复: " + id);
            normalized.put(id, SceneLayoutAnchor.builder()
                    .id(id)
                    .name(trimToNull(anchor.getName()))
                    .kind(StrUtil.blankToDefault(trimToNull(anchor.getKind()), "fixed"))
                    .description(trimToNull(anchor.getDescription()))
                    .furnitureLayout(normalizeFurnitureLayout(anchor.getFurnitureLayout()))
                    .canContainObjects(Boolean.TRUE.equals(anchor.getCanContainObjects()))
                    .build());
        }
        return SceneLayoutDefinition.builder().anchors(new ArrayList<>(normalized.values())).build();
    }

    private SceneStateSnapshot normalizeSnapshot(SceneStateSnapshot input) {
        List<SceneObjectState> objects = input == null || input.getObjects() == null
                ? List.of() : input.getObjects();
        if (objects.size() > 200) throw new BusinessException("场景状态对象不能超过200个");
        Map<String, SceneObjectState> normalized = new TreeMap<>();
        for (SceneObjectState object : objects) {
            if (object == null) continue;
            String objectId = normalizeObjectId(object.getObjectId());
            if (normalized.containsKey(objectId)) throw new BusinessException("场景对象ID重复: " + objectId);
            normalized.put(objectId, SceneObjectState.builder()
                    .objectId(objectId)
                    .objectName(StrUtil.blankToDefault(trimToNull(object.getObjectName()), objectId))
                    .location(trimToNull(object.getLocation()))
                    .state(trimToNull(object.getState()))
                    .containerId(normalizeContainerId(object.getContainerId()))
                    .canContainObjects(Boolean.TRUE.equals(object.getCanContainObjects()))
                    .visible(object.getVisible() == null || object.getVisible())
                    .build());
        }
        return SceneStateSnapshot.builder().objects(new ArrayList<>(normalized.values())).build();
    }

    private SceneStateDelta normalizeDelta(SceneStateDelta input) {
        List<SceneStateChange> changes = input == null || input.getChanges() == null
                ? List.of() : input.getChanges();
        if (changes.size() > 100) throw new BusinessException("单镜头场景变化不能超过100个");
        List<SceneStateChange> normalized = new ArrayList<>();
        for (SceneStateChange change : changes) {
            if (change == null) continue;
            String operation = StrUtil.blankToDefault(change.getOperation(), "UPSERT")
                    .trim().toUpperCase(Locale.ROOT);
            if (!OPERATIONS.contains(operation)) throw new BusinessException("不支持的场景状态操作: " + operation);
            String objectId = normalizeObjectId(change.getObjectId());
            if (objectId.startsWith("layout:")
                    && Set.of("MOVE", "ADD", "REMOVE").contains(operation)) {
                throw new BusinessException("固定布局锚点不允许移动、添加或移出: " + objectId);
            }
            if (objectId.startsWith("layout:") && change.getCanContainObjects() != null) {
                throw new BusinessException("固定布局锚点的容器能力只能在布局定义中设置: " + objectId);
            }
            if ("MOVE".equals(operation) && StrUtil.isBlank(change.getLocation())) {
                throw new BusinessException("MOVE 操作必须提供目标位置: " + objectId);
            }
            normalized.add(SceneStateChange.builder()
                    .operation(operation)
                    .objectId(objectId)
                    .objectName(trimToNull(change.getObjectName()))
                    .location(trimToNull(change.getLocation()))
                    .state(trimToNull(change.getState()))
                    .containerId(normalizeContainerId(change.getContainerId()))
                    .canContainObjects(change.getCanContainObjects())
                    .visible(change.getVisible())
                    .build());
        }
        return SceneStateDelta.builder().changes(normalized).build();
    }

    private FixedFurnitureLayout normalizeFurnitureLayout(FixedFurnitureLayout input) {
        if (input == null) {
            return null;
        }
        String arrangement = trimToNull(input.getArrangement());
        if (arrangement != null) {
            arrangement = arrangement.toLowerCase(Locale.ROOT);
            if (!FURNITURE_ARRANGEMENTS.contains(arrangement)) {
                throw new BusinessException("固定家具组排列方式不支持: " + arrangement);
            }
        }
        Integer rows = input.getRows();
        Integer columns = input.getColumns();
        if (rows != null && (rows <= 0 || rows > 100)) {
            throw new BusinessException("固定家具组行数必须在 1 到 100 之间");
        }
        if (columns != null && (columns <= 0 || columns > 100)) {
            throw new BusinessException("固定家具组列数必须在 1 到 100 之间");
        }
        if ("grid".equals(arrangement) && (rows == null || columns == null)) {
            throw new BusinessException("网格固定家具组必须同时提供行数和列数");
        }
        List<String> prohibited = input.getProhibitedFeatures() == null ? List.of()
                : input.getProhibitedFeatures().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(20)
                .toList();
        return FixedFurnitureLayout.builder()
                .arrangement(arrangement)
                .rows(rows)
                .columns(columns)
                .aisleDescription(trimToNull(input.getAisleDescription()))
                .empty(Boolean.TRUE.equals(input.getEmpty()))
                .prohibitedFeatures(prohibited)
                .build();
    }

    private String normalizeContainerId(String containerId) {
        String normalized = trimToNull(containerId);
        if (normalized == null) {
            return null;
        }
        if (!STABLE_ID.matcher(normalized).matches()) {
            throw new BusinessException("容器ID只能包含字母、数字、冒号、下划线和连字符");
        }
        return normalized;
    }

    private String normalizeObjectId(String objectId) {
        String normalized = trimToNull(objectId);
        if (normalized == null || !STABLE_ID.matcher(normalized).matches()) {
            throw new BusinessException("对象ID只能包含字母、数字、冒号、下划线和连字符");
        }
        return normalized;
    }

    private String normalizeContinuityMode(String value) {
        String normalized = StrUtil.blankToDefault(value, "CUT").trim().toUpperCase(Locale.ROOT);
        if (!CONTINUITY_MODES.contains(normalized)) {
            throw new BusinessException("镜头衔接模式仅支持 CUT 或 SEAMLESS");
        }
        return normalized;
    }

    private StoryboardScene requireScene(Long sceneId) {
        StoryboardScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException("分镜场次不存在: " + sceneId);
        return scene;
    }

    private List<StoryboardItem> listItems(Long sceneId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardSceneId, sceneId)
                .orderByAsc(StoryboardItem::getSortOrder)
                .orderByAsc(StoryboardItem::getId));
    }

    private <T> T readJson(String json, Class<T> type, T fallback) {
        if (StrUtil.isBlank(json)) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("忽略无法解析的旧场景状态数据: type={}, error={}", type.getSimpleName(), e.getMessage());
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("场景状态序列化失败: " + e.getOriginalMessage());
        }
    }

    private static String hash(String value) {
        return DigestUtil.sha256Hex(value);
    }

    String contextHash(Long sceneAssetItemId, SceneLayoutDefinition layout) {
        return hash(String.valueOf(sceneAssetItemId) + "|" + writeJson(normalizeLayout(layout)));
    }

    private static String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private static SceneObjectState copyObject(SceneObjectState source) {
        return SceneObjectState.builder()
                .objectId(source.getObjectId())
                .objectName(source.getObjectName())
                .location(source.getLocation())
                .state(source.getState())
                .containerId(source.getContainerId())
                .canContainObjects(source.getCanContainObjects())
                .visible(source.getVisible())
                .build();
    }

    private static boolean isStale(String mediaUrl, String expectedHash, String generatedHash) {
        return StrUtil.isNotBlank(mediaUrl)
                && StrUtil.isNotBlank(expectedHash)
                && !expectedHash.equals(generatedHash);
    }
}
