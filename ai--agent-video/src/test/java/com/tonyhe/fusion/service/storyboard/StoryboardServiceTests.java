package com.tonyhe.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.script.ScriptEpisode;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.mapper.script.ScriptEpisodeMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardItemMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.tonyhe.fusion.service.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class StoryboardServiceTests {

    @Mock
    private StoryboardMapper storyboardMapper;

    @Mock
    private StoryboardEpisodeMapper episodeMapper;

    @Mock
    private StoryboardSceneMapper sceneMapper;

    @Mock
    private StoryboardItemMapper itemMapper;

    @Mock
    private ScriptEpisodeMapper scriptEpisodeMapper;

    @Mock
    private TeamService teamService;

    @Mock
    private SceneStateTimelineService sceneStateTimelineService;

    private StoryboardService storyboardService;

    @BeforeEach
    void setUp() {
        storyboardService = new StoryboardService(
                storyboardMapper,
                episodeMapper,
                sceneMapper,
                itemMapper,
                scriptEpisodeMapper,
                teamService,
                sceneStateTimelineService
        );
    }

    @Test
    void bindScriptEpisodeRejectsCrossScriptEpisode() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .build());
        when(storyboardMapper.selectById(21L)).thenReturn(Storyboard.builder()
                .id(21L)
                .scriptId(31L)
                .build());
        when(scriptEpisodeMapper.selectById(41L)).thenReturn(ScriptEpisode.builder()
                .id(41L)
                .scriptId(99L)
                .build());

        assertThatThrownBy(() -> storyboardService.bindScriptEpisode(11L, 41L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("剧本分集不属于当前分镜关联的剧本");

        verify(episodeMapper, never()).updateById(any(StoryboardEpisode.class));
    }

    @Test
    void saveEpisodeForScriptReusesExistingBinding() {
        StoryboardEpisode existing = StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .scriptEpisodeId(41L)
                .episodeNumber(1)
                .build();
        StoryboardEpisode updated = StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .scriptEpisodeId(41L)
                .episodeNumber(1)
                .title("第一集")
                .build();

        when(storyboardMapper.selectById(21L)).thenReturn(Storyboard.builder()
                .id(21L)
                .scriptId(31L)
                .build());
        when(scriptEpisodeMapper.selectById(41L)).thenReturn(ScriptEpisode.builder()
                .id(41L)
                .scriptId(31L)
                .episodeNumber(1)
                .title("第一集")
                .build());
        when(episodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(episodeMapper.selectById(11L)).thenReturn(updated);

        StoryboardEpisode result = storyboardService.saveEpisodeForScript(
                21L, 41L, 1, "第一集", "梗概");

        assertThat(result.getId()).isEqualTo(11L);
        verify(episodeMapper).updateById(any(StoryboardEpisode.class));
        verify(episodeMapper, never()).insert(any(StoryboardEpisode.class));
    }

    @Test
    void clearEpisodeContentDeletesOnlyTargetEpisodeScenesAndItems() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .build());
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                StoryboardScene.builder().id(101L).episodeId(11L).build(),
                StoryboardScene.builder().id(102L).episodeId(11L).build()
        ));

        storyboardService.clearEpisodeContent(11L);

        verify(itemMapper).delete(any(LambdaQueryWrapper.class));
        verify(sceneMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void cutModeRejectsPreviousLastFrameReuse() {
        StoryboardItem current = StoryboardItem.builder()
                .id(2L).storyboardSceneId(10L).continuityMode("CUT").build();
        when(itemMapper.selectById(2L)).thenReturn(current);

        assertThatThrownBy(() -> storyboardService.reusePrevLastFrameAsFirstFrame(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CUT");
    }

    @Test
    void seamlessModeAllowsReuseOnlyWhenHashesMatchAndTailFrameIsCurrent() {
        StoryboardItem previous = StoryboardItem.builder()
                .id(1L)
                .storyboardSceneId(10L)
                .sortOrder(0)
                .lastFrameImageUrl("/tail.png")
                .lastFramePrompt("尾帧")
                .stateAfterHash("same-hash")
                .lastFrameStateHash("same-hash")
                .build();
        StoryboardItem current = StoryboardItem.builder()
                .id(2L)
                .storyboardSceneId(10L)
                .sortOrder(1)
                .continuityMode("SEAMLESS")
                .stateBeforeHash("same-hash")
                .build();
        when(itemMapper.selectById(2L)).thenReturn(current);
        when(itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(previous, current));

        StoryboardItem result = storyboardService.reusePrevLastFrameAsFirstFrame(2L);

        assertThat(result).isSameAs(current);
        verify(itemMapper).update(any(), any());
    }

    @Test
    void reorderRebuildsEachAffectedSceneOnce() {
        when(itemMapper.selectBatchIds(List.of(3L, 2L, 1L))).thenReturn(List.of(
                StoryboardItem.builder().id(1L).storyboardSceneId(10L).build(),
                StoryboardItem.builder().id(2L).storyboardSceneId(10L).build(),
                StoryboardItem.builder().id(3L).storyboardSceneId(10L).build()));

        storyboardService.batchUpdateItemSort(List.of(3L, 2L, 1L));

        verify(itemMapper, times(3)).updateById(any(StoryboardItem.class));
        verify(sceneStateTimelineService).rebuild(10L);
    }
}
