package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.storyboard.StoryboardShotPlanValidator;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaveStoryboardSceneShotsToolExecutorTests {

    private static final long STORYBOARD_ID = 28L;
    private static final long EPISODE_ID = 42L;
    private static final long PROJECT_ID = 77L;
    private static final long SCENE_ITEM_ID = 407L;
    private static final long SCENE_ASSET_ID = 268L;
    private static final long PROP_ASSET_ID = 285L;
    private static final long PROP_ITEM_ID = 418L;

    @Test
    void mistakenMainAssetIdIsNormalizedToTheOnlyReferencedPropItem() {
        Fixture fixture = fixture(List.of(propItem(PROP_ITEM_ID, "同学录卡片")));
        JSONObject params = validParams(new JSONArray().put(PROP_ITEM_ID));

        JSONObject result = JSONUtil.parseObj(fixture.executor().execute(
                params.toString(), ToolExecutionContext.builder().projectId(PROJECT_ID).build()));

        assertThat(result.getStr("status")).isEqualTo("success");
        JSONObject normalizedReference = result.getJSONArray("normalizedStateAssetReferences").getJSONObject(0);
        assertThat(normalizedReference.getLong("assetId")).isEqualTo(PROP_ASSET_ID);
        assertThat(normalizedReference.getLong("assetItemId")).isEqualTo(PROP_ITEM_ID);

        ArgumentCaptor<StoryboardScene> sceneCaptor = ArgumentCaptor.forClass(StoryboardScene.class);
        verify(fixture.storyboardService()).createScene(sceneCaptor.capture());
        JSONObject savedInitialState = JSONUtil.parseObj(sceneCaptor.getValue().getInitialSceneState());
        assertThat(savedInitialState.getJSONArray("objects").getJSONObject(0).getStr("objectId"))
                .isEqualTo("assetItem:" + PROP_ITEM_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoryboardItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.storyboardService()).batchCreateItems(itemsCaptor.capture());
        JSONObject savedDelta = JSONUtil.parseObj(itemsCaptor.getValue().get(0).getSceneStateDelta());
        assertThat(savedDelta.getJSONArray("changes").getJSONObject(0).getStr("objectId"))
                .isEqualTo("assetItem:" + PROP_ITEM_ID);
    }

    @Test
    void mistakenMainAssetIdIsRejectedWhenSeveralReferencedVariantsRemainAmbiguous() {
        AssetItem first = propItem(PROP_ITEM_ID, "同学录卡片");
        AssetItem second = propItem(419L, "同学录卡片");
        Fixture fixture = fixture(List.of(first, second));
        JSONObject params = validParams(new JSONArray().put(PROP_ITEM_ID).put(419L));

        JSONObject result = JSONUtil.parseObj(fixture.executor().execute(
                params.toString(), ToolExecutionContext.builder().projectId(PROJECT_ID).build()));

        assertThat(result.getStr("status")).isEqualTo("error");
        assertThat(result.getStr("message"))
                .contains("主资产ID " + PROP_ASSET_ID)
                .contains("无法唯一确定子资产")
                .contains(String.valueOf(PROP_ITEM_ID))
                .contains("419")
                .contains("shots[].propIds");
        verify(fixture.storyboardService(), never()).createScene(any());
        verify(fixture.storyboardService(), never()).batchCreateItems(any());
    }

    @Test
    void existingStateItemIsRejectedWhenItIsNotBoundToAnyShotPropIds() {
        AssetItem boundItem = propItem(PROP_ITEM_ID, "同学录卡片");
        AssetItem wrongStateItem = propItem(419L, "同学录卡片_破损");
        Fixture fixture = fixture(List.of(boundItem, wrongStateItem));
        JSONObject params = validParams(new JSONArray().put(PROP_ITEM_ID));
        params.getJSONObject("initialSceneState").getJSONArray("objects").getJSONObject(0)
                .set("objectId", "assetItem:419");
        params.getJSONArray("shots").getJSONObject(0)
                .getJSONObject("sceneStateDelta").getJSONArray("changes").getJSONObject(0)
                .set("objectId", "assetItem:419");

        JSONObject result = JSONUtil.parseObj(fixture.executor().execute(
                params.toString(), ToolExecutionContext.builder().projectId(PROJECT_ID).build()));

        assertThat(result.getStr("status")).isEqualTo("error");
        assertThat(result.getStr("message"))
                .contains("未绑定到任何 shots[].propIds")
                .contains("419")
                .contains("相同 AssetItem.id");
        verify(fixture.storyboardService(), never()).createScene(any());
        verify(fixture.storyboardService(), never()).batchCreateItems(any());
    }

    private Fixture fixture(List<AssetItem> propItems) {
        StoryboardService storyboardService = mock(StoryboardService.class);
        AssetService assetService = mock(AssetService.class);
        SaveStoryboardSceneShotsToolExecutor executor = new SaveStoryboardSceneShotsToolExecutor(
                storyboardService, assetService, new StoryboardShotPlanValidator());

        when(storyboardService.getById(STORYBOARD_ID)).thenReturn(Storyboard.builder()
                .id(STORYBOARD_ID)
                .projectId(PROJECT_ID)
                .build());
        when(storyboardService.getEpisodeById(EPISODE_ID)).thenReturn(StoryboardEpisode.builder()
                .id(EPISODE_ID)
                .storyboardId(STORYBOARD_ID)
                .build());
        when(storyboardService.createScene(any())).thenAnswer(invocation -> {
            StoryboardScene scene = invocation.getArgument(0);
            scene.setId(99L);
            return scene;
        });

        AssetItem sceneItem = AssetItem.builder()
                .id(SCENE_ITEM_ID)
                .assetId(SCENE_ASSET_ID)
                .itemType("initial")
                .name("教室")
                .build();
        when(assetService.listItemsByIds(any())).thenReturn(concat(sceneItem, propItems));
        when(assetService.listItemsByAssetIds(any())).thenReturn(propItems);

        Asset sceneAsset = Asset.builder()
                .id(SCENE_ASSET_ID)
                .projectId(PROJECT_ID)
                .type("scene")
                .name("教室")
                .build();
        Asset propAsset = Asset.builder()
                .id(PROP_ASSET_ID)
                .projectId(PROJECT_ID)
                .type("prop")
                .name("同学录卡片")
                .build();
        when(assetService.listByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return List.of(sceneAsset, propAsset).stream()
                    .filter(asset -> ids.contains(asset.getId()))
                    .toList();
        });
        return new Fixture(executor, storyboardService);
    }

    private List<AssetItem> concat(AssetItem sceneItem, List<AssetItem> propItems) {
        java.util.ArrayList<AssetItem> result = new java.util.ArrayList<>();
        result.add(sceneItem);
        result.addAll(propItems);
        return result;
    }

    private AssetItem propItem(Long id, String name) {
        return AssetItem.builder()
                .id(id)
                .assetId(PROP_ASSET_ID)
                .itemType("initial")
                .name(name)
                .build();
    }

    private JSONObject validParams(JSONArray propIds) {
        JSONObject initialState = JSONUtil.createObj().set("objects", new JSONArray().put(
                JSONUtil.createObj()
                        .set("objectId", "assetItem:" + PROP_ASSET_ID)
                        .set("objectName", "同学录卡片")
                        .set("location", "讲台上")
                        .set("visible", true)));
        JSONObject delta = JSONUtil.createObj().set("changes", new JSONArray().put(
                JSONUtil.createObj()
                        .set("operation", "MOVE")
                        .set("objectId", "assetItem:" + PROP_ASSET_ID)
                        .set("objectName", "同学录卡片")
                        .set("location", "苏念手中")));
        JSONObject shot = JSONUtil.createObj()
                .set("beatIds", new JSONArray().put("B1"))
                .set("content", "陈默从讲台拿起同学录卡片递给苏念")
                .set("sceneExpectation", "中景，教室暖光，卡片交接清楚")
                .set("shotType", "中景")
                .set("duration", 8)
                .set("cameraMovement", "固定")
                .set("cameraAngle", "平视")
                .set("cameraEquipment", "三脚架")
                .set("sound", "教室环境声")
                .set("soundEffect", "纸张轻响")
                .set("characterIds", new JSONArray())
                .set("costumeIds", new JSONArray())
                .set("propIds", propIds)
                .set("continuityMode", "CUT")
                .set("sceneStateDelta", delta);
        return JSONUtil.createObj()
                .set("storyboardId", STORYBOARD_ID)
                .set("storyboardEpisodeId", EPISODE_ID)
                .set("sceneNumber", "1-3")
                .set("baseSceneAssetItemId", SCENE_ITEM_ID)
                .set("initialSceneState", initialState)
                .set("shotStrategy", JSONUtil.createObj()
                        .set("primaryType", "DIALOGUE")
                        .set("tags", new JSONArray().put("OBJECT_OPERATION")))
                .set("beats", new JSONArray().put(JSONUtil.createObj()
                        .set("id", "B1")
                        .set("description", "陈默递出同学录卡片")))
                .set("reviewed", true)
                .set("reviewApproved", true)
                .set("reviewedShotCount", 1)
                .set("unresolvedReviewErrors", new JSONArray())
                .set("shots", new JSONArray().put(shot));
    }

    private record Fixture(
            SaveStoryboardSceneShotsToolExecutor executor,
            StoryboardService storyboardService) {
    }
}
