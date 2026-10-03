package com.tonyhe.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategy;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 火山引擎 Doubao Seed TTS 2.0 音频生成策略
 * <p>
 * 使用豆包语音 HTTP 单向合成接口，同步返回 NDJSON 流，
 * 每行包含 event=audio 数据（base64 编码的音频片段）。
 * <p>
 * 支持两种接入方式：
 * 1. Agent Plan：endpoint = /api/v3/plan/tts/unidirectional，鉴权用 Ark API Key（ark-xxx）
 * 2. 语音控制台：endpoint = /api/v3/tts/unidirectional，鉴权用语音 API Key 或 AppID+AccessKey
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VolcengineAudioStrategy implements AudioGenerationStrategy {

    public static final String PLATFORM = "volcengine";

    private static final String DEFAULT_TTS_ENDPOINT = "https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional";
    private static final String DEFAULT_RESOURCE_ID = "seed-tts-2.0";
    private static final String DEFAULT_VOICE = "zh_female_vv_uranus_bigtts";
    private static final String DEFAULT_FORMAT = "mp3";
    private static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final int MAX_TEXT_LENGTH = 500;

    private final MediaStorageService mediaStorageService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
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
            log.warn("[VolcengineTTS] 文本超长({}字)，截断到 {} 字: taskId={}", text.length(), MAX_TEXT_LENGTH, task.getTaskId());
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        JSONObject config = parseModelConfig(model);
        String voiceId = resolveVoiceId(task, config);
        String format = config.getStr("audioFormat", DEFAULT_FORMAT);
        int sampleRate = config.getInt("sampleRate", DEFAULT_SAMPLE_RATE);
        String resourceId = config.getStr("resourceId", DEFAULT_RESOURCE_ID);

        // Agent Plan 用 /plan/ 路径，语音控制台用标准路径
        // baseUrl 由 Consumer 根据 ApiConfig.platform 注入
        String endpoint = StrUtil.isNotBlank(baseUrl) ? baseUrl : DEFAULT_TTS_ENDPOINT;

        JSONObject body = buildRequestBody(text, voiceId, format, sampleRate, task, config);

        Request request = buildRequest(body, endpoint, apiKey, resourceId, model);

        log.info("[VolcengineTTS] 开始合成: taskId={}, voice={}, textLen={}",
                task.getTaskId(), voiceId, text.length());

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String respBody = response.body() != null ? response.body().string() : "";
                throw new BusinessException("火山 TTS 请求失败: HTTP " + response.code() + " " + previewText(respBody));
            }

            String responseBody = response.body().string();
            byte[] audioBytes = extractAudioFromResponse(responseBody);

            if (audioBytes == null || audioBytes.length == 0) {
                throw new BusinessException("火山 TTS 返回内容中未找到音频数据: " + previewText(responseBody));
            }

            String extension = "wav".equalsIgnoreCase(format) ? "wav" : "mp3";
            String audioUrl = mediaStorageService.storeBytes(audioBytes, "audio", extension);

            Integer durationMs = estimateDurationMs(audioBytes.length, sampleRate, format);
            log.info("[VolcengineTTS] 合成成功: taskId={}, audioUrl={}, size={}bytes, durationMs={}",
                    task.getTaskId(), audioUrl, audioBytes.length, durationMs);

            return new AudioSynthesizeResult(audioUrl, durationMs);
        } catch (IOException e) {
            throw new BusinessException("火山 TTS 请求异常: " + e.getMessage());
        }
    }

    @Override
    public List<VoiceOption> listVoices(AiModel model) {
        JSONObject config = parseModelConfig(model);
        JSONArray voiceList = config.getJSONArray("voiceList");
        if (voiceList == null || voiceList.isEmpty()) {
            return List.of();
        }
        List<VoiceOption> options = new ArrayList<>();
        for (Object item : voiceList) {
            JSONObject voice = (JSONObject) item;
            options.add(new VoiceOption(voice.getStr("id"), voice.getStr("name")));
        }
        return options;
    }

    // ========== 内部方法 ==========

    private JSONObject buildRequestBody(String text, String voiceId, String format,
                                        int sampleRate, AudioTask task, JSONObject config) {
        JSONObject body = JSONUtil.createObj();
        body.set("user", JSONUtil.createObj().set("uid", "afv_" + task.getProjectId()));

        JSONObject reqParams = JSONUtil.createObj();
        reqParams.set("text", text);
        reqParams.set("speaker", voiceId);

        JSONObject audioParams = JSONUtil.createObj();
        audioParams.set("format", format);
        audioParams.set("sample_rate", sampleRate);
        reqParams.set("audio_params", audioParams);

        // 语速
        JSONObject voiceConfig = parseVoiceConfig(task);
        if (voiceConfig != null) {
            Integer speechRate = voiceConfig.getInt("speechRate");
            if (speechRate != null) {
                audioParams.set("speech_rate", speechRate);
            }
            String emotion = voiceConfig.getStr("emotion");
            if (StrUtil.isNotBlank(emotion) && !"neutral".equalsIgnoreCase(emotion)) {
                JSONObject additions = JSONUtil.createObj();
                additions.set("context_texts", new JSONArray().add("用" + emotionText(emotion) + "的语气"));
                reqParams.set("additions", additions.toString());
            }
        }

        body.set("req_params", reqParams);
        return body;
    }

    private Request buildRequest(JSONObject body, String endpoint, String apiKey,
                                 String resourceId, AiModel model) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Api-Resource-Id", resourceId);

        // 鉴权：优先用 appId + appSecret（旧版），其次用 apiKey（新版 X-Api-Key）
        ApiConfigHolder auth = resolveAuth(model);
        if (StrUtil.isNotBlank(auth.appId) && StrUtil.isNotBlank(auth.appSecret)) {
            builder.addHeader("X-Api-App-Id", auth.appId);
            builder.addHeader("X-Api-Access-Key", auth.appSecret);
        } else if (StrUtil.isNotBlank(auth.apiKey)) {
            builder.addHeader("X-Api-Key", auth.apiKey);
        } else if (StrUtil.isNotBlank(apiKey)) {
            builder.addHeader("X-Api-Key", apiKey);
        } else {
            throw new BusinessException("火山 TTS 缺少鉴权信息，需配置 appId+appSecret 或 apiKey");
        }

        return builder.build();
    }

    private ApiConfigHolder resolveAuth(AiModel model) {
        // auth 由 Consumer 在调用前注入到 model config 的 _auth 节点
        // 简化处理：从 model.config 中读取 auth 节点（由 Consumer 注入）
        JSONObject config = parseModelConfig(model);
        JSONObject auth = config.getJSONObject("_auth");
        if (auth != null) {
            return new ApiConfigHolder(
                    auth.getStr("appId"),
                    auth.getStr("appSecret"),
                    auth.getStr("apiKey"));
        }
        return new ApiConfigHolder(null, null, null);
    }

    private String resolveVoiceId(AudioTask task, JSONObject config) {
        JSONObject voiceConfig = parseVoiceConfig(task);
        if (voiceConfig != null) {
            String voiceId = voiceConfig.getStr("voiceId");
            if (StrUtil.isNotBlank(voiceId)) {
                return voiceId;
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
            log.warn("[VolcengineTTS] voiceConfig 解析失败: taskId={}, config={}", task.getTaskId(), task.getVoiceConfig());
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
            log.warn("[VolcengineTTS] 模型 config 解析失败: modelId={}", model.getId());
            return new JSONObject();
        }
    }

    /**
     * 从响应中提取音频数据。
     * Agent Plan TTS 返回多行/拼接 JSON，每个对象含 code 和 data 字段：
     * - code=0: data 字段为 base64 编码的音频片段
     * - code=20000000: 合成结束
     * 响应可能以换行分隔，也可能是连续拼接的 JSON 对象（无换行）。
     */
    private byte[] extractAudioFromResponse(String responseBody) {
        List<byte[]> chunks = new ArrayList<>();

        // 尝试按换行分割
        String[] lines = responseBody.split("\n");
        if (lines.length > 1) {
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) {
                    continue;
                }
                parseAudioJson(line, chunks);
            }
        } else {
            // 无换行 → 连续拼接的 JSON 对象，逐个提取
            parseConcatenatedJson(responseBody, chunks);
        }

        if (chunks.isEmpty()) {
            return null;
        }

        int totalSize = chunks.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private void parseAudioJson(String jsonStr, List<byte[]> chunks) {
        try {
            JSONObject json = JSONUtil.parseObj(jsonStr);
            int code = json.getInt("code", -1);
            if (code == 0) {
                String data = json.getStr("data");
                if (StrUtil.isNotBlank(data)) {
                    chunks.add(Base64.getDecoder().decode(data));
                }
            }
            // code=20000000 表示合成结束，无需处理
        } catch (Exception e) {
            log.debug("[VolcengineTTS] JSON 行解析失败（跳过）: {}", previewText(jsonStr));
        }
    }

    /**
     * 解析连续拼接的 JSON 对象（无换行分隔）。
     * 通过括号匹配逐个提取 JSON 对象。
     */
    private void parseConcatenatedJson(String text, List<byte[]> chunks) {
        int start = 0;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String jsonStr = text.substring(start, i + 1).trim();
                    parseAudioJson(jsonStr, chunks);
                }
            }
        }
    }

    private Integer estimateDurationMs(int byteCount, int sampleRate, String format) {
        // 粗略估算：mp3 ~ 128kbps = 16KB/s, wav = sampleRate * 2 bytes per sample
        if ("wav".equalsIgnoreCase(format)) {
            return (int) ((byteCount * 1000L) / (sampleRate * 2L));
        }
        // mp3 粗估 16KB/s
        return (int) (byteCount * 1000L / 16000L);
    }

    private String emotionText(String emotion) {
        return switch (emotion.toLowerCase()) {
            case "happy" -> "开心";
            case "sad" -> "悲伤";
            case "angry" -> "愤怒";
            case "surprised" -> "惊讶";
            case "neutral" -> "平静";
            default -> emotion;
        };
    }

    private String previewText(String text) {
        if (StrUtil.isBlank(text)) return "<empty>";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private record ApiConfigHolder(String appId, String appSecret, String apiKey) {}
}
