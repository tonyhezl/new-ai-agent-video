package com.tonyhe.fusion.service.generation.strategy;

import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;

import java.util.List;

/**
 * 音频生成（TTS）策略接口
 * <p>
 * 不同 TTS 平台实现此接口。多数 TTS API 同步返回音频字节，
 * 因此主路径为 {@link #synthesize}；如遇异步平台可在实现内部封装 poll 后返回。
 */
public interface AudioGenerationStrategy {

    String getName();

    /**
     * 同步合成语音
     *
     * @param task       配音任务
     * @param model      TTS 模型
     * @param apiKey     API Key
     * @param baseUrl    API 地址（可为空）
     * @return 合成结果（音频 URL + 时长毫秒）
     */
    AudioSynthesizeResult synthesize(AudioTask task, AiModel model, String apiKey, String baseUrl);

    /**
     * 获取模型支持的音色列表
     *
     * @param model TTS 模型
     * @return 音色选项列表
     */
    List<VoiceOption> listVoices(AiModel model);

    /**
     * 合成结果
     */
    record AudioSynthesizeResult(String audioUrl, Integer durationMs) {}

    /**
     * 音色选项
     */
    record VoiceOption(String id, String name) {}
}
