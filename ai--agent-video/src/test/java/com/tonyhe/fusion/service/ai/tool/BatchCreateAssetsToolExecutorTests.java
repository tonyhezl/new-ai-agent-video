package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.project.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchCreateAssetsToolExecutorTests {

    @Mock
    private AssetService assetService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private BatchCreateAssetsToolExecutor executor;

    @Test
    @DisplayName("层级子场景命名不应被误判为变体冲突")
    void testHierarchicalSubSceneAllowed() {
        Long projectId = 100L;
        Long userId = 1L;

        // 模拟已有父场景 "天玄城"
        Asset parentScene = new Asset();
        parentScene.setId(10L);
        parentScene.setName("天玄城");
        parentScene.setType("scene");

        when(projectService.canAccessProject(projectId, userId)).thenReturn(true);
        when(assetService.listByProject(projectId, "scene", null)).thenReturn(List.of(parentScene));
        when(assetService.listByProject(projectId, "prop", null)).thenReturn(Collections.emptyList());
        when(assetService.findByProjectTypeAndName(projectId, "scene", "天玄城·天机铺")).thenReturn(null);
        when(assetService.create(any())).thenAnswer(invocation -> {
            Asset saved = invocation.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        JSONObject input = JSONUtil.createObj()
                .set("projectId", projectId)
                .set("assets", JSONUtil.createArray()
                        .put(JSONUtil.createObj()
                                .set("type", "scene")
                                .set("name", "天玄城·天机铺")
                                .set("description", "天机铺内部")
                                .set("properties", JSONUtil.createObj().set("locationType", "interior"))
                                .set("initialItem", JSONUtil.createObj().set("name", "天机铺").set("properties", JSONUtil.createObj()))
                        )
                );

        ToolExecutionContext context = new ToolExecutionContext();
        context.setUserId(userId);

        String resultJson = executor.execute(input.toString(), context);
        System.out.println("DEBUG testHierarchicalSubSceneAllowed result: " + resultJson);
        JSONObject result = JSONUtil.parseObj(resultJson);

        JSONArray created = result.getJSONArray("created");
        assertNotNull(created, "result was: " + resultJson);
        assertEquals(1, created.size());
        JSONObject item = created.getJSONObject(0);
        assertEquals("天玄城·天机铺", item.getStr("name"));
        assertNull(item.getStr("status"), "不应被 rejected");
        assertEquals(11L, item.getLong("assetId"));
    }

    @Test
    @DisplayName("真正的状态变体命名应被精准拦截")
    void testRealSceneVariantRejected() {
        Long projectId = 100L;
        Long userId = 1L;

        // 模拟已有主场景 "登仙台"
        Asset mainScene = new Asset();
        mainScene.setId(20L);
        mainScene.setName("登仙台");
        mainScene.setType("scene");

        when(projectService.canAccessProject(projectId, userId)).thenReturn(true);
        when(assetService.listByProject(projectId, "scene", null)).thenReturn(List.of(mainScene));
        when(assetService.listByProject(projectId, "prop", null)).thenReturn(Collections.emptyList());

        JSONObject input = JSONUtil.createObj()
                .set("projectId", projectId)
                .set("assets", JSONUtil.createArray()
                        .put(JSONUtil.createObj()
                                .set("type", "scene")
                                .set("name", "登仙台废墟")
                                .set("description", "登仙台被破坏后的废墟")
                                .set("properties", JSONUtil.createObj())
                                .set("initialItem", JSONUtil.createObj().set("name", "登仙台废墟").set("properties", JSONUtil.createObj()))
                        )
                );

        ToolExecutionContext context = new ToolExecutionContext();
        context.setUserId(userId);

        String resultJson = executor.execute(input.toString(), context);
        System.out.println("DEBUG testRealSceneVariantRejected result: " + resultJson);
        JSONObject result = JSONUtil.parseObj(resultJson);

        JSONArray created = result.getJSONArray("created");
        assertNotNull(created);
        assertEquals(1, created.size());
        JSONObject item = created.getJSONObject(0);
        assertEquals("登仙台废墟", item.getStr("name"));
        assertEquals("rejected", item.getStr("status"), "状态变体必须被 rejected");
        assertTrue(item.getStr("reason").contains("疑似同一空间的不同状态"));
    }
}
