package com.tonyhe.fusion.controller.storyboard.vo;

import com.tonyhe.fusion.service.storyboard.state.SceneLayoutDefinition;
import com.tonyhe.fusion.service.storyboard.state.SceneStateSnapshot;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StoryboardSceneStateTimelineUpdateReqVO {
    private Long baseSceneAssetItemId;
    private SceneLayoutDefinition layoutDefinition;
    private SceneStateSnapshot initialState;
    @Valid
    private List<StoryboardShotSceneStateUpdateReqVO> shots = new ArrayList<>();
}
