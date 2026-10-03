package com.tonyhe.fusion.controller.storyboard.vo;

import lombok.Data;

import java.util.List;

/**
 * 合成视频请求
 */
@Data
public class ComposeVideoReqVO {

    /** 选配的视频段列表，null 或空表示使用全部视频全时长 */
    private List<ComposeSegmentReqVO> segments;

    /** 转场效果：none(无转场/直接拼接), fade(淡入淡出), dissolve(溶解), wipeleft, wiperight, slideup, slidedown, circleopen, circleclose 等 ffmpeg xfade 支持的效果 */
    private String transition;

    /** 转场持续时间（秒），默认 0.5 */
    private Double transitionDuration;
}
