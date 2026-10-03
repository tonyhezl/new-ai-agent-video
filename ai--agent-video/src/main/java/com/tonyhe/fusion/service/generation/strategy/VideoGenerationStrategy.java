package com.tonyhe.fusion.service.generation.strategy;

import com.tonyhe.fusion.entity.generation.VideoTask;

/**
 * 视频生成策略接口
 * <p>
 * 不同平台实现此接口
 */
public interface VideoGenerationStrategy {

    /**
     * 策略名称
     */
    String getName();

    /**
     * 提交生视频任务到平台
     *
     * @param task 生视频任务
     * @return 平台任务ID
     */
    String submit(VideoTask task);

    /**
     * 轮询平台任务状态
     *
     * @param platformTaskId 平台任务ID
     * @param task 生视频任务
     */
    void poll(String platformTaskId, VideoTask task);
}
