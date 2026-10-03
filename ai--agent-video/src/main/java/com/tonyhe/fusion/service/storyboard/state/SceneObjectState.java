package com.tonyhe.fusion.service.storyboard.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneObjectState {
    private String objectId;
    private String objectName;
    private String location;
    private String state;
    /**
     * 可选的已知容器。只允许引用场景中明确存在的固定锚点或道具；不可见对象不会把该字段写入生图提示词。
     */
    private String containerId;
    /**
     * 道具只有在场景资产或剧本已明确其可收纳物体时才可作为 containerId 的目标。
     */
    @Builder.Default
    private Boolean canContainObjects = false;
    @Builder.Default
    private Boolean visible = true;
}
