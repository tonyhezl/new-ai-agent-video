package com.tonyhe.fusion.service.storyboard.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneLayoutAnchor {
    private String id;
    private String name;
    private String kind;
    private String description;
    /**
     * 固定家具组的可验证结构，例如课桌阵列的行列和过道。仅 fixed_furniture 使用。
     */
    private FixedFurnitureLayout furnitureLayout;
    /**
     * 仅在剧本或场景资产明确该固定结构可容纳物体时为 true，例如固定储物柜。
     * 普通课桌、桌面和椅子不能仅凭常识标记为容器。
     */
    @Builder.Default
    private Boolean canContainObjects = false;
}
