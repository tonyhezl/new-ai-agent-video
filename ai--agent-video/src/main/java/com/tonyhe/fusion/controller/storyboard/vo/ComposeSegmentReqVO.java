package com.tonyhe.fusion.controller.storyboard.vo;

import lombok.Data;

/**
 * 合成视频段配置请求
 */
@Data
public class ComposeSegmentReqVO {

    /** 分镜条目ID */
    private Long itemId;

    /** 裁剪起始时间（秒），null 表示从头开始 */
    private Double startSec;

    /** 裁剪结束时间（秒），null 表示到结尾 */
    private Double endSec;

    /** 从上一段到此段的转场效果（none/fade/dissolve/wipeleft/...），仅对第 2 段及之后生效 */
    private String transition;

    /** 转场持续时间（秒），null 表示用默认值 0.5 */
    private Double transitionDuration;
}
