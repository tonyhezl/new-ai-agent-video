package com.tonyhe.fusion.service.storyboard.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneStateChange {
    private String operation;
    private String objectId;
    private String objectName;
    private String location;
    private String state;
    private String containerId;
    private Boolean canContainObjects;
    private Boolean visible;
}
