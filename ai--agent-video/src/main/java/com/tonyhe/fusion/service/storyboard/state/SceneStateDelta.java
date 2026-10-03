package com.tonyhe.fusion.service.storyboard.state;

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
public class SceneStateDelta {
    @Builder.Default
    private List<SceneStateChange> changes = new ArrayList<>();
}
