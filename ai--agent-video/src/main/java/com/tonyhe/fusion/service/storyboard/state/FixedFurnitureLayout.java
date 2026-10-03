package com.tonyhe.fusion.service.storyboard.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定家具组的结构约束。仅记录场景参考图或剧本已确认的事实，不承载像素坐标。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedFurnitureLayout {
    /** grid/row/line/cluster/custom */
    private String arrangement;
    private Integer rows;
    private Integer columns;
    private String aisleDescription;
    /** true 表示该固定家具组在场次开始时为空置，不应凭空补出物品或收纳结构。 */
    private Boolean empty;
    @Builder.Default
    private List<String> prohibitedFeatures = new ArrayList<>();
}
