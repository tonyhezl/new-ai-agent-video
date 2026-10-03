package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.storyboard.StoryboardShotPlanValidator;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 保存场次分镜工具（save_storyboard_scene_shots）
 * <p>
 * 创建一个分镜场次，并批量插入该场次下的所有镜头。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveStoryboardSceneShotsToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final AssetService assetService;
    private final StoryboardShotPlanValidator shotPlanValidator;

    @Override
    public String getToolName() {
        return "save_storyboard_scene_shots";
    }

    @Override
    public String getDisplayName() {
        return "保存场次分镜";
    }

    @Override
    public String getToolDescription() {
        return """
                创建一个分镜场次，并批量保存该场次下的所有镜头。
                可一次性保存一个场次的完整分镜数据。

                【参数说明】
                - storyboardId：所属分镜脚本ID（必填）
                - storyboardEpisodeId：所属分镜集ID（必填）
                - sceneNumber：场次编号，如 "1-1"（必填）
                - sceneHeading：场景标头，如 "内景 客厅 夜"
                - location：场景地点
                - timeOfDay：时间段（日/夜/黄昏/清晨）
                - intExt：内外景标识（内景/外景/内外景）
                - shotStrategy：场次拆镜主策略与辅助标签（必填）
                - beats：场次叙事节拍列表（必填）
                - shots：复核通过后的最终镜头数组（必填），一个镜头可通过 beatIds 覆盖多个连续节拍
                - reviewed/reviewApproved：必须均为 true；未通过复核禁止保存
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardId": {
                            "type": "number",
                            "description": "所属分镜脚本ID（必填）"
                        },
                        "storyboardEpisodeId": {
                            "type": "number",
                            "description": "所属分镜集ID（必填）"
                        },
                        "sceneNumber": {
                            "type": "string",
                            "description": "场次编号，如 1-1（必填）"
                        },
                        "sceneHeading": {
                            "type": "string",
                            "description": "场景标头，如 内景 客厅 夜"
                        },
                        "location": {
                            "type": "string",
                            "description": "场景地点"
                        },
                        "timeOfDay": {
                            "type": "string",
                            "description": "时间段（日/夜/黄昏/清晨）"
                        },
                        "intExt": {
                            "type": "string",
                            "description": "内外景标识（内景/外景/内外景）"
                        },
                        "shotStrategy": {
                            "type": "object",
                            "description": "场次拆镜策略。混合场次用 primaryType 表示主策略，tags 表示辅助特征",
                            "properties": {
                                "primaryType": {
                                    "type": "string",
                                    "enum": ["GENERAL_NARRATIVE", "DIALOGUE", "ACTION", "CONTINUOUS_VISUAL", "MONTAGE"]
                                },
                                "tags": {
                                    "type": "array",
                                    "items": {"type": "string"}
                                },
                                "reason": {"type": "string"}
                            },
                            "required": ["primaryType", "tags"]
                        },
                        "beats": {
                            "type": "array",
                            "description": "场次叙事节拍列表。节拍是叙事状态变化，不等同于台词条数或物理动作数",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "id": {"type": "string", "description": "稳定节拍ID，如 B1"},
                                    "description": {"type": "string"},
                                    "sourceRefs": {"type": "array", "items": {"type": "string"}}
                                },
                                "required": ["id", "description"]
                            }
                        },
                        "baseSceneAssetItemId": {
                            "type": "number",
                            "description": "场次默认场景子资产ID。场次内镜头未单独指定场景变体时使用此值"
                        },
                        "layoutDefinition": {
                            "type": "object",
                            "description": "固定空间布局锚点，只记录参考图已知或剧本明确的固定结构，禁止脑补位置",
                            "properties": {
                                "anchors": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "id": {"type": "string", "description": "稳定ID，如 door_main"},
                                            "name": {"type": "string"},
                                            "kind": {"type": "string", "description": "door/window/fixed_furniture/zone"},
                                            "description": {"type": "string", "description": "仅填写已明确的布局事实"},
                                            "canContainObjects": {"type": "boolean", "description": "仅固定柜体、储物箱等已明确可收纳的结构为 true；普通课桌、桌面和椅子必须为 false"},
                                            "furnitureLayout": {
                                                "type": "object",
                                                "description": "仅 fixed_furniture 使用。固定家具组的结构事实，未知字段留空",
                                                "properties": {
                                                    "arrangement": {"type": "string", "enum": ["grid", "row", "line", "cluster", "custom"]},
                                                    "rows": {"type": "integer", "minimum": 1},
                                                    "columns": {"type": "integer", "minimum": 1},
                                                    "aisleDescription": {"type": "string", "description": "已知过道关系"},
                                                    "empty": {"type": "boolean", "description": "固定家具组是否为空置"},
                                                    "prohibitedFeatures": {"type": "array", "items": {"type": "string"}, "description": "参考图明确不存在、禁止模型补出的结构，如抽屉、储物柜"}
                                                }
                                            }
                                        },
                                        "required": ["id", "name"]
                                    }
                                }
                            }
                        },
                        "initialSceneState": {
                            "type": "object",
                            "description": "场次开始时可移动道具状态",
                            "properties": {
                                "objects": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "objectId": {"type": "string", "description": "道具必须使用 assetItem:<AssetItem.id>，该 ID 取自 assets[].items[].assetItemId 并与同一道具的 propIds 一致；禁止使用外层主资产 assetId。固定背景物使用 layout:<锚点ID>"},
                                            "objectName": {"type": "string"},
                                            "location": {"type": "string", "description": "仅在剧本或布局明确时填写"},
                                            "state": {"type": "string"},
                                            "containerId": {"type": "string", "description": "仅当场景明确存在容器时填写。课桌、柜体等未声明容器不得推断"},
                                            "canContainObjects": {"type": "boolean", "description": "该道具是否为已明确存在的真实容器；仅真实纸箱、储物箱、柜体等填写 true"},
                                            "visible": {"type": "boolean"}
                                        },
                                        "required": ["objectId", "objectName"]
                                    }
                                }
                            }
                        },
                        "sortOrder": {
                            "type": "number",
                            "description": "场次排序序号（从0开始，默认0）"
                        },
                        "reviewed": {
                            "type": "boolean",
                            "description": "是否已调用 review_storyboard_content（必填，必须为 true）"
                        },
                        "reviewApproved": {
                            "type": "boolean",
                            "description": "复核器是否批准最终镜头列表（必填，必须为 true）"
                        },
                        "reviewedShotCount": {
                            "type": "integer",
                            "description": "复核器批准的最终镜头数，必须与 shots 长度一致"
                        },
                        "unresolvedReviewErrors": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "未解决的 error 列表；保存时必须为空数组"
                        },
                        "shots": {
                            "type": "array",
                            "description": "镜头列表（必填）",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "content": {
                                        "type": "string",
                                        "description": "画面内容描述（必填）"
                                    },
                                    "beatIds": {
                                        "type": "array",
                                        "items": {"type": "string"},
                                        "description": "本镜头覆盖的一个或多个连续剧本节拍ID（必填）"
                                    },
                                    "shotType": {
                                        "type": "string",
                                        "description": "景别（远景/全景/中景/近景/特写）"
                                    },
                                    "duration": {
                                        "type": "number",
                                        "description": "预估时长（秒）"
                                    },
                                    "dialogue": {
                                        "type": "string",
                                        "description": "台词/旁白，格式为'角色名：台词内容'，如'张三：你好啊'。旁白则直接写内容，无需角色名前缀"
                                    },
                                    "sound": {
                                        "type": "string",
                                        "description": "环境音/氛围（必填，无则填'无'）。如：雨声、远处雷声、低沉环境底噪"
                                    },
                                    "soundEffect": {
                                        "type": "string",
                                        "description": "瞬时音效（必填，无则填'无'）。如：皮鞭抽地、碎石簌簌、钟声轰鸣"
                                    },
                                    "music": {
                                        "type": "string",
                                        "description": "配乐建议"
                                    },
                                    "cameraMovement": {
                                        "type": "string",
                                        "description": "运镜方式（必填）。如：固定/缓推/缓拉/横移/跟拍/环绕/拉升。静态镜头填'固定'"
                                    },
                                    "cameraAngle": {
                                        "type": "string",
                                        "description": "机位角度（必填）。如：平视/微仰/仰视/微俯/俯视/侧视/鸟瞰。默认'平视'"
                                    },
                                    "cameraEquipment": {
                                        "type": "string",
                                        "description": "拍摄设备名（必填，不是运镜名）。如：手持/三脚架/斯坦尼康/轨道。未指定填'三脚架'。禁止使用无人机/穿梭机/飞行器/摇臂等（系统会自动替换为三脚架）。仅供记录，不会出现在画面中"
                                    },

                                    "focalLength": {
                                        "type": "string",
                                        "description": "镜头焦段"
                                    },
                                    "transition": {
                                        "type": "string",
                                        "description": "转场效果"
                                    },
                                    "sceneExpectation": {
                                        "type": "string",
                                        "description": "画面期望描述"
                                    },
                                    "characterIds": {
                                        "type": "array",
                                        "items": { "type": "number" },
                                        "description": "出场角色子资产ID列表（AssetItem.id），从 list_project_assets 或 query_asset_items 获得"
                                    },
                                    "costumeIds": {
                                        "type": "array",
                                        "items": { "type": "number" },
                                        "description": "服装子资产ID列表（AssetItem.id, itemType=costume）。角色在该镜头穿着的服装子资产，与 characterIds 一一对应或留空"
                                    },
                                    "sceneAssetItemId": {
                                        "type": "number",
                                        "description": "场景子资产ID（AssetItem.id）"
                                    },
                                    "propIds": {
                                        "type": "array",
                                        "items": { "type": "number" },
                                        "description": "道具子资产ID列表（AssetItem.id），取自 assets[].items[].assetItemId，禁止使用主资产 Asset.id"
                                    },
                                    "continuityMode": {
                                        "type": "string",
                                        "enum": ["CUT", "SEAMLESS"],
                                        "description": "CUT=切镜并重新构图（默认）；SEAMLESS=机位构图连续且允许复用上镜尾帧"
                                    },
                                    "sceneStateDelta": {
                                        "type": "object",
                                        "description": "本镜头内可移动物体的状态变化；无变化时 changes 为空，禁止复制完整场景描述",
                                        "properties": {
                                            "changes": {
                                                "type": "array",
                                                "items": {
                                                    "type": "object",
                                                    "properties": {
                                                        "operation": {"type": "string", "enum": ["UPSERT", "ADD", "MOVE", "SET_STATE", "SHOW", "HIDE", "REMOVE"]},
                                                        "objectId": {"type": "string", "description": "必须沿用稳定对象ID；assetItem:<ID> 的 ID 必须与同一道具的 propIds 使用同一个 AssetItem.id，禁止使用主资产 Asset.id"},
                                                        "objectName": {"type": "string"},
                                                        "location": {"type": "string", "description": "MOVE 时必填目标位置"},
                                                        "state": {"type": "string"},
                                                        "containerId": {"type": "string", "description": "仅引用已声明且 canContainObjects=true 的固定结构或道具"},
                                                        "canContainObjects": {"type": "boolean", "description": "新增或更新的道具是否为真实容器"},
                                                        "visible": {"type": "boolean"}
                                                    },
                                                    "required": ["operation", "objectId"]
                                                }
                                            }
                                        }
                                    },
                                    "remark": {
                                        "type": "string",
                                        "description": "备注"
                                    }
                                },
                                "required": ["content", "beatIds", "shotType", "duration", "sound",
                                    "soundEffect", "cameraMovement", "cameraAngle", "cameraEquipment",
                                    "sceneExpectation", "continuityMode", "sceneStateDelta"]
                            }
                        }
                    },
                    "required": ["storyboardId", "storyboardEpisodeId", "sceneNumber", "shotStrategy",
                        "baseSceneAssetItemId",
                        "beats", "reviewed", "reviewApproved", "reviewedShotCount",
                        "unresolvedReviewErrors", "shots"]
                }
                """;
    }

    @Override
    @Transactional
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardId = params.getLong("storyboardId");
            Long storyboardEpisodeId = params.getLong("storyboardEpisodeId");
            String sceneNumber = params.getStr("sceneNumber");
            Long baseSceneAssetItemId = params.getLong("baseSceneAssetItemId");
            JSONArray shotsArr = params.getJSONArray("shots");

            if (storyboardId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 storyboardId").toString();
            }
            if (storyboardEpisodeId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 storyboardEpisodeId").toString();
            }
            if (sceneNumber == null || sceneNumber.isBlank()) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 sceneNumber").toString();
            }
            if (baseSceneAssetItemId == null) {
                return JSONUtil.createObj().set("status", "error")
                        .set("message", "缺少 baseSceneAssetItemId，无法建立场次默认场景变体").toString();
            }
            if (shotsArr == null || shotsArr.isEmpty()) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 shots 或为空").toString();
            }

            List<JSONObject> candidateShots = new ArrayList<>();
            for (int i = 0; i < shotsArr.size(); i++) {
                candidateShots.add(shotsArr.getJSONObject(i));
            }
            shotPlanValidator.validate(params, candidateShots);

            Storyboard storyboard = storyboardService.getById(storyboardId);
            if (storyboard == null) {
                throw new IllegalArgumentException("storyboardId 不存在: " + storyboardId);
            }
            StoryboardEpisode storyboardEpisode = storyboardService.getEpisodeById(storyboardEpisodeId);
            if (!storyboardId.equals(storyboardEpisode.getStoryboardId())) {
                throw new IllegalArgumentException("storyboardEpisodeId 不属于当前 storyboardId");
            }
            Long projectId = storyboard.getProjectId();
            if (context.getProjectId() != null && !context.getProjectId().equals(projectId)) {
                throw new IllegalArgumentException("storyboardId 不属于当前项目");
            }

            // 1. 在写入任何数据前批量校验全部资产引用。
            Set<Long> allItemIds = new HashSet<>();
            Set<Long> stateAssetItemIds = new HashSet<>();
            Set<String> layoutObjectIds = collectLayoutObjectIds(params.getJSONObject("layoutDefinition"));
            if (baseSceneAssetItemId != null) {
                allItemIds.add(baseSceneAssetItemId);
            }
            collectStateAssetItemIds(params.getJSONObject("initialSceneState"), "objects",
                    layoutObjectIds, stateAssetItemIds);
            for (JSONObject shot : candidateShots) {
                Long sceneItemId = shot.getLong("sceneAssetItemId");
                if (sceneItemId != null) allItemIds.add(sceneItemId);
                JSONArray charIds = shot.getJSONArray("characterIds");
                if (charIds != null) {
                    for (int j = 0; j < charIds.size(); j++) {
                        Long id = charIds.getLong(j);
                        if (id != null) allItemIds.add(id);
                    }
                }
                JSONArray propIds = shot.getJSONArray("propIds");
                if (propIds != null) {
                    for (int j = 0; j < propIds.size(); j++) {
                        Long id = propIds.getLong(j);
                        if (id != null) allItemIds.add(id);
                    }
                }
                JSONArray costumeIds = shot.getJSONArray("costumeIds");
                if (costumeIds != null) {
                    for (int j = 0; j < costumeIds.size(); j++) {
                        Long id = costumeIds.getLong(j);
                        if (id != null) allItemIds.add(id);
                    }
                }
                collectStateAssetItemIds(shot.getJSONObject("sceneStateDelta"), "changes",
                        layoutObjectIds, stateAssetItemIds);
            }
            allItemIds.addAll(stateAssetItemIds);

            // 批量查询 assetItems 并构建校验映射
            Map<Long, AssetItem> itemMap = new HashMap<>(assetService.listItemsByIds(allItemIds).stream()
                    .collect(Collectors.toMap(AssetItem::getId, item -> item)));
            List<StateAssetReferenceCorrection> stateReferenceCorrections = normalizeStateAssetItemReferences(
                    params, candidateShots, layoutObjectIds, stateAssetItemIds, itemMap, projectId);
            validateStateAssetItemsAreBoundToShots(stateAssetItemIds, candidateShots);
            validateContainerReferences(params.getJSONObject("layoutDefinition"),
                    params.getJSONObject("initialSceneState"), candidateShots);
            Set<Long> parentAssetIds = itemMap.values().stream()
                    .map(AssetItem::getAssetId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, Asset> assetMap = assetService.listByIds(parentAssetIds).stream()
                    .collect(Collectors.toMap(Asset::getId, asset -> asset));
            validateSceneAssetItem(baseSceneAssetItemId, itemMap, assetMap, projectId, "baseSceneAssetItemId");
            for (Long stateItemId : stateAssetItemIds) {
                AssetItem stateItem = itemMap.get(stateItemId);
                Asset stateAsset = stateItem != null ? assetMap.get(stateItem.getAssetId()) : null;
                if (stateItem == null || stateAsset == null) {
                    throw new IllegalArgumentException("场景状态引用的道具子资产不存在: " + stateItemId);
                }
                if (!projectId.equals(stateAsset.getProjectId()) || !"prop".equals(stateAsset.getType())) {
                    throw new IllegalArgumentException("场景状态对象必须引用当前项目的道具子资产: " + stateItemId);
                }
            }
            validateShotAssetReferences(candidateShots, itemMap, assetMap, projectId);

            // 2. 校验通过后创建分镜场次。
            StoryboardScene scene = StoryboardScene.builder()
                    .storyboardId(storyboardId)
                    .episodeId(storyboardEpisodeId)
                    .sceneNumber(sceneNumber)
                    .sceneHeading(params.getStr("sceneHeading"))
                    .location(params.getStr("location"))
                    .timeOfDay(params.getStr("timeOfDay"))
                    .intExt(params.getStr("intExt"))
                    .baseSceneAssetItemId(baseSceneAssetItemId)
                    .layoutDefinition(params.get("layoutDefinition") != null
                            ? JSONUtil.toJsonStr(params.get("layoutDefinition")) : null)
                    .initialSceneState(params.get("initialSceneState") != null
                            ? JSONUtil.toJsonStr(params.get("initialSceneState")) : null)
                    .shotStrategy(JSONUtil.toJsonStr(params.get("shotStrategy")))
                    .beats(JSONUtil.toJsonStr(params.get("beats")))
                    .sortOrder(params.getInt("sortOrder", 0))
                    .status(1)
                    .build();

            StoryboardScene savedScene = storyboardService.createScene(scene);
            log.info("[save_storyboard_scene_shots] 场次创建成功: sceneId={}, sceneNumber={}",
                    savedScene.getId(), sceneNumber);

            // 3. 批量创建已经通过结构化复核的最终镜头。

            List<StoryboardItem> items = new ArrayList<>();
            for (int i = 0; i < candidateShots.size(); i++) {
                JSONObject shot = candidateShots.get(i);

                // 处理角色子资产ID列表（去重）
                String characterIdsJson = null;
                JSONArray charArray = shot.getJSONArray("characterIds");
                if (charArray != null && !charArray.isEmpty()) {
                    List<Object> uniqueChars = charArray.stream().distinct().collect(Collectors.toList());
                    JSONArray dedupedChars = new JSONArray(uniqueChars);
                    characterIdsJson = dedupedChars.toString();
                }

                // 处理道具子资产ID列表（去重）
                String propIdsJson = null;
                JSONArray propArray = shot.getJSONArray("propIds");
                if (propArray != null && !propArray.isEmpty()) {
                    // 去重
                    List<Object> uniqueProps = propArray.stream().distinct().collect(Collectors.toList());
                    JSONArray dedupedProps = new JSONArray(uniqueProps);
                    propIdsJson = dedupedProps.toString();
                }

                // 处理服装子资产ID列表（去重）
                String costumeIdsJson = null;
                JSONArray costumeArray = shot.getJSONArray("costumeIds");
                if (costumeArray != null && !costumeArray.isEmpty()) {
                    List<Object> uniqueCostumes = costumeArray.stream().distinct().collect(Collectors.toList());
                    JSONArray dedupedCostumes = new JSONArray(uniqueCostumes);
                    costumeIdsJson = dedupedCostumes.toString();
                }

                StoryboardItem item = StoryboardItem.builder()
                        .storyboardId(storyboardId)
                        .storyboardEpisodeId(storyboardEpisodeId)
                        .storyboardSceneId(savedScene.getId())
                        .sortOrder(i)
                        .shotNumber(String.valueOf(i + 1))
                        .beatIds(JSONUtil.toJsonStr(shot.getJSONArray("beatIds")))
                        .shotType(shot.getStr("shotType"))
                        .content(StrUtil.blankToDefault(shot.getStr("content"),
                                StrUtil.blankToDefault(shot.getStr("sceneExpectation"), shot.getStr("dialogue"))))
                        .sceneExpectation(shot.getStr("sceneExpectation"))
                        .dialogue(shot.getStr("dialogue"))
                        .sound(shot.getStr("sound"))
                        .soundEffect(shot.getStr("soundEffect"))
                        .music(shot.getStr("music"))
                        .cameraMovement(shot.getStr("cameraMovement"))
                        .cameraAngle(shot.getStr("cameraAngle"))
                        .cameraEquipment(sanitizeCameraEquipment(shot.getStr("cameraEquipment")))
                        .focalLength(shot.getStr("focalLength"))
                        .transition(shot.getStr("transition"))
                        .characterIds(characterIdsJson)
                        .costumeIds(costumeIdsJson)
                        .sceneAssetItemId(shot.getLong("sceneAssetItemId"))
                        .propIds(propIdsJson)
                        .sceneStateDelta(shot.get("sceneStateDelta") != null
                                ? JSONUtil.toJsonStr(shot.get("sceneStateDelta")) : null)
                        .continuityMode(shot.getStr("continuityMode", "CUT"))
                        .remark(shot.getStr("remark"))
                        .duration(shot.get("duration") != null ? new BigDecimal(shot.getStr("duration")) : null)
                        .aiGenerated(true)
                        .status(1)
                        .build();
                items.add(item);
            }

            if (items.size() != candidateShots.size()) {
                throw new IllegalStateException("最终落库镜头数与复核批准镜头数不一致");
            }

            // 兜底：单场次镜头数上限警告
            if (items.size() > 20) {
                log.warn("[save_storyboard_scene_shots] 单场次镜头数 {} 超过 20，可能存在过度拆分: sceneId={}", items.size(), savedScene.getId());
            }

            storyboardService.batchCreateItems(items);
            log.info("[save_storyboard_scene_shots] 镜头批量创建成功: sceneId={}, count={}",
                    savedScene.getId(), items.size());

            // 4. 基于已批量查询的数据反写场景↔道具关联，无逐镜查询。
            writeBackRelatedAssets(items, baseSceneAssetItemId, itemMap, assetMap);

            JSONObject result = JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardSceneId", savedScene.getId())
                    .set("sceneNumber", sceneNumber)
                    .set("shotCount", items.size())
                    .set("message", "场次分镜保存成功，共 " + items.size() + " 个镜头");
            if (!stateReferenceCorrections.isEmpty()) {
                JSONArray normalizedReferences = new JSONArray();
                for (StateAssetReferenceCorrection correction : stateReferenceCorrections) {
                    normalizedReferences.add(JSONUtil.createObj()
                            .set("assetId", correction.assetId())
                            .set("assetItemId", correction.assetItemId())
                            .set("assetName", correction.assetName()));
                }
                result.set("normalizedStateAssetReferences", normalizedReferences);
            }
            return result.toString();
        } catch (Exception e) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            log.error("保存场次分镜失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "操作失败: " + e.getMessage()).toString();
        }
    }

    /**
     * 从镜头的 sceneAssetItemId 和 propIds 提取场景↔道具关联，
     * 反写到场景资产和道具资产的 properties.relatedAssetIds 中。
     */
    private void writeBackRelatedAssets(
            List<StoryboardItem> items,
            Long baseSceneAssetItemId,
            Map<Long, AssetItem> itemMap,
            Map<Long, Asset> assetMap) {
        try {
            Map<Long, Set<Long>> relatedByAssetId = new HashMap<>();
            for (StoryboardItem item : items) {
                Long sceneItemId = item.getSceneAssetItemId() != null
                        ? item.getSceneAssetItemId() : baseSceneAssetItemId;
                String propIdsJson = item.getPropIds();
                if (sceneItemId == null || StrUtil.isBlank(propIdsJson)) continue;
                AssetItem sceneItem = itemMap.get(sceneItemId);
                if (sceneItem == null || sceneItem.getAssetId() == null) continue;
                Long sceneAssetId = sceneItem.getAssetId();
                JSONArray propIds = JSONUtil.parseArray(propIdsJson);
                for (int i = 0; i < propIds.size(); i++) {
                    Long propItemId = propIds.getLong(i);
                    if (propItemId == null) continue;
                    AssetItem propItem = itemMap.get(propItemId);
                    if (propItem == null || propItem.getAssetId() == null) continue;
                    Long propAssetId = propItem.getAssetId();
                    relatedByAssetId.computeIfAbsent(sceneAssetId, ignored -> new HashSet<>()).add(propAssetId);
                    relatedByAssetId.computeIfAbsent(propAssetId, ignored -> new HashSet<>()).add(sceneAssetId);
                }
            }

            for (Map.Entry<Long, Set<Long>> entry : relatedByAssetId.entrySet()) {
                Asset asset = assetMap.get(entry.getKey());
                if (asset == null) continue;
                JSONObject properties = StrUtil.isNotBlank(asset.getProperties())
                        ? JSONUtil.parseObj(asset.getProperties()) : new JSONObject();
                Set<Long> relatedIds = new HashSet<>();
                JSONArray existing = properties.getJSONArray("relatedAssetIds");
                if (existing != null) {
                    for (int i = 0; i < existing.size(); i++) {
                        Long id = existing.getLong(i);
                        if (id != null) relatedIds.add(id);
                    }
                }
                if (relatedIds.addAll(entry.getValue())) {
                    properties.set("relatedAssetIds", JSONUtil.toJsonStr(new ArrayList<>(relatedIds)));
                    asset.setProperties(properties.toString());
                    assetService.update(asset);
                }
            }
        } catch (Exception e) {
            log.warn("[save_storyboard_scene_shots] 反写关联资产失败（不影响分镜保存）: {}", e.getMessage());
        }
    }

    private Set<String> collectLayoutObjectIds(JSONObject layout) {
        if (layout == null || layout.getJSONArray("anchors") == null) return Set.of();
        Set<String> ids = new HashSet<>();
        JSONArray anchors = layout.getJSONArray("anchors");
        for (int i = 0; i < anchors.size(); i++) {
            String id = anchors.getJSONObject(i).getStr("id");
            if (StrUtil.isNotBlank(id)) ids.add("layout:" + id.trim());
        }
        return ids;
    }

    /**
     * 容器必须是显式存在的固定储物结构或已声明可收纳的道具。不能仅因“放入、收起”等动作
     * 让普通课桌、桌面、椅子凭空获得收纳能力。
     */
    private void validateContainerReferences(
            JSONObject layout,
            JSONObject initialState,
            List<JSONObject> shots) {
        Map<String, Boolean> containerCapabilities = new HashMap<>();
        if (layout != null && layout.getJSONArray("anchors") != null) {
            JSONArray anchors = layout.getJSONArray("anchors");
            for (int i = 0; i < anchors.size(); i++) {
                JSONObject anchor = anchors.getJSONObject(i);
                String id = anchor.getStr("id");
                if (StrUtil.isNotBlank(id)) {
                    containerCapabilities.put("layout:" + id.trim(),
                            Boolean.TRUE.equals(anchor.getBool("canContainObjects")));
                }
            }
        }
        registerContainerCapabilities(initialState, "objects", containerCapabilities);
        validateStateContainerReferences(initialState, "objects", containerCapabilities);
        for (JSONObject shot : shots) {
            JSONObject delta = shot.getJSONObject("sceneStateDelta");
            // 允许本镜头新增已标记的纸箱或柜体承接该镜头的其他对象，但禁止跨镜倒序引用。
            registerContainerCapabilities(delta, "changes", containerCapabilities);
            validateStateContainerReferences(delta, "changes", containerCapabilities);
        }
    }

    private void registerContainerCapabilities(
            JSONObject state,
            String arrayName,
            Map<String, Boolean> containerCapabilities) {
        if (state == null || state.getJSONArray(arrayName) == null) return;
        JSONArray entries = state.getJSONArray(arrayName);
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String objectId = entry.getStr("objectId");
            if (StrUtil.isBlank(objectId)) continue;
            String normalized = objectId.trim();
            if (!normalized.startsWith("layout:")) {
                containerCapabilities.putIfAbsent(normalized, Boolean.FALSE);
                if (entry.containsKey("canContainObjects")) {
                    containerCapabilities.put(normalized,
                            Boolean.TRUE.equals(entry.getBool("canContainObjects")));
                }
            }
        }
    }

    private void validateStateContainerReferences(
            JSONObject state,
            String arrayName,
            Map<String, Boolean> containerCapabilities) {
        if (state == null || state.getJSONArray(arrayName) == null) return;
        JSONArray entries = state.getJSONArray(arrayName);
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String containerId = entry.getStr("containerId");
            if (StrUtil.isBlank(containerId)) continue;
            String normalized = containerId.trim();
            String rawObjectId = entry.getStr("objectId");
            String objectId = StrUtil.isBlank(rawObjectId) ? null : rawObjectId.trim();
            if (normalized.equals(objectId)) {
                throw new IllegalArgumentException("对象不能将自身作为容器: " + normalized);
            }
            Boolean canContainObjects = containerCapabilities.get(normalized);
            if (canContainObjects == null) {
                throw new IllegalArgumentException("容器必须先作为固定布局锚点或场景对象明确声明: " + normalized);
            }
            if (!canContainObjects) {
                throw new IllegalArgumentException("引用的对象未标记为可收纳容器，不能作为 containerId: " + normalized);
            }
        }
    }

    /**
     * 兼容 LLM 将主资产 ID 误写进 assetItem:<ID> 的情况。只有能从当前镜头绑定或
     * 该主资产的唯一子项中确定唯一 AssetItem 时才纠正，存在变体歧义时仍拒绝保存。
     */
    private List<StateAssetReferenceCorrection> normalizeStateAssetItemReferences(
            JSONObject params,
            List<JSONObject> shots,
            Set<String> layoutObjectIds,
            Set<Long> stateAssetItemIds,
            Map<Long, AssetItem> itemMap,
            Long projectId) {
        List<Long> missingStateIds = stateAssetItemIds.stream()
                .filter(id -> !itemMap.containsKey(id))
                .sorted()
                .toList();
        if (missingStateIds.isEmpty()) {
            return List.of();
        }
        Set<Long> missingStateIdSet = new HashSet<>(missingStateIds);

        Map<Long, Asset> mistakenMainAssets = assetService.listByIds(missingStateIds).stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset));
        for (Long missingId : missingStateIds) {
            Asset asset = mistakenMainAssets.get(missingId);
            if (asset == null) {
                throw new IllegalArgumentException(
                        "场景状态引用的道具子资产不存在: " + missingId
                                + "。objectId 必须使用 assets[].items[].assetItemId，禁止使用主资产 assetId");
            }
            if (!Objects.equals(projectId, asset.getProjectId()) || !"prop".equals(asset.getType())) {
                throw new IllegalArgumentException(
                        "场景状态中的 assetItem:" + missingId
                                + " 实际是主资产ID，且不是当前项目的道具资产；请改用正确的道具 AssetItem.id");
            }
        }

        Map<Long, Map<Long, AssetItem>> candidatesByAssetId = new HashMap<>();
        for (AssetItem item : assetService.listItemsByAssetIds(missingStateIds)) {
            candidatesByAssetId.computeIfAbsent(item.getAssetId(), ignored -> new HashMap<>())
                    .put(item.getId(), item);
            itemMap.putIfAbsent(item.getId(), item);
        }
        for (AssetItem item : itemMap.values()) {
            if (missingStateIdSet.contains(item.getAssetId())) {
                candidatesByAssetId.computeIfAbsent(item.getAssetId(), ignored -> new HashMap<>())
                        .put(item.getId(), item);
            }
        }

        Set<Long> referencedPropItemIds = collectPropAssetItemIds(shots);
        Map<Long, Set<String>> objectNamesByAssetId = collectStateObjectNames(params, shots, missingStateIds);
        Map<Long, Long> replacements = new HashMap<>();
        List<StateAssetReferenceCorrection> corrections = new ArrayList<>();
        for (Long assetId : missingStateIds) {
            List<AssetItem> candidates = new ArrayList<>(
                    candidatesByAssetId.getOrDefault(assetId, Map.of()).values());
            candidates.sort((left, right) -> Long.compare(left.getId(), right.getId()));
            AssetItem selected = selectUnambiguousStateAssetItem(
                    assetId, candidates, referencedPropItemIds,
                    objectNamesByAssetId.getOrDefault(assetId, Set.of()));
            replacements.put(assetId, selected.getId());
            Asset asset = mistakenMainAssets.get(assetId);
            corrections.add(new StateAssetReferenceCorrection(assetId, selected.getId(), asset.getName()));
            log.warn("[save_storyboard_scene_shots] 场景状态误用了道具主资产ID，已确定性纠正: assetId={}, assetItemId={}, assetName={}",
                    assetId, selected.getId(), asset.getName());
        }

        rewriteStateAssetItemIds(params.getJSONObject("initialSceneState"), "objects", replacements);
        for (JSONObject shot : shots) {
            rewriteStateAssetItemIds(shot.getJSONObject("sceneStateDelta"), "changes", replacements);
        }

        stateAssetItemIds.clear();
        collectStateAssetItemIds(params.getJSONObject("initialSceneState"), "objects",
                layoutObjectIds, stateAssetItemIds);
        for (JSONObject shot : shots) {
            collectStateAssetItemIds(shot.getJSONObject("sceneStateDelta"), "changes",
                    layoutObjectIds, stateAssetItemIds);
        }
        return corrections;
    }

    private AssetItem selectUnambiguousStateAssetItem(
            Long assetId,
            List<AssetItem> candidates,
            Set<Long> referencedPropItemIds,
            Set<String> stateObjectNames) {
        List<AssetItem> referencedCandidates = candidates.stream()
                .filter(item -> referencedPropItemIds.contains(item.getId()))
                .toList();
        if (referencedCandidates.size() == 1) {
            return referencedCandidates.get(0);
        }
        if (referencedCandidates.size() > 1) {
            List<AssetItem> nameMatches = matchStateObjectNames(referencedCandidates, stateObjectNames);
            if (nameMatches.size() == 1) {
                return nameMatches.get(0);
            }
            throw ambiguousStateAssetItem(assetId, referencedCandidates);
        }

        List<AssetItem> nameMatches = matchStateObjectNames(candidates, stateObjectNames);
        if (nameMatches.size() == 1) {
            return nameMatches.get(0);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw ambiguousStateAssetItem(assetId, candidates);
    }

    private List<AssetItem> matchStateObjectNames(List<AssetItem> candidates, Set<String> stateObjectNames) {
        if (stateObjectNames.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(item -> StrUtil.isNotBlank(item.getName())
                        && stateObjectNames.contains(item.getName().trim()))
                .toList();
    }

    private IllegalArgumentException ambiguousStateAssetItem(Long assetId, List<AssetItem> candidates) {
        String candidateSummary = candidates.stream()
                .map(item -> item.getId() + "(" + StrUtil.blankToDefault(item.getName(), "未命名")
                        + "/" + StrUtil.blankToDefault(item.getItemType(), "未分类") + ")")
                .collect(Collectors.joining(", "));
        if (candidateSummary.isBlank()) {
            candidateSummary = "无可用子资产";
        }
        return new IllegalArgumentException(
                "场景状态把道具主资产ID " + assetId
                        + " 写成了 AssetItem.id，但无法唯一确定子资产。候选: " + candidateSummary
                        + "。请将 objectId 改为与 shots[].propIds 一致的 assetItem:<AssetItem.id>");
    }

    private Set<Long> collectPropAssetItemIds(List<JSONObject> shots) {
        Set<Long> result = new HashSet<>();
        for (JSONObject shot : shots) {
            JSONArray propIds = shot.getJSONArray("propIds");
            if (propIds == null) continue;
            for (int i = 0; i < propIds.size(); i++) {
                Long id = propIds.getLong(i);
                if (id != null) result.add(id);
            }
        }
        return result;
    }

    private void validateStateAssetItemsAreBoundToShots(
            Set<Long> stateAssetItemIds,
            List<JSONObject> shots) {
        Set<Long> propItemIds = collectPropAssetItemIds(shots);
        List<Long> unboundStateItemIds = stateAssetItemIds.stream()
                .filter(id -> !propItemIds.contains(id))
                .sorted()
                .toList();
        if (!unboundStateItemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "场景状态引用的道具子资产未绑定到任何 shots[].propIds: " + unboundStateItemIds
                            + "。状态 objectId 必须与同一道具的 propIds 使用相同 AssetItem.id");
        }
    }

    private Map<Long, Set<String>> collectStateObjectNames(
            JSONObject params,
            List<JSONObject> shots,
            List<Long> mistakenAssetIds) {
        Set<Long> mistakenIdSet = new HashSet<>(mistakenAssetIds);
        Map<Long, Set<String>> result = new HashMap<>();
        collectStateObjectNames(params.getJSONObject("initialSceneState"), "objects", mistakenIdSet, result);
        for (JSONObject shot : shots) {
            collectStateObjectNames(shot.getJSONObject("sceneStateDelta"), "changes", mistakenIdSet, result);
        }
        return result;
    }

    private void collectStateObjectNames(
            JSONObject state,
            String arrayName,
            Set<Long> mistakenAssetIds,
            Map<Long, Set<String>> result) {
        if (state == null || state.getJSONArray(arrayName) == null) return;
        JSONArray entries = state.getJSONArray(arrayName);
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String objectId = entry.getStr("objectId");
            if (StrUtil.isBlank(objectId) || !objectId.startsWith("assetItem:")) continue;
            try {
                Long id = Long.parseLong(objectId.substring("assetItem:".length()));
                String objectName = entry.getStr("objectName");
                if (mistakenAssetIds.contains(id) && StrUtil.isNotBlank(objectName)) {
                    result.computeIfAbsent(id, ignored -> new HashSet<>()).add(objectName.trim());
                }
            } catch (NumberFormatException ignored) {
                // collectStateAssetItemIds is responsible for the actionable format error.
            }
        }
    }

    private void rewriteStateAssetItemIds(
            JSONObject state,
            String arrayName,
            Map<Long, Long> replacements) {
        if (state == null || state.getJSONArray(arrayName) == null) return;
        JSONArray entries = state.getJSONArray(arrayName);
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            rewriteStateAssetItemId(entry, "objectId", replacements);
            rewriteStateAssetItemId(entry, "containerId", replacements);
        }
    }

    private void rewriteStateAssetItemId(
            JSONObject entry,
            String fieldName,
            Map<Long, Long> replacements) {
        String referenceId = entry.getStr(fieldName);
        if (StrUtil.isBlank(referenceId) || !referenceId.startsWith("assetItem:")) return;
        try {
            Long originalId = Long.parseLong(referenceId.substring("assetItem:".length()));
            Long replacementId = replacements.get(originalId);
            if (replacementId != null) {
                entry.set(fieldName, "assetItem:" + replacementId);
            }
        } catch (NumberFormatException ignored) {
            // collectStateAssetItemIds is responsible for the actionable format error.
        }
    }

    private void collectStateAssetItemIds(
            JSONObject state,
            String arrayName,
            Set<String> layoutObjectIds,
            Set<Long> result) {
        if (state == null) return;
        JSONArray entries = state.getJSONArray(arrayName);
        if (entries == null) return;
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            collectStateAssetItemId(entry.getStr("objectId"), layoutObjectIds, result, "对象ID");
            collectStateAssetItemId(entry.getStr("containerId"), layoutObjectIds, result, "容器ID");
        }
    }

    private void collectStateAssetItemId(
            String referenceId,
            Set<String> layoutObjectIds,
            Set<Long> result,
            String fieldName) {
        if (StrUtil.isBlank(referenceId)) {
            if ("对象ID".equals(fieldName)) {
                throw new IllegalArgumentException("场景状态对象缺少 objectId");
            }
            return;
        }
        String normalized = referenceId.trim();
        if (normalized.startsWith("layout:")) {
            if (!layoutObjectIds.contains(normalized)) {
                throw new IllegalArgumentException("场景状态引用了不存在的布局锚点: " + normalized);
            }
            return;
        }
        if (!normalized.startsWith("assetItem:")) {
            throw new IllegalArgumentException("场景状态" + fieldName
                    + "必须使用 assetItem:<ID> 或 layout:<锚点ID>: " + normalized);
        }
        try {
            result.add(Long.parseLong(normalized.substring("assetItem:".length())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法场景状态" + fieldName + ": " + normalized);
        }
    }

    private void validateSceneAssetItem(
            Long itemId,
            Map<Long, AssetItem> itemMap,
            Map<Long, Asset> assetMap,
            Long projectId,
            String fieldName) {
        if (itemId == null) return;
        AssetItem item = itemMap.get(itemId);
        Asset asset = item != null ? assetMap.get(item.getAssetId()) : null;
        if (item == null || asset == null) {
            throw new IllegalArgumentException(fieldName + " 引用的场景子资产不存在: " + itemId);
        }
        if (!projectId.equals(asset.getProjectId()) || !"scene".equals(asset.getType())) {
            throw new IllegalArgumentException(fieldName + " 必须引用当前项目的场景子资产: " + itemId);
        }
    }

    private void validateShotAssetReferences(
            List<JSONObject> shots,
            Map<Long, AssetItem> itemMap,
            Map<Long, Asset> assetMap,
            Long projectId) {
        for (int i = 0; i < shots.size(); i++) {
            JSONObject shot = shots.get(i);
            validateSceneAssetItem(shot.getLong("sceneAssetItemId"), itemMap, assetMap, projectId,
                    "shots[" + i + "].sceneAssetItemId");
            JSONArray characterIds = shot.getJSONArray("characterIds");
            validateAssetArray(characterIds, "character", null,
                    "shots[" + i + "].characterIds", itemMap, assetMap, projectId);
            warnUnexpectedCharacterItems(characterIds, itemMap, i);
            JSONArray propIds = shot.getJSONArray("propIds");
            validateAssetArray(propIds, "prop", null,
                    "shots[" + i + "].propIds", itemMap, assetMap, projectId);
            warnCameraEquipmentProps(propIds, itemMap, i);
            validateAssetArray(shot.getJSONArray("costumeIds"), "character", "costume",
                    "shots[" + i + "].costumeIds", itemMap, assetMap, projectId);
        }
    }

    private void validateAssetArray(
            JSONArray ids,
            String expectedAssetType,
            String expectedItemType,
            String fieldName,
            Map<Long, AssetItem> itemMap,
            Map<Long, Asset> assetMap,
            Long projectId) {
        if (ids == null) return;
        for (int i = 0; i < ids.size(); i++) {
            Long itemId = ids.getLong(i);
            if (itemId == null) {
                throw new IllegalArgumentException(fieldName + " 包含空资产子项ID");
            }
            AssetItem item = itemMap.get(itemId);
            Asset asset = item != null ? assetMap.get(item.getAssetId()) : null;
            if (item == null || asset == null) {
                throw new IllegalArgumentException(fieldName + " 引用的资产子项不存在: " + itemId);
            }
            if (!Objects.equals(projectId, asset.getProjectId()) || !expectedAssetType.equals(asset.getType())) {
                throw new IllegalArgumentException(fieldName + " 引用了错误项目或类型的资产子项: " + itemId);
            }
            if (expectedItemType != null && !expectedItemType.equals(item.getItemType())) {
                throw new IllegalArgumentException(fieldName + " 必须引用 " + expectedItemType + " 子资产: " + itemId);
            }
        }
    }

    private void warnUnexpectedCharacterItems(JSONArray ids, Map<Long, AssetItem> itemMap, int shotIndex) {
        if (ids == null) return;
        for (int i = 0; i < ids.size(); i++) {
            Long itemId = ids.getLong(i);
            AssetItem item = itemMap.get(itemId);
            if (item != null && "three_view".equals(item.getItemType())) {
                log.warn("[save_storyboard_scene_shots] characterId {} 是 three_view，建议使用 initial 或 variant (shotIndex={})",
                        itemId, shotIndex);
            }
        }
    }

    private void warnCameraEquipmentProps(JSONArray ids, Map<Long, AssetItem> itemMap, int shotIndex) {
        if (ids == null) return;
        for (int i = 0; i < ids.size(); i++) {
            Long itemId = ids.getLong(i);
            AssetItem item = itemMap.get(itemId);
            String name = item != null ? item.getName() : null;
            if (StrUtil.isNotBlank(name) && EQUIPMENT_BLACKLIST.stream().anyMatch(name::contains)) {
                log.warn("[save_storyboard_scene_shots] 疑似拍摄设备被混入 propIds: itemId={}, name={}, shotIndex={}",
                        itemId, name, shotIndex);
            }
        }
    }

    /**
     * 拍摄设备黑名单：这些设备名不允许出现在 cameraEquipment 中，
     * 会被替换为安全默认值，避免下游生图/生视频时将设备误入画面。
     */
    private static final List<String> EQUIPMENT_BLACKLIST = Arrays.asList(
            "无人机", "穿梭机", "飞行器", "轨道车", "摇臂", "斯坦尼康", "手持云台");

    /**
     * 规范化拍摄设备：将黑名单设备替换为三脚架，并记录警告。
     */
    private String sanitizeCameraEquipment(String equipment) {
        if (StrUtil.isBlank(equipment)) {
            return "三脚架";
        }
        for (String blacklisted : EQUIPMENT_BLACKLIST) {
            if (equipment.contains(blacklisted)) {
                log.warn("[save_storyboard_scene_shots] 拍摄设备 '{}' 包含黑名单关键词 '{}'，替换为'三脚架'", equipment, blacklisted);
                return "三脚架";
            }
        }
        return equipment;
    }

    private record StateAssetReferenceCorrection(Long assetId, Long assetItemId, String assetName) {
    }
}
