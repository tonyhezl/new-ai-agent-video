package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.config.ArtStylePresets;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.voiceover.VoiceConfigService;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import com.tonyhe.fusion.service.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询分镜场次镜头列表工具（get_storyboard_scene_items）
 * <p>
 * 返回指定场次下的所有镜头详情，含完整的镜头信息（画面内容、运镜、对白、图片、视频等）。
 * 也支持通过 storyboardItemId 查询该镜头所在场次的所有镜头（用于获取上下文）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GetStoryboardSceneItemsToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final AssetService assetService;
    private final ProjectService projectService;
    private final SystemConfigService systemConfigService;
    private final AiModelService aiModelService;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final VoiceConfigService voiceConfigService;

    /** 模型类型常量：图片生成 */
    private static final int MODEL_TYPE_IMAGE = 2;
    /** 默认参考图上限（模型能力不可用时回退） */
    private static final int DEFAULT_MAX_REFERENCE_IMAGES = 10;

    @Override
    public String getToolName() {
        return "get_storyboard_scene_items";
    }

    @Override
    public String getDisplayName() {
        return "查询场次镜头列表";
    }

    @Override
    public String getToolDescription() {
        return """
                查询分镜场次下的所有镜头详情。支持两种查询方式：
                1. 通过 sceneId 直接查询场次下的所有镜头
                2. 通过 storyboardItemId 查询该镜头所在场次的所有镜头（自动定位场次）

                返回的每个镜头包含完整信息：画面内容、景别、运镜、对白、音效、首尾帧图片URL、视频URL等。
                可用于获取上下文信息（上一个/下一个镜头），以便生成连贯的视频提示词。

                **资产引用解析**：每个镜头的 characterIds、propIds、sceneAssetItemId 会自动解析为带图片URL的资产引用信息，
                返回在 characterRefs、propRefs、sceneRef 字段中，包含子资产ID、名称、类型和图片URL，无需额外调用 query_asset_items。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardSceneId": {
                            "type": "integer",
                            "description": "分镜场次ID，直接查询该场次的所有镜头"
                        },
                        "storyboardItemId": {
                            "type": "integer",
                            "description": "分镜条目ID，自动找到所在场次并返回该场次所有镜头"
                        },
                        "frameType": {
                            "type": "string",
                            "enum": ["first", "last"],
                            "description": "生成首尾帧时传入。只有场次第一镜的首帧会携带画风参考图；尾帧和后续镜头不携带。"
                        }
                    }
                }
                """;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardSceneId = params.getLong("storyboardSceneId");
            Long storyboardItemId = params.getLong("storyboardItemId");
            String requestedFrameType = params.getStr("frameType");

            if (storyboardSceneId == null && storyboardItemId == null) {
                return errorResult("请提供 storyboardSceneId 或 storyboardItemId");
            }

            // 如果提供了 storyboardItemId，先找到所在的场次
            Long targetItemId = storyboardItemId;
            if (storyboardSceneId == null) {
                StoryboardItem targetItem = storyboardService.getItemById(storyboardItemId);
                storyboardSceneId = targetItem.getStoryboardSceneId();
                if (storyboardSceneId == null) {
                    return errorResult("该分镜条目没有关联场次");
                }
            }

            // 查询场次信息
            StoryboardScene scene = storyboardService.getSceneById(storyboardSceneId);

            // 查询项目画风参考图
            List<String> artStyleRefImageUrls = resolveArtStyleRefImages(scene.getStoryboardId());
            String artStyleReferencePrompt = resolveArtStyleReferencePrompt(scene.getStoryboardId());

            // 查询该场次下的所有镜头
            List<StoryboardItem> items = storyboardService.listItemsByScene(storyboardSceneId);

            // 收集所有镜头中引用的子资产ID，批量查询
            Set<Long> allAssetItemIds = new LinkedHashSet<>();
            for (StoryboardItem item : items) {
                collectAssetItemIds(allAssetItemIds, item.getCharacterIds());
                collectAssetItemIds(allAssetItemIds, item.getCostumeIds());
                collectAssetItemIds(allAssetItemIds, item.getPropIds());
                if (item.getSceneAssetItemId() != null) {
                    allAssetItemIds.add(item.getSceneAssetItemId());
                }
            }
            if (scene.getBaseSceneAssetItemId() != null) {
                allAssetItemIds.add(scene.getBaseSceneAssetItemId());
            }
            // 批量查询子资产信息
            Map<Long, AssetItem> assetItemMap = batchGetAssetItems(allAssetItemIds);

            // 为角色子资产批量查询同主资产下的三视图和默认常服子资产 imageUrl（单次 DB 查询）
            Map<Long, CharacterSubAssetUrls> characterSubAssetUrlMap = batchGetCharacterSubAssetUrls(assetItemMap);

            // 批量查询主资产名称（assetId → name），用于 character_ids 指向 costume 等非角色子资产时正确解析角色名
            Map<Long, String> assetNameMap = batchGetAssetNames(assetItemMap);

            // 批量查询角色音色参考音频 URL
            Map<Long, String> characterVoiceAudioUrlMap = batchGetCharacterVoiceAudioUrls(assetItemMap);

            // 动态获取图片模型参考图上限
            int maxReferenceImages = resolveMaxReferenceImages();

            JSONArray itemList = new JSONArray();
            String prevFirstFrameUrl = null; // 追踪前一个镜头的首帧 URL，用于视觉连贯性
            // 追踪前序镜头的提示词和结尾状态，用于叙事连贯性
            String prevContent = null;
            String prevFirstFramePrompt = null;
            String prevLastFramePrompt = null;
            String prevVideoPrompt = null;
            String prevLastFrameUrl = null;
            String prevStateAfterHash = null;
            boolean prevLastFrameCurrent = false;
            boolean isFirstShotInScene = true; // 仅场次首个镜头携带画风参考图，后续镜头通过首帧继承画风
            for (StoryboardItem item : items) {
                Long effectiveSceneAssetItemId = item.getSceneAssetItemId() != null
                        ? item.getSceneAssetItemId() : scene.getBaseSceneAssetItemId();
                boolean firstFrameStale = isMediaStale(item.getFirstFrameImageUrl(),
                        item.getStateBeforeHash(), item.getFirstFrameStateHash());
                boolean lastFrameStale = isMediaStale(item.getLastFrameImageUrl(),
                        item.getStateAfterHash(), item.getLastFrameStateHash());
                boolean videoStale = isMediaStale(item.getGeneratedVideoUrl(),
                        item.getSceneStateHash(), item.getGeneratedVideoStateHash());
                JSONObject itemObj = JSONUtil.createObj()
                        .set("id", item.getId())
                        .set("shotNumber", item.getShotNumber())
                        .set("beatIds", parseJson(item.getBeatIds()))
                        .set("autoShotNumber", item.getAutoShotNumber())
                        .set("sortOrder", item.getSortOrder())
                        .set("shotType", item.getShotType())
                        .set("content", item.getContent())
                        .set("sceneExpectation", item.getSceneExpectation())
                        .set("dialogue", item.getDialogue())
                        .set("sound", item.getSound())
                        .set("soundEffect", item.getSoundEffect())
                        .set("music", item.getMusic())
                        .set("duration", item.getDuration())
                        .set("cameraMovement", item.getCameraMovement())
                        .set("cameraAngle", item.getCameraAngle())
                        .set("cameraEquipment", item.getCameraEquipment())
                        .set("focalLength", item.getFocalLength())
                        .set("transition", item.getTransition())
                        .set("imageUrl", item.getImageUrl())
                        .set("generatedImageUrl", item.getGeneratedImageUrl())
                        .set("firstFrameImageUrl", item.getFirstFrameImageUrl())
                        .set("lastFrameImageUrl", item.getLastFrameImageUrl())
                        .set("firstFramePrompt", item.getFirstFramePrompt())
                        .set("firstFrameEffectivePrompt", item.getFirstFrameEffectivePrompt())
                        .set("lastFramePrompt", item.getLastFramePrompt())
                        .set("lastFrameEffectivePrompt", item.getLastFrameEffectivePrompt())
                        .set("videoUrl", item.getVideoUrl())
                        .set("generatedVideoUrl", item.getGeneratedVideoUrl())
                        .set("videoPrompt", item.getVideoPrompt())
                        .set("characterIds", item.getCharacterIds())
                        .set("costumeIds", item.getCostumeIds())
                        .set("sceneAssetItemId", item.getSceneAssetItemId())
                        .set("effectiveSceneAssetItemId", effectiveSceneAssetItemId)
                        .set("propIds", item.getPropIds())
                        .set("remark", item.getRemark())
                        .set("continuityMode", StrUtil.blankToDefault(item.getContinuityMode(), "CUT"))
                        .set("sceneStateBefore", parseJson(item.getSceneStateBefore()))
                        .set("sceneStateDelta", parseJson(item.getSceneStateDelta()))
                        .set("sceneStateAfter", parseJson(item.getSceneStateAfter()))
                        .set("stateBeforeHash", item.getStateBeforeHash())
                        .set("stateAfterHash", item.getStateAfterHash())
                        .set("sceneStateHash", item.getSceneStateHash())
                        .set("firstFrameStale", firstFrameStale)
                        .set("lastFrameStale", lastFrameStale)
                        .set("videoStale", videoStale);

                // 内联角色参考图信息（含三视图 URL）
                JSONArray characterRefs = buildCharacterRefs(item.getCharacterIds(), assetItemMap, characterSubAssetUrlMap, characterVoiceAudioUrlMap);
                if (!characterRefs.isEmpty()) {
                    itemObj.set("characterRefs", characterRefs);
                }

                // 内联服装参考图信息
                JSONArray costumeRefs = buildAssetRefs(item.getCostumeIds(), assetItemMap);
                if (!costumeRefs.isEmpty()) {
                    itemObj.set("costumeRefs", costumeRefs);
                }

                // 内联道具参考图信息
                JSONArray propRefs = buildAssetRefs(item.getPropIds(), assetItemMap);
                if (!propRefs.isEmpty()) {
                    itemObj.set("propRefs", propRefs);
                }

                // 内联场景参考图信息
                if (effectiveSceneAssetItemId != null) {
                    AssetItem sceneAssetItem = assetItemMap.get(effectiveSceneAssetItemId);
                    if (sceneAssetItem != null) {
                        itemObj.set("sceneRef", buildSingleAssetRef(sceneAssetItem));
                    }
                }

                // 标记当前目标镜头
                if (targetItemId != null && targetItemId.equals(item.getId())) {
                    itemObj.set("isCurrentTarget", true);
                }

                // 注入连贯性上下文：前序镜头的剧情/提示词/首尾帧（任一有值即注入）
                if (prevContent != null
                        || StrUtil.isNotBlank(prevFirstFramePrompt)
                        || StrUtil.isNotBlank(prevLastFramePrompt)
                        || StrUtil.isNotBlank(prevVideoPrompt)
                        || StrUtil.isNotBlank(prevFirstFrameUrl)
                        || StrUtil.isNotBlank(prevLastFrameUrl)) {
                    JSONObject continuityCtx = JSONUtil.createObj()
                            .set("prevContent", prevContent != null ? prevContent : "")
                            .set("prevFirstFramePrompt", prevFirstFramePrompt != null ? prevFirstFramePrompt : "")
                            .set("prevLastFramePrompt", prevLastFramePrompt != null ? prevLastFramePrompt : "")
                            .set("prevVideoPrompt", prevVideoPrompt != null ? prevVideoPrompt : "")
                            .set("prevLastFrameImageUrl", prevLastFrameUrl != null ? prevLastFrameUrl : "")
                            .set("prevFirstFrameImageUrl", prevFirstFrameUrl != null ? prevFirstFrameUrl : "")
                            .set("prevStateAfterHash", prevStateAfterHash)
                            .set("currentStateBeforeHash", item.getStateBeforeHash())
                            .set("canReusePrevLastFrame", "SEAMLESS".equalsIgnoreCase(item.getContinuityMode())
                                    && prevLastFrameCurrent
                                    && StrUtil.isNotBlank(prevStateAfterHash)
                                    && prevStateAfterHash.equals(item.getStateBeforeHash()));
                    itemObj.set("continuityContext", continuityCtx);
                }

                // 确定性预计算参考图列表。场景、角色和服装是连续性的硬参考，画风图置后，
                // 避免图像模型把画风人物/场景误当作镜头内容。
                boolean includeArtStyleReference = shouldIncludeArtStyleReference(
                        isFirstShotInScene, requestedFrameType);
                List<String> effectiveArtStyleRefs = includeArtStyleReference ? artStyleRefImageUrls : null;
                String effectiveArtStyleRefPrompt = includeArtStyleReference ? artStyleReferencePrompt : null;
                JSONArray suggestedRefImages = buildSuggestedReferenceImages(
                        item.getCharacterIds(), item.getCostumeIds(), item.getPropIds(), effectiveSceneAssetItemId,
                        assetItemMap, characterSubAssetUrlMap, assetNameMap, effectiveArtStyleRefs, effectiveArtStyleRefPrompt,
                        maxReferenceImages, prevFirstFrameUrl);
                if (!suggestedRefImages.isEmpty()) {
                    itemObj.set("suggestedReferenceImages", suggestedRefImages);
                }

                itemList.add(itemObj);

                // 更新前序追踪变量，供下一个镜头使用
                prevContent = item.getContent();
                if (!firstFrameStale && StrUtil.isNotBlank(item.getFirstFramePrompt())) {
                    prevFirstFramePrompt = item.getFirstFramePrompt();
                }
                // 尾帧 prompt 追踪：供下一镜头首帧承接上镜结尾状态
                if (!lastFrameStale && StrUtil.isNotBlank(item.getLastFramePrompt())) {
                    prevLastFramePrompt = item.getLastFramePrompt();
                }
                // 文本结尾锚点：videoPrompt > lastFramePrompt > 保留旧值
                if (!videoStale && StrUtil.isNotBlank(item.getVideoPrompt())) {
                    prevVideoPrompt = item.getVideoPrompt();
                } else if (!lastFrameStale && StrUtil.isNotBlank(item.getLastFramePrompt())) {
                    prevVideoPrompt = item.getLastFramePrompt();
                }
                if (!lastFrameStale && StrUtil.isNotBlank(item.getLastFrameImageUrl())) {
                    prevLastFrameUrl = item.getLastFrameImageUrl();
                } else {
                    prevLastFrameUrl = null;
                }
                // prev_frame 视觉参考：优先尾帧（结尾状态），否则首帧
                if (!lastFrameStale && StrUtil.isNotBlank(item.getLastFrameImageUrl())) {
                    prevFirstFrameUrl = item.getLastFrameImageUrl();
                } else if (!firstFrameStale && StrUtil.isNotBlank(item.getFirstFrameImageUrl())) {
                    prevFirstFrameUrl = item.getFirstFrameImageUrl();
                } else {
                    prevFirstFrameUrl = null;
                }
                prevStateAfterHash = item.getStateAfterHash();
                prevLastFrameCurrent = !lastFrameStale && StrUtil.isNotBlank(item.getLastFrameImageUrl());
                isFirstShotInScene = false;
            }

            return JSONUtil.createObj()
                    .set("storyboardSceneId", scene.getId())
                    .set("sceneName", scene.getSceneHeading())
                    .set("baseSceneAssetItemId", scene.getBaseSceneAssetItemId())
                    .set("layoutDefinition", parseJson(scene.getLayoutDefinition()))
                    .set("initialSceneState", parseJson(scene.getInitialSceneState()))
                    .set("shotStrategy", parseJson(scene.getShotStrategy()))
                    .set("beats", parseJson(scene.getBeats()))
                    .set("stateTimelineVersion", scene.getStateTimelineVersion())
                    .set("totalItems", items.size())
                    .set("items", itemList)
                    .toString();

        } catch (Exception e) {
            log.error("[get_storyboard_scene_items] 查询失败", e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    static boolean shouldIncludeArtStyleReference(boolean isFirstShotInScene, String frameType) {
        return isFirstShotInScene && "first".equalsIgnoreCase(frameType);
    }

    private static Object parseJson(String json) {
        if (StrUtil.isBlank(json)) return null;
        try {
            return JSONUtil.parse(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMediaStale(String mediaUrl, String expectedHash, String generatedHash) {
        return StrUtil.isNotBlank(mediaUrl)
                && StrUtil.isNotBlank(expectedHash)
                && !expectedHash.equals(generatedHash);
    }

    /**
     * 从 JSON 数组字符串中提取子资产 ID 到集合
     */
    private void collectAssetItemIds(Set<Long> ids, String jsonArrayStr) {
        if (StrUtil.isBlank(jsonArrayStr)) return;
        try {
            JSONArray arr = JSONUtil.parseArray(jsonArrayStr);
            for (int i = 0; i < arr.size(); i++) {
                Long id = arr.getLong(i);
                if (id != null) ids.add(id);
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 解析子资产ID列表失败: {}", jsonArrayStr, e);
        }
    }

    /**
     * 批量查询子资产信息，返回 id → AssetItem 映射
     */
    private Map<Long, AssetItem> batchGetAssetItems(Set<Long> ids) {
        Map<Long, AssetItem> map = new HashMap<>();
        for (Long id : ids) {
            try {
                AssetItem item = assetService.getItemById(id);
                map.put(id, item);
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 查询子资产失败: id={}", id, e);
            }
        }
        return map;
    }

    /**
     * 根据 ID 列表 JSON 构建资产引用数组（通用，不含三视图）
     */
    private JSONArray buildAssetRefs(String idsJson, Map<Long, AssetItem> assetItemMap) {
        JSONArray refs = new JSONArray();
        if (StrUtil.isBlank(idsJson)) return refs;
        try {
            JSONArray arr = JSONUtil.parseArray(idsJson);
            for (int i = 0; i < arr.size(); i++) {
                Long id = arr.getLong(i);
                if (id == null) continue;
                AssetItem assetItem = assetItemMap.get(id);
                if (assetItem != null) {
                    refs.add(buildSingleAssetRef(assetItem));
                }
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 构建资产引用失败: {}", idsJson, e);
        }
        return refs;
    }

    /**
     * 构建角色引用数组，额外附带 threeViewImageUrl（如果存在）
     */
    private JSONArray buildCharacterRefs(String idsJson, Map<Long, AssetItem> assetItemMap,
                                         Map<Long, CharacterSubAssetUrls> characterSubAssetUrlMap,
                                         Map<Long, String> characterVoiceAudioUrlMap) {
        JSONArray refs = new JSONArray();
        if (StrUtil.isBlank(idsJson)) return refs;
        try {
            JSONArray arr = JSONUtil.parseArray(idsJson);
            for (int i = 0; i < arr.size(); i++) {
                Long id = arr.getLong(i);
                if (id == null) continue;
                AssetItem assetItem = assetItemMap.get(id);
                if (assetItem != null) {
                    JSONObject ref = buildSingleAssetRef(assetItem);
                    CharacterSubAssetUrls subUrls = characterSubAssetUrlMap.get(assetItem.getAssetId());
                    if (subUrls != null && StrUtil.isNotBlank(subUrls.threeViewUrl())) {
                        ref.set("threeViewImageUrl", subUrls.threeViewUrl());
                    }
                    String voiceAudioUrl = characterVoiceAudioUrlMap.get(assetItem.getAssetId());
                    if (StrUtil.isNotBlank(voiceAudioUrl)) {
                        ref.set("referenceAudioUrl", voiceAudioUrl);
                    }
                    refs.add(ref);
                }
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 构建角色引用失败: {}", idsJson, e);
        }
        return refs;
    }

    /**
     * 批量查询主资产名称，返回 assetId → name 映射
     * 用于 character_ids 指向 costume 等非角色子资产时，正确解析角色名
     */
    private Map<Long, String> batchGetAssetNames(Map<Long, AssetItem> assetItemMap) {
        Map<Long, String> result = new HashMap<>();
        Set<Long> assetIds = new LinkedHashSet<>();
        for (AssetItem item : assetItemMap.values()) {
            if (item.getAssetId() != null) {
                assetIds.add(item.getAssetId());
            }
        }
        for (Long assetId : assetIds) {
            try {
                var asset = assetService.getById(assetId);
                if (asset != null && StrUtil.isNotBlank(asset.getName())) {
                    result.put(assetId, asset.getName());
                }
            } catch (Exception e) {
                log.debug("[get_storyboard_scene_items] 查询主资产名称失败: assetId={}", assetId);
            }
        }
        return result;
    }

    /**
     * 解析角色名称：优先从 assetNameMap（主资产名称）获取，
     * 避免 character_ids 指向 costume 等非角色子资产时名称错误（如"苏念 常服"而非"苏念"）
     */
    static String resolveCharacterName(AssetItem assetItem, Map<Long, String> assetNameMap) {
        if (assetItem == null) return "";
        if (assetNameMap != null && assetItem.getAssetId() != null) {
            String parentName = assetNameMap.get(assetItem.getAssetId());
            if (StrUtil.isNotBlank(parentName)) {
                return parentName;
            }
        }
        return StrUtil.blankToDefault(assetItem.getName(), "");
    }

    /**
     * 为 assetItemMap 中的每个主资产查询 three_view、initial 和默认 costume 子资产的 imageUrl
     * 返回 assetId → CharacterSubAssetUrls 映射，单次 DB 查询同时提取三种子资产
     */
    private Map<Long, CharacterSubAssetUrls> batchGetCharacterSubAssetUrls(Map<Long, AssetItem> assetItemMap) {
        Map<Long, CharacterSubAssetUrls> result = new HashMap<>();
        Set<Long> assetIds = new LinkedHashSet<>();
        for (AssetItem item : assetItemMap.values()) {
            if (item.getAssetId() != null) {
                assetIds.add(item.getAssetId());
            }
        }
        for (Long assetId : assetIds) {
            try {
                List<AssetItem> items = assetService.listItems(assetId);
                String threeViewUrl = null;
                String initialUrl = null;
                String defaultCostumeUrl = null;
                String defaultCostumeDescription = null;
                for (AssetItem item : items) {
                    if ("three_view".equals(item.getItemType()) && StrUtil.isNotBlank(item.getImageUrl()) && threeViewUrl == null) {
                        threeViewUrl = item.getImageUrl();
                    }
                    if ("initial".equals(item.getItemType()) && StrUtil.isNotBlank(item.getImageUrl()) && initialUrl == null) {
                        initialUrl = item.getImageUrl();
                    }
                    if ("costume".equals(item.getItemType()) && StrUtil.isNotBlank(item.getImageUrl()) && defaultCostumeUrl == null) {
                        defaultCostumeUrl = item.getImageUrl();
                        defaultCostumeDescription = resolveCostumeDescription(item);
                    }
                }
                result.put(assetId, new CharacterSubAssetUrls(threeViewUrl, initialUrl, defaultCostumeUrl,
                        defaultCostumeDescription));
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 查询角色子资产失败: assetId={}", assetId, e);
            }
        }
        return result;
    }

    /** 角色主资产的子资产 URL 封装（三视图 + 初始图 + 默认常服及其文字描述） */
    record CharacterSubAssetUrls(String threeViewUrl, String initialUrl, String defaultCostumeUrl,
                                 String defaultCostumeDescription) {}

    /**
     * 批量查询角色主资产的音色参考音频 URL
     * 返回 assetId → referenceAudioUrl 映射
     */
    private Map<Long, String> batchGetCharacterVoiceAudioUrls(Map<Long, AssetItem> assetItemMap) {
        Map<Long, String> result = new HashMap<>();
        Set<Long> assetIds = new LinkedHashSet<>();
        for (AssetItem item : assetItemMap.values()) {
            if (item.getAssetId() != null) {
                assetIds.add(item.getAssetId());
            }
        }
        for (Long assetId : assetIds) {
            try {
                VoiceConfigService.VoiceConfig vc = voiceConfigService.getVoiceConfig(assetId);
                if (vc != null && StrUtil.isNotBlank(vc.getReferenceAudioUrl())) {
                    result.put(assetId, vc.getReferenceAudioUrl());
                }
            } catch (Exception e) {
                // 角色可能没有配置音色，静默跳过
            }
        }
        return result;
    }

    /**
     * 构建单个子资产的引用信息
     */
    private JSONObject buildSingleAssetRef(AssetItem assetItem) {
        return JSONUtil.createObj()
                .set("assetItemId", assetItem.getId())
                .set("assetId", assetItem.getAssetId())
                .set("name", assetItem.getName())
                .set("itemType", assetItem.getItemType())
                .set("imageUrl", assetItem.getImageUrl())
                .set("thumbnailUrl", assetItem.getThumbnailUrl());
    }

    /**
     * 解析项目画风参考图 URL 列表（支持多张）
     */
    private List<String> resolveArtStyleRefImages(Long storyboardId) {
        if (storyboardId == null) return Collections.emptyList();
        try {
            Storyboard storyboard = storyboardService.getById(storyboardId);
            if (storyboard.getProjectId() == null) return Collections.emptyList();
            Project project = projectService.getById(storyboard.getProjectId());
            String artStyleKey = project.getArtStyle();
            if (StrUtil.isBlank(artStyleKey)) return Collections.emptyList();

            List<String> refImagePaths = new ArrayList<>();
            if ("custom".equals(artStyleKey)) {
                if (StrUtil.isNotBlank(project.getArtStyleImageUrl())) {
                    refImagePaths.add(project.getArtStyleImageUrl());
                }
            } else {
                ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
                if (preset == null) return Collections.emptyList();
                List<String> paths = preset.getReferenceImagePaths();
                if (paths != null) {
                    for (int i = 0; i < paths.size(); i++) {
                        String configKey = paths.size() == 1
                                ? "art_preset_url:" + artStyleKey
                                : "art_preset_url:" + artStyleKey + "_" + i;
                        String presetPublicUrl = systemConfigService.getValue(configKey);
                        refImagePaths.add(StrUtil.isNotBlank(presetPublicUrl) ? presetPublicUrl : paths.get(i));
                    }
                }
            }

            List<String> result = new ArrayList<>();
            for (String path : refImagePaths) {
                if (StrUtil.isNotBlank(path)) {
                    result.add(path);
                }
            }
            log.info("[get_storyboard_scene_items] 画风参考图解析: artStyleKey={}, refImagePaths={}, result={}",
                    artStyleKey, refImagePaths, result);
            return result;
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 解析画风参考图失败: storyboardId={}", storyboardId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析项目画风参考图引用提示词（referencePrompt）
     */
    private String resolveArtStyleReferencePrompt(Long storyboardId) {
        if (storyboardId == null) return null;
        try {
            Storyboard storyboard = storyboardService.getById(storyboardId);
            if (storyboard.getProjectId() == null) return null;
            Project project = projectService.getById(storyboard.getProjectId());
            String artStyleKey = project.getArtStyle();
            if (StrUtil.isBlank(artStyleKey)) return null;
            if ("custom".equals(artStyleKey)) {
                return "仅参考图片{index}的渲染技法、光影质感和色彩风格，绝不参考其中的任何具体角色、物品和构图，";
            }
            ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
            return preset != null ? preset.getReferencePrompt() : null;
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 解析画风参考提示词失败: storyboardId={}", storyboardId, e);
            return null;
        }
    }

    /**
     * 确定性预计算参考图列表（优先级：场景固定布局 → 前序帧 → 角色三视图 → 服装 → 角色初始图 → 道具 → 画风）。
     * 画风仅提供渲染质感，不能挤占角色服装和固定布局这两类连续性硬参考。
     * 每个元素包含 url、role（style/prev_frame/character_three_view/character/costume/prop/scene）、name 字段
     * 当 costumeIds 为空时，自动回落到角色的默认常服（costume 子资产）
     */
    private static JSONArray buildSuggestedReferenceImages(String characterIdsJson, String costumeIdsJson,
                                                     String propIdsJson,
                                                     Long sceneAssetItemId,
                                                     Map<Long, AssetItem> assetItemMap,
                                                     Map<Long, CharacterSubAssetUrls> characterSubAssetUrlMap,
                                                     Map<Long, String> assetNameMap,
                                                     List<String> artStyleRefImageUrls,
                                                     String artStyleReferencePrompt,
                                                     int maxImages,
                                                     String prevFirstFrameUrl) {
        JSONArray result = new JSONArray();
        Set<String> addedUrls = new LinkedHashSet<>();

        // 1. 场景布局参考图：只锁定固定空间结构，不锁定角色和可移动物体位置。
        if (sceneAssetItemId != null && result.size() < maxImages) {
            AssetItem sceneItem = assetItemMap.get(sceneAssetItemId);
            if (sceneItem != null && StrUtil.isNotBlank(sceneItem.getImageUrl())
                    && addedUrls.add(sceneItem.getImageUrl())) {
                int idx = result.size() + 1;
                result.add(JSONUtil.createObj()
                        .set("url", sceneItem.getImageUrl())
                        .set("role", "scene_layout")
                        .set("name", sceneItem.getName())
                        .set("index", idx)
                        .set("referencePrompt", "参考图片" + idx
                                + "的固定空间结构、门窗位置和固定家具布局；角色与可移动物体位置以本镜头状态为准，"));
            }
        }

        // 2. 前序镜头首帧（视觉连贯性参考）
        //    用于保持角色外貌、场景布局、光线方向、色调风格在镜头间一致
        if (StrUtil.isNotBlank(prevFirstFrameUrl) && addedUrls.add(prevFirstFrameUrl) && result.size() < maxImages) {
            result.add(JSONUtil.createObj()
                    .set("url", prevFirstFrameUrl)
                    .set("role", "prev_frame")
                    .set("name", "前序镜头首帧")
                    .set("index", result.size() + 1));
        }

        // 3. 角色三视图。初始图延后到常服图之后，确保模型先看到着装约束。
        // 记录每个角色主资产对应的参考图 index，用于后续 costume 组合提示
        Map<Long, Integer> characterAssetIndexMap = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(characterIdsJson)) {
            try {
                JSONArray charIds = JSONUtil.parseArray(characterIdsJson);
                for (int i = 0; i < charIds.size() && result.size() < maxImages; i++) {
                    Long id = charIds.getLong(i);
                    if (id == null) continue;
                    AssetItem assetItem = assetItemMap.get(id);
                    if (assetItem == null) continue;

                    // 三视图优先
                    CharacterSubAssetUrls subUrls = characterSubAssetUrlMap.get(assetItem.getAssetId());
                    String characterName = resolveCharacterName(assetItem, assetNameMap);
                    if (subUrls != null && StrUtil.isNotBlank(subUrls.threeViewUrl()) && addedUrls.add(subUrls.threeViewUrl())) {
                        int idx = result.size() + 1;
                        result.add(JSONUtil.createObj()
                                .set("url", subUrls.threeViewUrl())
                                .set("role", "character_three_view")
                                .set("name", characterName + " 三视图")
                                .set("index", idx)
                                .set("referencePrompt", "参考图片" + idx + "的角色长相和面部特征（" + characterName + "，多角度设计细节），"));
                        characterAssetIndexMap.put(assetItem.getAssetId(), idx);
                    }
                }
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建角色参考图失败: {}", characterIdsJson, e);
            }
        }

        // 4. 服装子资产（costume 类型）。服装在角色初始图之前，使视觉模型优先建立造型。
        //    当 costumeIds 为空时，自动回落到角色的默认常服
        //    记录 costume 的 assetId → (index, name) 映射，用于后续后处理合并到角色 referencePrompt
        Map<Long, Integer> costumeAssetIndexMap = new LinkedHashMap<>();
        Map<Long, String> costumeAssetNameMap = new HashMap<>();
        Map<Long, String> costumeDescriptionMap = new HashMap<>();
        boolean hasExplicitCostumeIds = hasAssetItemIds(costumeIdsJson);
        if (hasExplicitCostumeIds) {
            try {
                JSONArray costumeIds = JSONUtil.parseArray(costumeIdsJson);
                for (int i = 0; i < costumeIds.size() && result.size() < maxImages; i++) {
                    Long id = costumeIds.getLong(i);
                    if (id == null) continue;
                    AssetItem assetItem = assetItemMap.get(id);
                    if (assetItem == null) continue;
                    String imageUrl = assetItem.getImageUrl();
                    if (StrUtil.isNotBlank(imageUrl) && addedUrls.add(imageUrl)) {
                        int idx = result.size() + 1;
                        String costumeName = assetItem.getName();
                        result.add(JSONUtil.createObj()
                                .set("url", imageUrl)
                                .set("role", "costume")
                                .set("name", costumeName + "（服装）")
                                .set("index", idx)
                                .set("referencePrompt", buildCostumeReferencePrompt(idx, costumeName,
                                        resolveCostumeDescription(assetItem))));
                        costumeAssetIndexMap.put(assetItem.getAssetId(), idx);
                        costumeAssetNameMap.put(assetItem.getAssetId(), costumeName);
                        costumeDescriptionMap.put(assetItem.getAssetId(), resolveCostumeDescription(assetItem));
                    }
                }
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建服装参考图失败: {}", costumeIdsJson, e);
            }
        } else if (StrUtil.isNotBlank(characterIdsJson)) {
            // 无显式 costumeIds → 回落到角色默认常服
            try {
                JSONArray charIds = JSONUtil.parseArray(characterIdsJson);
                for (int i = 0; i < charIds.size() && result.size() < maxImages; i++) {
                    Long id = charIds.getLong(i);
                    if (id == null) continue;
                    AssetItem assetItem = assetItemMap.get(id);
                    if (assetItem == null) continue;
                    CharacterSubAssetUrls subUrls = characterSubAssetUrlMap.get(assetItem.getAssetId());
                    if (subUrls != null && StrUtil.isNotBlank(subUrls.defaultCostumeUrl())
                            && addedUrls.add(subUrls.defaultCostumeUrl())) {
                        int idx = result.size() + 1;
                        String characterName = resolveCharacterName(assetItem, assetNameMap);
                        String costumeName = characterName + " 常服";
                        result.add(JSONUtil.createObj()
                                .set("url", subUrls.defaultCostumeUrl())
                                .set("role", "costume")
                                .set("name", costumeName + "（服装）")
                                .set("index", idx)
                                .set("referencePrompt", buildCostumeReferencePrompt(idx, costumeName,
                                        subUrls.defaultCostumeDescription())));
                        costumeAssetIndexMap.put(assetItem.getAssetId(), idx);
                        costumeAssetNameMap.put(assetItem.getAssetId(), costumeName);
                        costumeDescriptionMap.put(assetItem.getAssetId(), subUrls.defaultCostumeDescription());
                    }
                }
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建默认常服参考图失败: {}", characterIdsJson, e);
            }
        }

        // 5. 角色初始图。初始图是素体参考，只补充脸和体型，不能压过常服图。
        if (StrUtil.isNotBlank(characterIdsJson)) {
            try {
                JSONArray charIds = JSONUtil.parseArray(characterIdsJson);
                for (int i = 0; i < charIds.size() && result.size() < maxImages; i++) {
                    Long id = charIds.getLong(i);
                    AssetItem assetItem = id != null ? assetItemMap.get(id) : null;
                    if (assetItem == null) continue;
                    CharacterSubAssetUrls subUrls = characterSubAssetUrlMap.get(assetItem.getAssetId());
                    String initialUrl = subUrls != null ? subUrls.initialUrl() : null;
                    if (StrUtil.isNotBlank(initialUrl) && addedUrls.add(initialUrl)) {
                        int idx = result.size() + 1;
                        String characterName = resolveCharacterName(assetItem, assetNameMap);
                        result.add(JSONUtil.createObj()
                                .set("url", initialUrl)
                                .set("role", "character")
                                .set("name", characterName)
                                .set("index", idx)
                                .set("referencePrompt", "参考图片" + idx + "的角色长相和面部特征（" + characterName + "），"));
                        characterAssetIndexMap.putIfAbsent(assetItem.getAssetId(), idx);
                    }
                }
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建角色初始图参考失败: {}", characterIdsJson, e);
            }
        }

        // 6. 道具
        if (StrUtil.isNotBlank(propIdsJson)) {
            try {
                JSONArray propIds = JSONUtil.parseArray(propIdsJson);
                for (int i = 0; i < propIds.size() && result.size() < maxImages; i++) {
                    Long id = propIds.getLong(i);
                    if (id == null) continue;
                    AssetItem assetItem = assetItemMap.get(id);
                    if (assetItem == null) continue;
                    String imageUrl = assetItem.getImageUrl();
                    if (StrUtil.isNotBlank(imageUrl) && addedUrls.add(imageUrl)) {
                        int idx = result.size() + 1;
                        result.add(JSONUtil.createObj()
                                .set("url", imageUrl)
                                .set("role", "prop")
                                .set("name", assetItem.getName())
                                .set("index", idx)
                                .set("referencePrompt", "参考图片" + idx + "的道具（" + assetItem.getName() + "），"));
                    }
                }
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建道具参考图失败: {}", propIdsJson, e);
            }
        }

        // 7. 画风参考图（支持多张，最多占 2 个位置）。放在资产硬参考之后，且仅约束渲染质感。
        int styleImageCount = 0;
        if (artStyleRefImageUrls != null) {
            int styleSlots = 0;
            for (String url : artStyleRefImageUrls) {
                if (styleSlots >= 2 || result.size() >= maxImages) break;
                if (StrUtil.isNotBlank(url) && addedUrls.add(url)) {
                    int index = result.size() + 1;
                    JSONObject styleObj = JSONUtil.createObj()
                            .set("url", url)
                            .set("role", "style")
                            .set("name", "画风参考图" + (artStyleRefImageUrls.size() > 1 ? (styleSlots + 1) : ""))
                            .set("index", index);
                    if (StrUtil.isNotBlank(artStyleReferencePrompt)) {
                        styleObj.set("referencePrompt", artStyleReferencePrompt.replace("{index}", String.valueOf(index)));
                    }
                    result.add(styleObj);
                    styleSlots++;
                    styleImageCount++;
                }
            }
        }
        log.info("[get_storyboard_scene_items] buildSuggestedReferenceImages: styleImageCount={}, totalImages={}",
                styleImageCount, result.size());

        // 后处理：将角色+服装组合句直接写入角色元素的 referencePrompt，并清除服装元素的 referencePrompt
        // 同时清除同一角色的其他角色图（如初始图）的 referencePrompt，避免 LLM 为同一角色写多句描述
        for (Map.Entry<Long, Integer> charEntry : characterAssetIndexMap.entrySet()) {
            Long assetId = charEntry.getKey();
            int primaryCharIdx = charEntry.getValue();
            Integer costumeIdx = costumeAssetIndexMap.get(assetId);
            if (costumeIdx == null) continue;
            String costumeName = costumeAssetNameMap.get(assetId);
            String costumeDescription = costumeDescriptionMap.get(assetId);

            // 查找主角色元素（三视图优先，否则初始图），更新其 referencePrompt 为完整的组合句
            for (Object obj : result) {
                JSONObject jo = (JSONObject) obj;
                if (jo.getInt("index", 0) != primaryCharIdx) continue;
                String charRole = jo.getStr("role");
                String charDisplayName = jo.getStr("name", "").replace(" 三视图", "").trim();
                String combination = "character_three_view".equals(charRole)
                        ? "参考图片" + primaryCharIdx + "的角色长相和面部特征（" + charDisplayName + "，多角度设计细节），"
                        : "参考图片" + primaryCharIdx + "的角色长相和面部特征（" + charDisplayName + "），";
                combination += buildCostumeReferencePrompt(costumeIdx, costumeName, costumeDescription);
                jo.set("referencePrompt", combination);
                break;
            }

            // 清除同一角色的其他角色图（如初始图）的 referencePrompt，标记为已合并
            // 避免LLM同时为三视图和初始图写两句描述
            for (Object obj : result) {
                JSONObject jo = (JSONObject) obj;
                String role = jo.getStr("role");
                if (!"character".equals(role) && !"character_three_view".equals(role)) continue;
                if (jo.getInt("index", 0) == primaryCharIdx) continue;
                // 检查是否属于同一角色资产（通过 name 前缀匹配）
                String name = jo.getStr("name", "");
                // name 格式为 "角色名" 或 "角色名 三视图"
                String primaryName = "";
                for (Object o2 : result) {
                    JSONObject jo2 = (JSONObject) o2;
                    if (jo2.getInt("index", 0) == primaryCharIdx) {
                        primaryName = jo2.getStr("name", "");
                        break;
                    }
                }
                String primaryCharName = primaryName.replace(" 三视图", "").trim();
                String thisCharName = name.replace(" 三视图", "").trim();
                if (primaryCharName.equals(thisCharName)) {
                    jo.remove("referencePrompt");
                    jo.set("mergedIntoCharacter", true);
                }
            }

            // 清除服装元素的 referencePrompt，标记为已合并到角色描述
            for (Object obj : result) {
                JSONObject jo = (JSONObject) obj;
                if (jo.getInt("index", 0) != costumeIdx) continue;
                jo.remove("referencePrompt");
                jo.set("mergedIntoCharacter", true);
                break;
            }
        }

        return result;
    }

    private static String buildCostumeReferencePrompt(int imageIndex, String costumeName, String costumeDescription) {
        String description = StrUtil.isBlank(costumeDescription) ? "" : "，具体为" + costumeDescription.trim();
        return "穿着参考图片" + imageIndex + "的绑定服装（" + costumeName + description
                + "），服装款式、配色、上下装、鞋履和配饰必须与该图一致，禁止替换为未绑定的造型，";
    }

    private static String resolveCostumeDescription(AssetItem assetItem) {
        if (assetItem == null || StrUtil.isBlank(assetItem.getProperties())) {
            return null;
        }
        try {
            String clothing = JSONUtil.parseObj(assetItem.getProperties()).getStr("clothing");
            return StrUtil.isBlank(clothing) || "无".equals(clothing.trim()) ? null : clothing.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasAssetItemIds(String idsJson) {
        if (StrUtil.isBlank(idsJson)) {
            return false;
        }
        try {
            return !JSONUtil.parseArray(idsJson).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 动态获取图片模型的最大参考图数量
     */
    private int resolveMaxReferenceImages() {
        try {
            AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_IMAGE);
            if (defaultModel == null) {
                List<AiModel> imageModels = aiModelService.getListByType(MODEL_TYPE_IMAGE);
                if (!imageModels.isEmpty()) {
                    defaultModel = imageModels.get(0);
                }
            }
            if (defaultModel != null) {
                GenerationModelCapabilityService.ImageModelCapability cap =
                        generationModelCapabilityService.resolveImageCapability(defaultModel);
                if (cap.supportsReferenceImages() && cap.maxReferenceImages() != null && cap.maxReferenceImages() > 0) {
                    return cap.maxReferenceImages();
                }
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 获取图片模型参考图上限失败，回退默认值 {}", DEFAULT_MAX_REFERENCE_IMAGES, e);
        }
        return DEFAULT_MAX_REFERENCE_IMAGES;
    }
}
