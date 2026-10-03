package com.tonyhe.fusion.service.generation.strategy.impl.openaivideo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.tonyhe.fusion.entity.generation.VideoTask;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seedance 视频协议适配器（SuperToken / 第三方代理兼容格式）。
 * <p>
 * 对接代理 API：
 * POST /v1/video/tasks          创建视频生成任务（JSON body）
 * GET  /v1/video/tasks/{id}     查询任务状态
 * <p>
 * 请求体格式：
 * {
 *   "model": "...",
 *   "operation": "generation",
 *   "input": {
 *     "prompt": "...",
 *     "reference_mode": "media" | "frame",
 *     "image": { "url": "..." },           // reference_mode=frame 时使用
 *     "reference_images": [{ "url": "...", "name": "..." }],
 *     "reference_videos": [{ "url": "...", "name": "..." }],
 *     "reference_audios": [{ "url": "...", "name": "..." }]
 *   },
 *   "output": {
 *     "duration": 5,
 *     "aspect_ratio": "16:9",
 *     "generate_audio": false
 *   }
 * }
 * <p>
 * Seedance 2.0 不支持 reference_mode=frame，统一使用 reference_mode=media。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleSeedanceVideoProtocolAdapter implements OpenAiCompatibleVideoProtocolAdapter {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    private final OpenAiCompatibleVideoProtocolSupport support;
    private final SuperTokenMediaUploader mediaUploader;

    @Override
    public String getProtocol() {
        return "seedance";
    }

    @Override
    public String resolveSubmitUrl(OpenAiCompatibleVideoProtocolContext context) {
        return support.resolveApiRoot(context.apiConfig()) + "/v1/video/tasks";
    }

    @Override
    public RequestBody buildSubmitBody(OpenAiCompatibleVideoProtocolContext context) {
        VideoTask task = context.task();
        String modelCode = support.resolveModelCode(context.model());

        JSONObject body = JSONUtil.createObj();
        body.set("model", modelCode);
        body.set("operation", "generation");

        // input
        JSONObject input = JSONUtil.createObj();
        String prompt = StrUtil.trim(task.getPrompt());
        if (StrUtil.isNotBlank(prompt)) {
            input.set("prompt", prompt);
        }

        // Seedance 2.0 不支持 reference_mode=frame，统一使用 media 模式
        input.set("reference_mode", "media");

        // 收集所有媒体文件（clientId, sourceUrl, kind）
        List<MediaEntry> mediaEntries = new ArrayList<>();

        // 参考图片（首帧、尾帧、参考图）
        if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            mediaEntries.add(new MediaEntry("first_frame", task.getFirstFrameImageUrl(), "image"));
        }
        if (StrUtil.isNotBlank(task.getLastFrameImageUrl())) {
            mediaEntries.add(new MediaEntry("last_frame", task.getLastFrameImageUrl(), "image"));
        }
        List<String> referenceImageUrls = support.parseJsonUrls(task.getReferenceImageUrls());
        if (referenceImageUrls != null) {
            for (int i = 0; i < referenceImageUrls.size(); i++) {
                String url = referenceImageUrls.get(i);
                if (StrUtil.isNotBlank(url)) {
                    mediaEntries.add(new MediaEntry("reference_" + (i + 1), url, "image"));
                }
            }
        }

        // 参考视频
        List<String> refVideoUrls = support.parseJsonUrls(task.getReferenceVideoUrls());
        if (refVideoUrls != null) {
            for (int i = 0; i < refVideoUrls.size(); i++) {
                String url = refVideoUrls.get(i);
                if (StrUtil.isNotBlank(url)) {
                    mediaEntries.add(new MediaEntry("reference_video_" + (i + 1), url, "video"));
                }
            }
        }

        // 参考音频
        List<String> refAudioUrls = support.parseJsonUrls(task.getReferenceAudioUrls());
        if (refAudioUrls != null) {
            for (int i = 0; i < refAudioUrls.size(); i++) {
                String url = refAudioUrls.get(i);
                if (StrUtil.isNotBlank(url)) {
                    mediaEntries.add(new MediaEntry("reference_audio_" + (i + 1), url, "audio"));
                }
            }
        }

        // 批量解析所有媒体 URL（本地文件批量上传，HTTP 直接透传）
        Map<String, String> resolvedUrls = resolveMediaUrls(context, mediaEntries);

        // 构建 reference_images
        List<JSONObject> refImages = new ArrayList<>();
        List<JSONObject> refVideos = new ArrayList<>();
        List<JSONObject> refAudios = new ArrayList<>();
        for (MediaEntry entry : mediaEntries) {
            String url = resolvedUrls.get(entry.clientId());
            if (StrUtil.isBlank(url)) {
                continue;
            }
            JSONObject ref = JSONUtil.createObj().set("url", url).set("name", entry.clientId());
            switch (entry.kind()) {
                case "image" -> refImages.add(ref);
                case "video" -> refVideos.add(ref);
                case "audio" -> refAudios.add(ref);
            }
        }
        if (!refImages.isEmpty()) {
            input.set("reference_images", refImages);
        }
        if (!refVideos.isEmpty()) {
            JSONArray arr = new JSONArray();
            refVideos.forEach(arr::add);
            input.set("reference_videos", arr);
        }
        if (!refAudios.isEmpty()) {
            JSONArray arr = new JSONArray();
            refAudios.forEach(arr::add);
            input.set("reference_audios", arr);
        }

        body.set("input", input);

        // output
        JSONObject output = JSONUtil.createObj();
        if (task.getDuration() != null && task.getDuration() > 0) {
            output.set("duration", task.getDuration());
        }
        if (StrUtil.isNotBlank(task.getRatio())) {
            output.set("aspect_ratio", task.getRatio());
        }
        output.set("generate_audio", task.getGenerateAudio() != null ? task.getGenerateAudio() : false);
        body.set("output", output);

        return RequestBody.create(body.toString(), JSON_MEDIA_TYPE);
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseSubmitResponse(OpenAiCompatibleVideoProtocolContext context,
                                                               String responseBody) {
        return parseResult(responseBody);
    }

    @Override
    public String resolveQueryUrl(OpenAiCompatibleVideoProtocolContext context, String trackingId) {
        return resolveSubmitUrl(context) + "/" + trackingId;
    }

    @Override
    public OpenAiCompatibleVideoTaskResult parseQueryResponse(OpenAiCompatibleVideoProtocolContext context,
                                                              String responseBody) {
        return parseResult(responseBody);
    }

    @Override
    public String resolveVideoContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        // 代理在查询结果中直接返回视频 URL，无需单独下载
        return null;
    }

    @Override
    public String resolveCoverContentUrl(OpenAiCompatibleVideoProtocolContext context,
                                         String trackingId,
                                         OpenAiCompatibleVideoTaskResult result) {
        return null;
    }

    private OpenAiCompatibleVideoTaskResult parseResult(String responseBody) {
        JsonNode root = support.readJson(responseBody, "Seedance 视频响应不是合法 JSON");
        JsonNode node = root;
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            node = data.get(0);
        } else if (data.isObject()) {
            node = data;
        }

        String trackingId = support.firstText(node, "id", "task_id", "video_id");
        String status = support.firstText(node, "status", "state");
        String errorMessage = support.extractErrorMessage(root);

        // 代理返回视频 URL 在 result.videos[0].url
        String videoUrl = null;
        String coverUrl = null;
        JsonNode result = node.path("result");
        if (!result.isMissingNode() && !result.isNull()) {
            JsonNode videos = result.path("videos");
            if (videos.isArray() && !videos.isEmpty()) {
                JsonNode firstVideo = videos.get(0);
                videoUrl = support.firstText(firstVideo, "url", "video_url");
                // duration_ms -> seconds
                JsonNode durationMs = firstVideo.path("duration_ms");
                if (durationMs.isNumber()) {
                    int seconds = (int) Math.round(durationMs.asDouble() / 1000.0);
                    if (seconds > 0) {
                        return new OpenAiCompatibleVideoTaskResult(trackingId, status, seconds, videoUrl, coverUrl, errorMessage);
                    }
                }
            }
        }

        // fallback: 顶层字段
        videoUrl = support.firstText(node, "url", "video_url", "videoUrl");
        coverUrl = support.firstText(node, "cover_url", "thumbnail_url", "coverUrl", "thumbnailUrl");
        Integer duration = support.parsePositiveSeconds(support.firstText(node, "duration", "seconds"));
        return new OpenAiCompatibleVideoTaskResult(trackingId, status, duration, videoUrl, coverUrl, errorMessage);
    }

    /**
     * 将本地路径转换为代理可访问的 URL。
     * <p>
     * 代理不支持 base64 data URL，本地 /media/ 路径和画风预设路径需要通过
     * SuperToken 的三步上传流程获取临时 HTTPS URL。
     * 已是 http(s) 的直接透传。
     *
     * @param context   协议上下文（用于获取 resourceApiKey 和 apiConfig）
     * @param sourceUrl 原始 URL
     * @param kind      媒体类型：image / video / audio
     * @param clientId  客户端标识
     * @return 代理可访问的 HTTPS URL
     */
    private Map<String, String> resolveMediaUrls(OpenAiCompatibleVideoProtocolContext context,
                                                   List<MediaEntry> entries) {
        // 分离本地文件和 HTTP URL
        List<SuperTokenMediaUploader.UploadRequest> localFiles = new ArrayList<>();
        for (MediaEntry entry : entries) {
            String trimmed = entry.sourceUrl().trim();
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("data:")) {
                localFiles.add(new SuperTokenMediaUploader.UploadRequest(trimmed, entry.kind(), entry.clientId()));
            }
        }

        // 批量上传本地文件
        Map<String, String> uploadedUrls = Map.of();
        if (!localFiles.isEmpty()) {
            String resourceApiKey = resolveResourceApiKey(context);
            uploadedUrls = mediaUploader.uploadMediaBatch(localFiles, context.apiConfig(), resourceApiKey);
        }

        // 合并结果：本地文件用上传 URL，HTTP/data 直接透传
        Map<String, String> result = new LinkedHashMap<>();
        for (MediaEntry entry : entries) {
            String trimmed = entry.sourceUrl().trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
                result.put(entry.clientId(), trimmed);
            } else {
                result.put(entry.clientId(), uploadedUrls.get(entry.clientId()));
            }
        }
        return result;
    }

    private String resolveResourceApiKey(OpenAiCompatibleVideoProtocolContext context) {
        String resourceApiKey = context.modelConfig() != null
                ? context.modelConfig().getStr("resourceApiKey") : null;
        if (StrUtil.isBlank(resourceApiKey) && context.apiConfig() != null
                && StrUtil.isNotBlank(context.apiConfig().getAppSecret())) {
            resourceApiKey = context.apiConfig().getAppSecret();
        }
        if (StrUtil.isBlank(resourceApiKey)) {
            resourceApiKey = context.apiConfig().getApiKey();
        }
        return resourceApiKey;
    }

    private record MediaEntry(String clientId, String sourceUrl, String kind) {}
}
