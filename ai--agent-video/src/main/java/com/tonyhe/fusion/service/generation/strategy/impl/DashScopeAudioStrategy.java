package com.tonyhe.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.service.ai.dashscope.DashScopeGenerationSupport;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategy;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼 DashScope TTS 音频生成策略。
 * <p>
 * 使用 DashScope 原生 TTS 接口（/api/v1/services/audio/tts/SpeechSynthesizer），
 * 流式模式（X-DashScope-SSE: enable）直接在内存中接收 Base64 音频片段，
 * 绕过 OSS URL 下载环节。支持 qwen-audio-3.0-tts-plus/flash 等。
 * <p>
 * 鉴权：Authorization: Bearer {apiKey}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashScopeAudioStrategy implements AudioGenerationStrategy {

    public static final String PLATFORM = DashScopeGenerationSupport.PLATFORM;

    private static final String TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer";
    private static final String DEFAULT_VOICE = "longanlingxin";
    private static final String DEFAULT_FORMAT = "mp3";
    private static final int DEFAULT_SAMPLE_RATE = 22050;
    private static final int MAX_TEXT_LENGTH = 5000;

    private final MediaStorageService mediaStorageService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .proxy(Proxy.NO_PROXY)
            .build();

    @Override
    public String getName() {
        return PLATFORM;
    }

    @Override
    public AudioSynthesizeResult synthesize(AudioTask task, AiModel model, String apiKey, String baseUrl) {
        String text = StrUtil.trim(task.getText());
        if (StrUtil.isBlank(text)) {
            throw new BusinessException("TTS 合成文本为空");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            log.warn("[DashScopeTTS] 文本超长({}字)，截断到 {} 字: taskId={}", text.length(), MAX_TEXT_LENGTH, task.getTaskId());
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        if (StrUtil.isBlank(apiKey)) {
            throw new BusinessException("DashScope TTS 缺少 API Key");
        }

        JSONObject config = parseModelConfig(model);
        String modelCode = StrUtil.isNotBlank(model.getCode()) ? model.getCode() : "qwen-audio-3.0-tts-plus";
        String voiceId = resolveVoiceId(task, config);
        String format = config.getStr("audioFormat", DEFAULT_FORMAT);
        int sampleRate = config.getInt("sampleRate", DEFAULT_SAMPLE_RATE);

        String endpoint = resolveEndpoint(baseUrl);
        JSONObject body = buildRequestBody(modelCode, text, voiceId, format, sampleRate, task, config);
        Request request = buildStreamRequest(body, endpoint, apiKey);

        log.info("[DashScopeTTS] 开始流式合成: taskId={}, model={}, voice={}, textLen={}",
                task.getTaskId(), modelCode, voiceId, text.length());

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String respBody = response.body() != null ? response.body().string() : "";
                throw new BusinessException("DashScope TTS 请求失败: HTTP " + response.code() + " " + previewText(respBody));
            }

            // 流式读取 SSE 事件，拼接 Base64 音频片段
            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
            BufferedSource source = response.body().source();
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) break;
                if (!line.startsWith("data:")) continue;
                String jsonStr = line.substring(5).trim();
                if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) continue;
                String base64Audio = extractAudioData(jsonStr);
                if (base64Audio != null && !base64Audio.isEmpty()) {
                    audioBuffer.write(Base64.getDecoder().decode(base64Audio));
                }
            }

            byte[] audioBytes = audioBuffer.toByteArray();
            if (audioBytes.length == 0) {
                throw new BusinessException("DashScope TTS 流式响应中未收到音频数据");
            }

            String extension = "wav".equalsIgnoreCase(format) ? "wav" : "mp3";
            String audioUrl = mediaStorageService.storeBytes(audioBytes, "audio", extension);

            Integer durationMs = estimateDurationMs(audioBytes.length, format);
            log.info("[DashScopeTTS] 合成成功: taskId={}, audioUrl={}, size={}bytes, durationMs={}",
                    task.getTaskId(), audioUrl, audioBytes.length, durationMs);

            return new AudioSynthesizeResult(audioUrl, durationMs);
        } catch (IOException e) {
            throw new BusinessException("DashScope TTS 请求异常: " + e.getMessage());
        }
    }

    @Override
    public List<VoiceOption> listVoices(AiModel model) {
        JSONObject config = parseModelConfig(model);
        List<VoiceOption> defaults = List.of(
                new VoiceOption("longanlingxin", "龙安灵心（知心温暖女声）"),
                new VoiceOption("longanlufeng", "龙安鲁风（明亮开朗男声）")
        );
        cn.hutool.json.JSONArray voiceList = config.getJSONArray("voiceList");
        if (voiceList == null || voiceList.isEmpty()) {
            return defaults;
        }
        List<VoiceOption> options = new ArrayList<>();
        for (Object item : voiceList) {
            JSONObject voice = (JSONObject) item;
            options.add(new VoiceOption(voice.getStr("id"), voice.getStr("name")));
        }
        return options;
    }

    // ========== 内部方法 ==========

    private String resolveEndpoint(String baseUrl) {
        if (StrUtil.isNotBlank(baseUrl)) {
            String trimmed = baseUrl.trim().replaceAll("/+$", "");
            // 已包含完整 TTS 路径
            if (trimmed.endsWith("/services/audio/tts/SpeechSynthesizer")) {
                return trimmed;
            }
            // 去掉已有的 /api/v1 或 /compatible-mode/v1 后缀，统一用 /api/v1
            trimmed = trimmed.replaceAll("(?i)/compatible-mode/v1$", "");
            trimmed = trimmed.replaceAll("(?i)/api/v1$", "");
            return trimmed + TTS_PATH;
        }
        return DashScopeGenerationSupport.DEFAULT_ROOT_URL + TTS_PATH;
    }

    private JSONObject buildRequestBody(String modelCode, String text, String voiceId,
                                        String format, int sampleRate, AudioTask task, JSONObject config) {
        JSONObject body = JSONUtil.createObj();
        body.set("model", modelCode);

        JSONObject input = JSONUtil.createObj();
        input.set("text", text);
        input.set("voice", voiceId);
        input.set("format", format);
        input.set("sample_rate", sampleRate);

        // 语速 / 音量
        JSONObject voiceConfig = parseVoiceConfig(task);
        if (voiceConfig != null) {
            Double rate = voiceConfig.getDouble("speechRate");
            if (rate != null && rate > 0) {
                input.set("rate", rate);
            }
            Integer volume = voiceConfig.getInt("volume");
            if (volume != null && volume >= 0 && volume <= 100) {
                input.set("volume", volume);
            }
        }

        body.set("input", input);
        return body;
    }

    private Request buildStreamRequest(JSONObject body, String endpoint, String apiKey) {
        return new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("X-DashScope-SSE", "enable")
                .build();
    }

    private String resolveVoiceId(AudioTask task, JSONObject config) {
        JSONObject voiceConfig = parseVoiceConfig(task);
        if (voiceConfig != null) {
            String voiceId = voiceConfig.getStr("voiceId");
            if (StrUtil.isNotBlank(voiceId)) {
                return voiceId;
            }
            String customVoiceId = voiceConfig.getStr("customVoiceId");
            if (StrUtil.isNotBlank(customVoiceId)) {
                return customVoiceId;
            }
        }
        return config.getStr("defaultVoice", DEFAULT_VOICE);
    }

    private JSONObject parseVoiceConfig(AudioTask task) {
        if (StrUtil.isBlank(task.getVoiceConfig())) {
            return null;
        }
        try {
            return JSONUtil.parseObj(task.getVoiceConfig());
        } catch (Exception e) {
            log.warn("[DashScopeTTS] voiceConfig 解析失败: taskId={}, config={}", task.getTaskId(), task.getVoiceConfig());
            return null;
        }
    }

    private JSONObject parseModelConfig(AiModel model) {
        if (model == null || StrUtil.isBlank(model.getConfig())) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(model.getConfig());
        } catch (Exception e) {
            log.warn("[DashScopeTTS] 模型 config 解析失败: modelId={}", model.getId());
            return new JSONObject();
        }
    }

    /**
     * 从 SSE 事件 JSON 中提取 Base64 音频数据。
     * 流式响应中 output.audio.data 字段为 Base64 编码的音频片段。
     */
    private String extractAudioData(String jsonStr) {
        try {
            JSONObject json = JSONUtil.parseObj(jsonStr);
            JSONObject output = json.getJSONObject("output");
            if (output != null) {
                JSONObject audio = output.getJSONObject("audio");
                if (audio != null) {
                    return audio.getStr("data");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }


    private Integer estimateDurationMs(int byteCount, String format) {
        if ("wav".equalsIgnoreCase(format)) {
            return (int) (byteCount * 1000L / 48000L);
        }
        return (int) (byteCount * 1000L / 16000L);
    }

    private String previewText(String text) {
        if (StrUtil.isBlank(text)) return "<empty>";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
