package com.tonyhe.fusion.service.storyboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneStatePromptCompilerTests {

    @Mock
    private StoryboardService storyboardService;
    @Mock
    private AssetService assetService;
    @Mock
    private StoryboardMapper storyboardMapper;
    @Mock
    private StoryboardSceneMapper sceneMapper;
    @Mock
    private StoryboardItemMapper itemMapper;

    private ObjectMapper objectMapper;
    private SceneStatePromptCompiler compiler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SceneStateTimelineService timelineService = new SceneStateTimelineService(
                storyboardMapper, sceneMapper, itemMapper, assetService, objectMapper);
        compiler = new SceneStatePromptCompiler(storyboardService, timelineService, assetService);
    }

    @Test
    void invisibleObjectDoesNotRenderHistoricalDrawerLocationAndFurnitureRestrictionsEnterNegativePrompt() {
        SceneLayoutDefinition layout = SceneLayoutDefinition.builder().anchors(List.of(
                SceneLayoutAnchor.builder()
                        .id("student_desks")
                        .name("学生课桌阵列")
                        .kind("fixed_furniture")
                        .furnitureLayout(FixedFurnitureLayout.builder()
                                .arrangement("grid")
                                .rows(5)
                                .columns(6)
                                .aisleDescription("中间主过道")
                                .empty(true)
                                .prohibitedFeatures(List.of("抽屉", "储物柜", "独立金属柜体"))
                                .build())
                        .build()))
                .build();
        SceneStateSnapshot before = SceneStateSnapshot.builder().objects(List.of(
                SceneObjectState.builder()
                        .objectId("assetItem:101")
                        .objectName("水果糖")
                        .location("苏念抽屉")
                        .visible(false)
                        .build()))
                .build();

        String compiled = compileFrame(layout, before);
        String negative = compiler.compileFrameNegativePrompt(compiled, "肢体畸形");

        assertThat(compiled).contains("规则网格排列", "5行6列", "中间主过道", "禁止生成抽屉、储物柜、独立金属柜体");
        assertThat(compiled).doesNotContain("苏念抽屉");
        assertThat(compiled).contains("水果糖不可见，不在画面中生成，也不得为其补出容器或收纳结构");
        assertThat(negative).contains("抽屉", "储物柜", "独立金属柜体");
    }

    @Test
    void visibleObjectUsesDeclaredContainerNameInsteadOfInternalId() {
        SceneStateSnapshot before = SceneStateSnapshot.builder().objects(List.of(
                SceneObjectState.builder()
                        .objectId("assetItem:201")
                        .objectName("纸箱")
                        .canContainObjects(true)
                        .visible(true)
                        .build(),
                SceneObjectState.builder()
                        .objectId("assetItem:202")
                        .objectName("照片")
                        .containerId("assetItem:201")
                        .visible(true)
                        .build()))
                .build();

        String compiled = compileFrame(new SceneLayoutDefinition(), before);

        assertThat(compiled).contains("照片，位于已声明容器“纸箱”内");
        assertThat(compiled).doesNotContain("已声明容器assetItem:201");
    }

    private String compileFrame(SceneLayoutDefinition layout, SceneStateSnapshot before) {
        try {
            StoryboardItem item = StoryboardItem.builder()
                    .id(1L)
                    .storyboardSceneId(2L)
                    .sceneStateBefore(objectMapper.writeValueAsString(before))
                    .build();
            StoryboardScene scene = StoryboardScene.builder()
                    .id(2L)
                    .layoutDefinition(objectMapper.writeValueAsString(layout))
                    .build();
            when(storyboardService.getItemById(1L)).thenReturn(item);
            when(storyboardService.getSceneById(2L)).thenReturn(scene);
            return compiler.compileFramePrompt(1L, "first", "全景教室画面");
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
