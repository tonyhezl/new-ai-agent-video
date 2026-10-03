package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.AssetItem;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GetStoryboardSceneItemsToolExecutor 的单元测试。
 * 重点验证 resolveCharacterName 在 character_ids 指向 costume 等非角色子资产时的名称解析逻辑。
 */
class GetStoryboardSceneItemsToolExecutorTest {

    /**
     * 当 character_ids 指向 costume 子资产（如 id=394, name="苏念 常服", assetId=268）时，
     * resolveCharacterName 应返回主资产名称"苏念"而非子资产名称"苏念 常服"。
     */
    @Test
    void resolveCharacterName_costumeSubAsset_returnsParentAssetName() throws Exception {
        AssetItem costumeItem = AssetItem.builder()
                .id(394L)
                .assetId(268L)
                .itemType("costume")
                .name("苏念 常服")
                .build();

        Map<Long, String> assetNameMap = new HashMap<>();
        assetNameMap.put(268L, "苏念");

        String result = invokeResolveCharacterName(costumeItem, assetNameMap);
        assertEquals("苏念", result);
    }

    /**
     * 当 character_ids 指向 initial 子资产（如 id=392, name="苏念", assetId=268）时，
     * resolveCharacterName 应优先返回主资产名称"苏念"。
     */
    @Test
    void resolveCharacterName_initialSubAsset_returnsParentAssetName() throws Exception {
        AssetItem initialItem = AssetItem.builder()
                .id(392L)
                .assetId(268L)
                .itemType("initial")
                .name("苏念")
                .build();

        Map<Long, String> assetNameMap = new HashMap<>();
        assetNameMap.put(268L, "苏念");

        String result = invokeResolveCharacterName(initialItem, assetNameMap);
        assertEquals("苏念", result);
    }

    /**
     * 当 assetNameMap 不包含主资产 ID 时，回退到子资产名称。
     */
    @Test
    void resolveCharacterName_parentNameMissing_fallsBackToItemName() throws Exception {
        AssetItem item = AssetItem.builder()
                .id(394L)
                .assetId(268L)
                .itemType("costume")
                .name("苏念 常服")
                .build();

        Map<Long, String> assetNameMap = new HashMap<>();

        String result = invokeResolveCharacterName(item, assetNameMap);
        assertEquals("苏念 常服", result);
    }

    /**
     * 当 assetNameMap 为 null 时，回退到子资产名称。
     */
    @Test
    void resolveCharacterName_nullAssetNameMap_fallsBackToItemName() throws Exception {
        AssetItem item = AssetItem.builder()
                .id(392L)
                .assetId(268L)
                .itemType("initial")
                .name("苏念")
                .build();

        String result = invokeResolveCharacterName(item, null);
        assertEquals("苏念", result);
    }

    /**
     * 当 assetItem 为 null 时，返回空字符串。
     */
    @Test
    void resolveCharacterName_nullAssetItem_returnsEmpty() throws Exception {
        String result = invokeResolveCharacterName(null, null);
        assertEquals("", result);
    }

    /**
     * 默认常服名称不应重复"常服"后缀。
     * 模拟 character_ids=[394]（costume 子资产，name="苏念 常服"）时，
     * resolveCharacterName 返回"苏念"，则 costumeName = "苏念 常服"（正确），
     * 而非 "苏念 常服 常服"（错误）。
     */
    @Test
    void resolveCharacterName_preventsDuplicateCostumeSuffix() throws Exception {
        AssetItem costumeItem = AssetItem.builder()
                .id(394L)
                .assetId(268L)
                .itemType("costume")
                .name("苏念 常服")
                .build();

        Map<Long, String> assetNameMap = new HashMap<>();
        assetNameMap.put(268L, "苏念");

        String characterName = invokeResolveCharacterName(costumeItem, assetNameMap);
        String costumeName = characterName + " 常服";

        assertEquals("苏念 常服", costumeName);
        assertNotEquals("苏念 常服 常服", costumeName);
    }

    /**
     * 通过反射调用 private resolveCharacterName 方法。
     */
    private String invokeResolveCharacterName(AssetItem assetItem, Map<Long, String> assetNameMap) throws Exception {
        var method = GetStoryboardSceneItemsToolExecutor.class.getDeclaredMethod(
                "resolveCharacterName", AssetItem.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(null, assetItem, assetNameMap);
    }

    // ===== buildSuggestedReferenceImages 画风参考图注入测试 =====

    /**
     * 当 artStyleRefImageUrls 为 null 时（非首镜头场景），
     * buildSuggestedReferenceImages 不应生成任何 role=style 的参考图。
     */
    @Test
    void buildSuggestedRefImages_nullArtStyleRefs_noStyleEntries() throws Exception {
        JSONArray result = invokeBuildSuggestedReferenceImages(
                null, null, null, null,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                null, null,
                10, null);
        assertTrue(result.isEmpty(), "无任何参考图时应返回空数组");
    }

    /**
     * 当 artStyleRefImageUrls 非空时（首镜头场景），
     * buildSuggestedReferenceImages 应生成 role=style 的参考图。
     */
    @Test
    void buildSuggestedRefImages_withArtStyleRefs_hasStyleEntries() throws Exception {
        List<String> styleUrls = List.of("https://example.com/style1.png");
        String stylePrompt = "仅参考图片{index}的CG渲染精度，";
        JSONArray result = invokeBuildSuggestedReferenceImages(
                null, null, null, null,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                styleUrls, stylePrompt,
                10, null);
        assertFalse(result.isEmpty(), "有画风参考图时应非空");
        assertEquals("style", result.getJSONObject(0).getStr("role"));
        assertTrue(result.getJSONObject(0).getStr("referencePrompt").contains("CG渲染精度"));
    }

    @Test
    void artStyleReferenceOnlyAppearsForFirstFrameOfFirstShot() {
        assertTrue(GetStoryboardSceneItemsToolExecutor.shouldIncludeArtStyleReference(true, "first"));
        assertFalse(GetStoryboardSceneItemsToolExecutor.shouldIncludeArtStyleReference(true, "last"));
        assertFalse(GetStoryboardSceneItemsToolExecutor.shouldIncludeArtStyleReference(false, "first"));
    }

    @Test
    void hasAssetItemIds_emptyArrayFallsBackToDefaultCostume() throws Exception {
        assertFalse(invokeHasAssetItemIds("[]"));
        assertFalse(invokeHasAssetItemIds(null));
        assertTrue(invokeHasAssetItemIds("[418]"));
    }

    @Test
    void buildCostumeReferencePrompt_locksCompleteOutfit() throws Exception {
        String prompt = invokeBuildCostumeReferencePrompt(3, "苏念 常服", "白衬衫、黑色百褶裙、黑色皮鞋");

        assertTrue(prompt.contains("参考图片3"));
        assertTrue(prompt.contains("白衬衫、黑色百褶裙、黑色皮鞋"));
        assertTrue(prompt.contains("上下装、鞋履和配饰必须与该图一致"));
    }

    /**
     * 通过反射调用 private buildSuggestedReferenceImages 方法。
     */
    @SuppressWarnings("unchecked")
    private JSONArray invokeBuildSuggestedReferenceImages(
            String characterIdsJson, String costumeIdsJson, String propIdsJson, Long sceneAssetItemId,
            Map<Long, AssetItem> assetItemMap,
            Map<Long, ?> characterSubAssetUrlMap,
            Map<Long, String> assetNameMap,
            List<String> artStyleRefImageUrls, String artStyleReferencePrompt,
            int maxImages, String prevFirstFrameUrl) throws Exception {
        var method = GetStoryboardSceneItemsToolExecutor.class.getDeclaredMethod(
                "buildSuggestedReferenceImages",
                String.class, String.class, String.class, Long.class,
                Map.class, Map.class, Map.class,
                List.class, String.class,
                int.class, String.class);
        method.setAccessible(true);
        return (JSONArray) method.invoke(null,
                characterIdsJson, costumeIdsJson, propIdsJson, sceneAssetItemId,
                assetItemMap, characterSubAssetUrlMap, assetNameMap,
                artStyleRefImageUrls, artStyleReferencePrompt,
                maxImages, prevFirstFrameUrl);
    }

    private boolean invokeHasAssetItemIds(String idsJson) throws Exception {
        var method = GetStoryboardSceneItemsToolExecutor.class.getDeclaredMethod("hasAssetItemIds", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, idsJson);
    }

    private String invokeBuildCostumeReferencePrompt(int imageIndex, String costumeName, String costumeDescription)
            throws Exception {
        var method = GetStoryboardSceneItemsToolExecutor.class.getDeclaredMethod(
                "buildCostumeReferencePrompt", int.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, imageIndex, costumeName, costumeDescription);
    }
}
