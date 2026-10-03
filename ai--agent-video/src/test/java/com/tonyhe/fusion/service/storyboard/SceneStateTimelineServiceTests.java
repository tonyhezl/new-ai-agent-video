package com.tonyhe.fusion.service.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonyhe.fusion.common.BusinessException;
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
import com.tonyhe.fusion.service.storyboard.state.FixedFurnitureLayout;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutAnchor;
import com.tonyhe.fusion.service.storyboard.state.SceneLayoutDefinition;
import com.tonyhe.fusion.service.storyboard.state.SceneObjectState;
import com.tonyhe.fusion.service.storyboard.state.SceneStateChange;
import com.tonyhe.fusion.service.storyboard.state.SceneStateDelta;
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneStateTimelineServiceTests {

    @Mock
    private StoryboardMapper storyboardMapper;
    @Mock
    private StoryboardSceneMapper sceneMapper;
    @Mock
    private StoryboardItemMapper itemMapper;
    @Mock
    private AssetService assetService;

    private SceneStateTimelineService service;

    @BeforeEach
    void setUp() {
        service = new SceneStateTimelineService(
                storyboardMapper, sceneMapper, itemMapper, assetService, new ObjectMapper());
        lenient().when(itemMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void emptyTimelineRemainsEmpty() {
        SceneStateSnapshot after = service.applyDelta(
                new SceneStateSnapshot(), new SceneStateDelta());

        assertThat(after.getObjects()).isEmpty();
    }

    @Test
    void moveAfterBecomesNextShotBefore() {
        SceneStateSnapshot initial = snapshot(object("assetItem:101", "纸箱", "门外", "封好", true));
        SceneStateDelta move = delta(change("MOVE", "assetItem:101", "客厅中央", null));

        SceneStateSnapshot firstAfter = service.applyDelta(initial, move);
        SceneStateSnapshot secondBefore = service.applyDelta(firstAfter, new SceneStateDelta());

        assertThat(firstAfter.getObjects()).singleElement()
                .extracting(SceneObjectState::getLocation)
                .isEqualTo("客厅中央");
        assertThat(secondBefore).isEqualTo(firstAfter);
    }

    @Test
    void hideAndRemoveKeepObjectsButMakeThemInvisible() {
        SceneStateSnapshot initial = snapshot(
                object("assetItem:101", "纸箱", "门边", null, true),
                object("assetItem:102", "椅子", "桌边", null, true));
        SceneStateDelta changes = SceneStateDelta.builder().changes(List.of(
                change("HIDE", "assetItem:101", null, null),
                change("REMOVE", "assetItem:102", null, null))).build();

        SceneStateSnapshot after = service.applyDelta(initial, changes);

        assertThat(after.getObjects()).allMatch(object -> !object.getVisible());
        assertThat(after.getObjects().stream()
                .filter(object -> "assetItem:102".equals(object.getObjectId()))
                .findFirst().orElseThrow().getState()).isEqualTo("已移出场景");
    }

    @Test
    void sceneVariantAndLayoutBothAffectContextHash() {
        SceneLayoutDefinition layout = layout(anchor("door_main", "主门", "靠近玄关"));
        SceneLayoutDefinition changedLayout = layout(anchor("door_main", "主门", "通往走廊"));

        String baseline = service.contextHash(201L, layout);

        assertThat(service.contextHash(202L, layout)).isNotEqualTo(baseline);
        assertThat(service.contextHash(201L, changedLayout)).isNotEqualTo(baseline);
        assertThat(service.contextHash(201L, layout)).isEqualTo(baseline);
    }

    @Test
    void gridFurnitureRequiresRowsAndColumns() {
        SceneLayoutAnchor desks = SceneLayoutAnchor.builder()
                .id("student_desks")
                .name("学生课桌阵列")
                .kind("fixed_furniture")
                .furnitureLayout(FixedFurnitureLayout.builder()
                        .arrangement("grid")
                        .rows(5)
                        .build())
                .build();

        assertThatThrownBy(() -> service.contextHash(201L, layout(desks)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("行数和列数");
    }

    @Test
    void deltaCarriesExplicitContainerCapabilityAndReference() {
        SceneStateSnapshot initial = snapshot(
                SceneObjectState.builder()
                        .objectId("assetItem:101")
                        .objectName("纸箱")
                        .canContainObjects(true)
                        .visible(true)
                        .build(),
                object("assetItem:102", "照片", "桌面", null, true));
        SceneStateDelta delta = delta(SceneStateChange.builder()
                .operation("MOVE")
                .objectId("assetItem:102")
                .location("门边")
                .containerId("assetItem:101")
                .build());

        SceneStateSnapshot after = service.applyDelta(initial, delta);

        SceneObjectState photo = after.getObjects().stream()
                .filter(object -> "assetItem:102".equals(object.getObjectId()))
                .findFirst()
                .orElseThrow();
        assertThat(photo.getContainerId()).isEqualTo("assetItem:101");
        assertThat(after.getObjects().stream()
                .filter(object -> "assetItem:101".equals(object.getObjectId()))
                .findFirst().orElseThrow().getCanContainObjects()).isTrue();
    }

    @Test
    void unmarkedContainerReferenceIsRejectedBeforeAssetLookup() {
        StoryboardScene scene = StoryboardScene.builder().id(1L).storyboardId(2L).build();
        when(sceneMapper.selectById(1L)).thenReturn(scene);
        when(storyboardMapper.selectById(2L)).thenReturn(
                Storyboard.builder().id(2L).projectId(3L).build());
        when(itemMapper.selectList(any())).thenReturn(List.of(
                StoryboardItem.builder().id(10L).storyboardSceneId(1L).build()));

        StoryboardShotSceneStateUpdateReqVO shot = new StoryboardShotSceneStateUpdateReqVO();
        shot.setItemId(10L);
        StoryboardSceneStateTimelineUpdateReqVO request = new StoryboardSceneStateTimelineUpdateReqVO();
        request.setLayoutDefinition(new SceneLayoutDefinition());
        request.setInitialState(snapshot(SceneObjectState.builder()
                .objectId("assetItem:101")
                .objectName("糖果")
                .containerId("assetItem:102")
                .visible(false)
                .build()));
        request.setShots(List.of(shot));

        assertThatThrownBy(() -> service.updateTimeline(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("容器必须先作为固定布局锚点或场景对象明确声明");
    }

    @Test
    void nonPropAssetItemIsRejectedAsMovableObject() {
        StoryboardScene scene = StoryboardScene.builder()
                .id(1L).storyboardId(2L).build();
        when(sceneMapper.selectById(1L)).thenReturn(scene);
        when(storyboardMapper.selectById(2L)).thenReturn(
                Storyboard.builder().id(2L).projectId(3L).build());
        when(assetService.listItemsByIds(Set.of(101L))).thenReturn(List.of(
                AssetItem.builder().id(101L).assetId(201L).build()));
        when(assetService.listByIds(Set.of(201L))).thenReturn(List.of(
                Asset.builder().id(201L).projectId(3L).type("character").build()));

        StoryboardSceneStateTimelineUpdateReqVO request = new StoryboardSceneStateTimelineUpdateReqVO();
        request.setLayoutDefinition(new SceneLayoutDefinition());
        request.setInitialState(snapshot(object("assetItem:101", "错误角色对象", null, null, true)));

        assertThatThrownBy(() -> service.updateTimeline(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可移动对象必须引用道具资产");
    }

    @Test
    void missingLayoutAnchorReferenceIsRejected() {
        StoryboardScene scene = StoryboardScene.builder()
                .id(1L).storyboardId(2L).build();
        when(sceneMapper.selectById(1L)).thenReturn(scene);
        when(storyboardMapper.selectById(2L)).thenReturn(
                Storyboard.builder().id(2L).projectId(3L).build());

        StoryboardSceneStateTimelineUpdateReqVO request = new StoryboardSceneStateTimelineUpdateReqVO();
        request.setLayoutDefinition(new SceneLayoutDefinition());
        request.setInitialState(snapshot(object("layout:missing", "不存在的门", null, null, true)));

        assertThatThrownBy(() -> service.updateTimeline(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在的布局锚点");
    }

    @Test
    void fullTimelineUpdateRejectsMissingShot() {
        StoryboardScene scene = StoryboardScene.builder().id(1L).storyboardId(2L).build();
        when(sceneMapper.selectById(1L)).thenReturn(scene);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                StoryboardItem.builder().id(10L).storyboardSceneId(1L).build()));

        StoryboardSceneStateTimelineUpdateReqVO request = new StoryboardSceneStateTimelineUpdateReqVO();

        assertThatThrownBy(() -> service.updateTimeline(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("全部镜头");
    }

    @Test
    void propAssetCannotBeUsedAsShotSceneVariant() {
        StoryboardScene scene = StoryboardScene.builder().id(1L).storyboardId(2L).build();
        when(sceneMapper.selectById(1L)).thenReturn(scene);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                StoryboardItem.builder().id(10L).storyboardSceneId(1L).build()));
        when(storyboardMapper.selectById(2L)).thenReturn(
                Storyboard.builder().id(2L).projectId(3L).build());
        when(assetService.listItemsByIds(Set.of(101L))).thenReturn(List.of(
                AssetItem.builder().id(101L).assetId(201L).build()));
        when(assetService.listByIds(Set.of(201L))).thenReturn(List.of(
                Asset.builder().id(201L).projectId(3L).type("prop").build()));

        StoryboardShotSceneStateUpdateReqVO shot = new StoryboardShotSceneStateUpdateReqVO();
        shot.setItemId(10L);
        shot.setSceneAssetItemId(101L);
        StoryboardSceneStateTimelineUpdateReqVO request = new StoryboardSceneStateTimelineUpdateReqVO();
        request.setShots(List.of(shot));

        assertThatThrownBy(() -> service.updateTimeline(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须是场景类型");
    }

    private static SceneStateSnapshot snapshot(SceneObjectState... objects) {
        return SceneStateSnapshot.builder().objects(List.of(objects)).build();
    }

    private static SceneObjectState object(
            String id, String name, String location, String state, boolean visible) {
        return SceneObjectState.builder()
                .objectId(id)
                .objectName(name)
                .location(location)
                .state(state)
                .visible(visible)
                .build();
    }

    private static SceneStateDelta delta(SceneStateChange... changes) {
        return SceneStateDelta.builder().changes(List.of(changes)).build();
    }

    private static SceneStateChange change(
            String operation, String objectId, String location, String state) {
        return SceneStateChange.builder()
                .operation(operation)
                .objectId(objectId)
                .location(location)
                .state(state)
                .build();
    }

    private static SceneLayoutDefinition layout(SceneLayoutAnchor... anchors) {
        return SceneLayoutDefinition.builder().anchors(List.of(anchors)).build();
    }

    private static SceneLayoutAnchor anchor(String id, String name, String description) {
        return SceneLayoutAnchor.builder()
                .id(id)
                .name(name)
                .kind("door")
                .description(description)
                .build();
    }
}
