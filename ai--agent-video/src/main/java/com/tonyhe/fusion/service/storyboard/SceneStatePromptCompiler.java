package com.tonyhe.fusion.service.storyboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutAnchor;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutDefinition;
import com.tonyhe.fusion.service.storyboard.state.FixedFurnitureLayout;
import com.tonyhe.fusion.service.storyboard.state.SceneObjectState;
import com.tonyhe.fusion.service.storyboard.state.SceneStateChange;
import com.tonyhe.fusion.service.storyboard.state.SceneStateDelta;
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SceneStatePromptCompiler {

    private static final String MARKER = "【服务端场景状态约束】";
    private static final String USER_PROMPT_MARKER = "【用户确认的镜头提示词】";
    private static final Pattern PROHIBITED_FEATURES_PATTERN = Pattern.compile("禁止生成([^；。\\n]+)");

    private final StoryboardService storyboardService;
    private final SceneStateTimelineService timelineService;
    private final AssetService assetService;

    public String compileFramePrompt(Long itemId, String frameType, String prompt) {
        if (itemId == null || StrUtil.isBlank(prompt) || prompt.contains(MARKER)) {
            return prompt;
        }
        StoryboardItem item = storyboardService.getItemById(itemId);
        StoryboardScene scene = item.getStoryboardSceneId() != null
                ? storyboardService.getSceneById(item.getStoryboardSceneId()) : null;
        SceneStateSnapshot target = "last".equalsIgnoreCase(frameType)
                ? timelineService.parseSnapshot(item.getSceneStateAfter())
                : timelineService.parseSnapshot(item.getSceneStateBefore());
        SceneLayoutDefinition layout = scene != null
                ? timelineService.parseLayout(scene.getLayoutDefinition()) : new SceneLayoutDefinition();
        if (!hasConstraints(scene, layout, target, null, item)) {
            return prompt;
        }

        List<String> constraints = commonConstraints(scene, layout, item);
        constraints.add(("last".equalsIgnoreCase(frameType) ? "镜头结束状态：" : "镜头开始状态：")
                + renderSnapshot(target, layout));
        constraints.add("未在状态中声明的位置不得根据常识补写；画面构图可以变化，但固定布局必须服从场景参考图。");
        return wrap(constraints, prompt);
    }

    public String compileVideoPrompt(Long itemId, String prompt) {
        if (itemId == null || StrUtil.isBlank(prompt) || prompt.contains(MARKER)) {
            return prompt;
        }
        StoryboardItem item = storyboardService.getItemById(itemId);
        StoryboardScene scene = item.getStoryboardSceneId() != null
                ? storyboardService.getSceneById(item.getStoryboardSceneId()) : null;
        SceneLayoutDefinition layout = scene != null
                ? timelineService.parseLayout(scene.getLayoutDefinition()) : new SceneLayoutDefinition();
        SceneStateSnapshot before = timelineService.parseSnapshot(item.getSceneStateBefore());
        SceneStateSnapshot after = timelineService.parseSnapshot(item.getSceneStateAfter());
        SceneStateDelta delta = timelineService.parseDelta(item.getSceneStateDelta());
        if (!hasConstraints(scene, layout, before, delta, item)) {
            return prompt;
        }

        List<String> constraints = commonConstraints(scene, layout, item);
        constraints.add("视频起始状态：" + renderSnapshot(before, layout));
        if (delta.getChanges() != null && !delta.getChanges().isEmpty()) {
            constraints.add("本镜头只执行以下场景变化：" + renderDelta(delta, layout, before));
        } else {
            constraints.add("本镜头没有场景物体变化，所有可见对象保持起始状态。");
        }
        constraints.add("视频结束状态：" + renderSnapshot(after, layout));
        constraints.add("禁止在动作过程中擅自移动、增删或改变未列出的场景物体。");
        return wrap(constraints, prompt);
    }

    /** 帧图反向提示词同步锁定服装及场景已明确禁止生成的结构。 */
    public String compileFrameNegativePrompt(String compiledPrompt, String negativePrompt) {
        if (StrUtil.isBlank(compiledPrompt)) {
            return negativePrompt;
        }
        Set<String> additions = new LinkedHashSet<>();
        if (compiledPrompt.contains("角色造型锁定：")) {
            additions.add("服装替换");
            additions.add("未绑定服装");
            additions.add("错误上下装");
            additions.add("错误鞋履");
            additions.add("错误配色");
            additions.add("错误配饰");
        }
        additions.addAll(extractProhibitedFeatures(compiledPrompt));
        if (additions.isEmpty()) {
            return negativePrompt;
        }
        String merged = StrUtil.blankToDefault(negativePrompt, "");
        for (String addition : additions) {
            if (!merged.contains(addition)) {
                merged = StrUtil.isBlank(merged) ? addition : merged + "、" + addition;
            }
        }
        return merged;
    }

    private List<String> extractProhibitedFeatures(String compiledPrompt) {
        int constraintsStart = compiledPrompt.indexOf(MARKER);
        int constraintsEnd = compiledPrompt.indexOf(USER_PROMPT_MARKER);
        if (constraintsStart < 0 || constraintsEnd <= constraintsStart) {
            return List.of();
        }
        Matcher matcher = PROHIBITED_FEATURES_PATTERN.matcher(
                compiledPrompt.substring(constraintsStart, constraintsEnd));
        Set<String> features = new LinkedHashSet<>();
        while (matcher.find()) {
            for (String feature : matcher.group(1).split("[、,，]")) {
                String normalized = feature.trim().replaceAll("[）)]+$", "");
                if (StrUtil.isNotBlank(normalized) && normalized.length() <= 80) {
                    features.add(normalized);
                }
            }
        }
        return new ArrayList<>(features);
    }

    private boolean hasConstraints(
            StoryboardScene scene,
            SceneLayoutDefinition layout,
            SceneStateSnapshot snapshot,
            SceneStateDelta delta,
            StoryboardItem item) {
        return (scene != null && scene.getBaseSceneAssetItemId() != null)
                || (item != null && item.getSceneAssetItemId() != null)
                || (layout != null && layout.getAnchors() != null && !layout.getAnchors().isEmpty())
                || (snapshot != null && snapshot.getObjects() != null && !snapshot.getObjects().isEmpty())
                || (delta != null && delta.getChanges() != null && !delta.getChanges().isEmpty())
                || (item != null && (StrUtil.isNotBlank(item.getCostumeIds()) || StrUtil.isNotBlank(item.getCharacterIds())));
    }

    private List<String> commonConstraints(StoryboardScene scene, SceneLayoutDefinition layout, StoryboardItem item) {
        List<String> constraints = new ArrayList<>();
        boolean hasLayoutReference = (scene != null && scene.getBaseSceneAssetItemId() != null)
                || (item != null && item.getSceneAssetItemId() != null)
                || (layout != null && layout.getAnchors() != null && !layout.getAnchors().isEmpty());
        if (hasLayoutReference) {
            constraints.add("固定空间结构、门窗和固定家具以场景参考图为唯一依据；门、窗、墙体和固定家具的数量、可见性及相对位置必须与参考图一致，禁止新增、复制、移位或替换任何固定结构。");
        }
        if (layout != null && layout.getAnchors() != null && !layout.getAnchors().isEmpty()) {
            List<String> anchors = layout.getAnchors().stream().map(this::renderAnchor).toList();
            constraints.add("固定布局锚点：" + String.join("；", anchors) + "。");
        }
        if ((scene != null && scene.getBaseSceneAssetItemId() != null)
                || (item != null && item.getSceneAssetItemId() != null)) {
            constraints.add("场次使用已绑定的场景变体；镜头若有单独场景变体则以镜头绑定为准。");
        }
        String costumeConstraint = renderCostumeConstraint(item);
        if (StrUtil.isNotBlank(costumeConstraint)) {
            constraints.add(costumeConstraint);
        }
        return constraints;
    }

    private String renderCostumeConstraint(StoryboardItem item) {
        if (item == null) {
            return null;
        }
        Map<Long, AssetItem> costumesByAssetId = new LinkedHashMap<>();
        for (Long costumeId : parseIds(item.getCostumeIds())) {
            try {
                AssetItem costume = assetService.getItemById(costumeId);
                if ("costume".equals(costume.getItemType())) {
                    costumesByAssetId.putIfAbsent(costume.getAssetId(), costume);
                }
            } catch (Exception ignored) {
                // 资产异常不应阻断生成，仍由图片参考图和已有提示词兜底。
            }
        }
        if (costumesByAssetId.isEmpty()) {
            for (Long characterId : parseIds(item.getCharacterIds())) {
                try {
                    AssetItem character = assetService.getItemById(characterId);
                    assetService.listItems(character.getAssetId()).stream()
                            .filter(candidate -> "costume".equals(candidate.getItemType()))
                            .findFirst()
                            .ifPresent(costume -> costumesByAssetId.putIfAbsent(costume.getAssetId(), costume));
                } catch (Exception ignored) {
                    // 单个角色资产缺失时跳过，避免影响其他角色的约束。
                }
            }
        }
        if (costumesByAssetId.isEmpty()) {
            return null;
        }
        List<String> descriptions = costumesByAssetId.values().stream()
                .map(this::renderCostume)
                .toList();
        return "角色造型锁定：" + String.join("；", descriptions)
                + "。除非本镜头状态明确写有换装，否则服装款式、配色、上下装、鞋履和配饰必须与绑定常服参考图完全一致；禁止生成未绑定的替换造型。";
    }

    private String renderCostume(AssetItem costume) {
        String description = null;
        if (StrUtil.isNotBlank(costume.getProperties())) {
            try {
                description = JSONUtil.parseObj(costume.getProperties()).getStr("clothing");
            } catch (Exception ignored) {
                // 无法解析时仍保留常服名称，视觉参考图仍是约束来源。
            }
        }
        return StrUtil.isBlank(description) || "无".equals(description.trim())
                ? StrUtil.blankToDefault(costume.getName(), "绑定常服")
                : StrUtil.blankToDefault(costume.getName(), "绑定常服") + "（" + description.trim() + "）";
    }

    private List<Long> parseIds(String idsJson) {
        if (StrUtil.isBlank(idsJson)) {
            return List.of();
        }
        try {
            JSONArray ids = JSONUtil.parseArray(idsJson);
            return ids.toList(Long.class).stream().filter(id -> id != null).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String renderAnchor(SceneLayoutAnchor anchor) {
        String name = StrUtil.blankToDefault(anchor.getName(), anchor.getId());
        List<String> details = new ArrayList<>();
        if (StrUtil.isNotBlank(anchor.getDescription())) {
            details.add(anchor.getDescription());
        }
        FixedFurnitureLayout furnitureLayout = anchor.getFurnitureLayout();
        if (furnitureLayout != null) {
            String layout = renderFurnitureLayout(furnitureLayout);
            if (StrUtil.isNotBlank(layout)) {
                details.add(layout);
            }
        }
        if (Boolean.TRUE.equals(anchor.getCanContainObjects())) {
            details.add("该固定结构是已声明的真实容器");
        }
        return details.isEmpty() ? name : name + "（" + String.join("；", details) + "）";
    }

    private String renderFurnitureLayout(FixedFurnitureLayout layout) {
        List<String> details = new ArrayList<>();
        if (StrUtil.isNotBlank(layout.getArrangement())) {
            String arrangement = switch (layout.getArrangement()) {
                case "grid" -> "规则网格排列";
                case "row" -> "成排排列";
                case "line" -> "线性排列";
                case "cluster" -> "固定分组排列";
                default -> "按场景参考图固定排列";
            };
            details.add(arrangement);
        }
        if (layout.getRows() != null && layout.getColumns() != null) {
            details.add(layout.getRows() + "行" + layout.getColumns() + "列");
        }
        if (StrUtil.isNotBlank(layout.getAisleDescription())) {
            details.add("过道：" + layout.getAisleDescription());
        }
        if (Boolean.TRUE.equals(layout.getEmpty())) {
            details.add("家具表面为空置状态，无多余杂物");
        }
        if (layout.getProhibitedFeatures() != null && !layout.getProhibitedFeatures().isEmpty()) {
            details.add("禁止生成" + String.join("、", layout.getProhibitedFeatures()));
        }
        return String.join("，", details);
    }

    private String renderSnapshot(SceneStateSnapshot snapshot, SceneLayoutDefinition layout) {
        if (snapshot == null || snapshot.getObjects() == null || snapshot.getObjects().isEmpty()) {
            return "无额外可移动对象状态";
        }
        Map<String, String> containerNames = buildContainerNames(layout, snapshot, null);
        return snapshot.getObjects().stream()
                .map(object -> renderObject(object, containerNames))
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    private String renderObject(SceneObjectState object, Map<String, String> containerNames) {
        StringBuilder value = new StringBuilder(StrUtil.blankToDefault(object.getObjectName(), object.getObjectId()));
        if (Boolean.FALSE.equals(object.getVisible())) {
            // 不可见对象只描述可见性，禁止用其 location/container 反推出抽屉、柜体等未声明结构。
            value.append("不可见，不在画面中生成，也不得为其补出容器或收纳结构");
            if (StrUtil.isNotBlank(object.getState())) value.append("，状态为").append(object.getState());
            return value.toString();
        }
        if (Boolean.TRUE.equals(object.getCanContainObjects())) value.append("（已声明的真实容器）");
        if (StrUtil.isNotBlank(object.getLocation())) value.append("位于").append(object.getLocation());
        value.append(renderContainerPhrase(object.getContainerId(), containerNames));
        if (StrUtil.isNotBlank(object.getState())) value.append("，状态为").append(object.getState());
        return value.toString();
    }

    private String renderDelta(SceneStateDelta delta, SceneLayoutDefinition layout, SceneStateSnapshot before) {
        Map<String, String> containerNames = buildContainerNames(layout, before, delta);
        return delta.getChanges().stream()
                .map(change -> renderChange(change, containerNames))
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    private String renderChange(SceneStateChange change, Map<String, String> containerNames) {
        String name = StrUtil.blankToDefault(change.getObjectName(), change.getObjectId());
        return switch (change.getOperation()) {
            case "MOVE" -> name + "移动到" + change.getLocation()
                    + renderContainerPhrase(change.getContainerId(), containerNames);
            case "SHOW", "ADD" -> name + "进入/出现在场景中"
                    + (StrUtil.isBlank(change.getLocation()) ? "" : "，位置为" + change.getLocation())
                    + renderContainerPhrase(change.getContainerId(), containerNames);
            case "HIDE", "REMOVE" -> name + "离开或移出场景";
            case "SET_STATE" -> name + "变为" + StrUtil.blankToDefault(change.getState(), "指定状态")
                    + renderContainerPhrase(change.getContainerId(), containerNames);
            default -> renderObject(SceneObjectState.builder()
                    .objectId(change.getObjectId())
                    .objectName(change.getObjectName())
                    .location(change.getLocation())
                    .state(change.getState())
                    .containerId(change.getContainerId())
                    .canContainObjects(change.getCanContainObjects())
                    .visible(change.getVisible())
                    .build(), containerNames);
        };
    }

    private Map<String, String> buildContainerNames(
            SceneLayoutDefinition layout,
            SceneStateSnapshot snapshot,
            SceneStateDelta delta) {
        Map<String, String> containerNames = new LinkedHashMap<>();
        if (layout != null && layout.getAnchors() != null) {
            for (SceneLayoutAnchor anchor : layout.getAnchors()) {
                if (Boolean.TRUE.equals(anchor.getCanContainObjects())) {
                    containerNames.put("layout:" + anchor.getId(),
                            StrUtil.blankToDefault(anchor.getName(), anchor.getId()));
                }
            }
        }
        if (snapshot != null && snapshot.getObjects() != null) {
            for (SceneObjectState object : snapshot.getObjects()) {
                if (Boolean.TRUE.equals(object.getCanContainObjects()) && !Boolean.FALSE.equals(object.getVisible())) {
                    containerNames.put(object.getObjectId(),
                            StrUtil.blankToDefault(object.getObjectName(), object.getObjectId()));
                }
            }
        }
        if (delta != null && delta.getChanges() != null) {
            for (SceneStateChange change : delta.getChanges()) {
                if (Boolean.TRUE.equals(change.getCanContainObjects())) {
                    containerNames.put(change.getObjectId(),
                            StrUtil.blankToDefault(change.getObjectName(), change.getObjectId()));
                }
            }
        }
        return containerNames;
    }

    private String renderContainerPhrase(String containerId, Map<String, String> containerNames) {
        if (StrUtil.isBlank(containerId)) {
            return "";
        }
        String name = containerNames.get(containerId);
        return StrUtil.isBlank(name) ? "" : "，位于已声明容器“" + name + "”内";
    }

    private String wrap(List<String> constraints, String prompt) {
        return MARKER + "\n- " + String.join("\n- ", constraints)
                + "\n【用户确认的镜头提示词】\n" + prompt;
    }
}
