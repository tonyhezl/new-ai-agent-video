package com.tonyhe.fusion.controller.storyboard.vo;

import com.tonyhe.fusion.service.storyboard.state.SceneLayoutDefinition;
import com.tonyhe.fusion.service.storyboard.state.SceneStateDelta;
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryboardSceneStateTimelineRespVO {
    private Long sceneId;
    private Long baseSceneAssetItemId;
    private SceneLayoutDefinition layoutDefinition;
    private SceneStateSnapshot initialState;
    private Integer timelineVersion;
    @Builder.Default
    private List<ShotStateVO> shots = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShotStateVO {
        private Long itemId;
        private String shotNumber;
        private Integer sortOrder;
        private String content;
        private Long sceneAssetItemId;
        private Long effectiveSceneAssetItemId;
        private String continuityMode;
        private SceneStateSnapshot before;
        private SceneStateDelta delta;
        private SceneStateSnapshot after;
        private String stateBeforeHash;
        private String stateAfterHash;
        private String sceneStateHash;
        private Boolean firstFrameStale;
        private Boolean lastFrameStale;
        private Boolean videoStale;
    }
}
