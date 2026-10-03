package com.tonyhe.fusion.controller.storyboard.vo;

import com.tonyhe.fusion.service.storyboard.state.SceneStateDelta;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StoryboardShotSceneStateUpdateReqVO {
    @NotNull(message = "分镜条目ID不能为空")
    private Long itemId;
    private Long sceneAssetItemId;
    private String continuityMode;
    private SceneStateDelta delta;
}
