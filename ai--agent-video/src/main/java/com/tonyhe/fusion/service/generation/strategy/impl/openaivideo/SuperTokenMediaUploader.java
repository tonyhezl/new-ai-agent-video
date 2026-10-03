package com.tonyhe.fusion.service.generation.strategy.impl.openaivideo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.ApiConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SuperToken 代理媒体上传助手。
 * <p>
 * 代理不支持 base64 data URL，需要通过三步上传流程获取临时 HTTPS URL：
 * 1. POST /v1/media/uploads        创建上传会话，获取 presigned upload_url
 * 2. PUT  upload_url               客户端直接上传文件到对象存储
 * 3. POST /v1/media/uploads/complete  确认上传，获取可用的素材 URL
 * <p>
 * 仅用于 SeedanceVideoProtocolAdapter，不影响其他策略。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuperTokenMediaUploader {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    private final OpenAiCompatibleVideoProtocolSupport support;

    private final OkHttpClient uploadHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build();

    /**
     * 将本地/非 HTTP 媒体上传到 SuperToken 代理，返回临时 HTTPS URL。
     *
     * @param sourceUrl      本地路径（/media/...、画风预设路径等）
     * @param apiConfig      API 配置（用于解析 base URL）
     * @param resourceApiKey 资源 API Key（ak_ 开头）
     * @param kind           媒体类型：image / video / audio
     * @param clientId       客户端标识（如 first_frame、reference_1）
     * @return 代理返回的临时 HTTPS URL
     */
    public String uploadMedia(String sourceUrl, ApiConfig apiConfig, String resourceApiKey,
                              String kind, String clientId) {
        Map<String, String> result = uploadMediaBatch(
                List.of(new UploadRequest(sourceUrl, kind, clientId)),
                apiConfig, resourceApiKey);
        return result.get(clientId);
    }

    /**
     * 批量上传多个本地媒体文件，一次创建会话、逐个 PUT、一次批量确认。
     * 减少 API 调用次数，避免限流。
     *
     * @param requests       上传请求列表
     * @param apiConfig      API 配置
     * @param resourceApiKey 资源 API Key（ak_ 开头）
     * @return clientId -> 代理返回的临时 HTTPS URL
     */
    public Map<String, String> uploadMediaBatch(List<UploadRequest> requests,
                                                  ApiConfig apiConfig, String resourceApiKey) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        String baseUrl = support.resolveApiRoot(apiConfig);

        // 1. 读取所有本地文件
        List<LoadedFile> loadedFiles = new ArrayList<>();
        for (UploadRequest req : requests) {
            try {
                OpenAiCompatibleVideoProtocolSupport.BinaryResource resource =
                        support.loadBinaryResource(req.sourceUrl(), apiConfig);
                String mimeType = resolveMimeType(resource.mimeType(), req.sourceUrl(), req.kind());
                String filename = resolveFilename(req.sourceUrl(), req.clientId(), mimeType);
                loadedFiles.add(new LoadedFile(req.clientId(), req.kind(), filename, mimeType, resource.bytes()));
            } catch (IOException e) {
                throw new BusinessException("SuperToken 上传：读取本地媒体失败: " + e.getMessage());
            }
        }

        // 2. 批量创建上传会话
        JSONArray filesArr = new JSONArray();
        for (LoadedFile f : loadedFiles) {
            filesArr.add(JSONUtil.createObj()
                    .set("client_id", f.clientId())
                    .set("kind", f.kind())
                    .set("filename", f.filename())
                    .set("mime_type", f.mimeType())
                    .set("size_bytes", f.bytes().length));
        }
        JSONObject sessionBody = JSONUtil.createObj().set("files", filesArr);
        JSONArray sessions = createUploadSessionsBatch(baseUrl, resourceApiKey, sessionBody);

        // 3. 逐个 PUT 上传到对象存储
        List<String> uploadIds = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            String uploadId = session.getStr("id");
            String uploadUrl = session.getStr("upload_url");
            String method = StrUtil.blankToDefault(session.getStr("method"), "PUT");
            if (StrUtil.isBlank(uploadId) || StrUtil.isBlank(uploadUrl)) {
                throw new BusinessException("SuperToken 上传：创建会话失败，未返回 upload_id 或 upload_url");
            }
            LoadedFile f = loadedFiles.get(i);
            putFileToStorage(uploadUrl, method, f.mimeType(), f.bytes());
            uploadIds.add(uploadId);
        }

        // 4. 批量确认上传
        Map<String, String> result = confirmUploadBatch(baseUrl, resourceApiKey, uploadIds, loadedFiles);
        log.info("[SuperToken Upload] 批量上传成功: {} 个文件", result.size());
        return result;
    }

    public record UploadRequest(String sourceUrl, String kind, String clientId) {}

    private record LoadedFile(String clientId, String kind, String filename, String mimeType, byte[] bytes) {}

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 5000;

    private JSONArray createUploadSessionsBatch(String baseUrl, String resourceApiKey, JSONObject body) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/media/uploads")
                    .addHeader("Authorization", "Bearer " + resourceApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = uploadHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429 && attempt < MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                    log.warn("[SuperToken Upload] 创建上传会话被限流 (429), {}ms 后重试 (attempt={}/{})",
                            delay, attempt + 1, MAX_RETRIES);
                    Thread.sleep(delay);
                    continue;
                }
                if (!response.isSuccessful()) {
                    throw new BusinessException("SuperToken 创建上传会话失败: HTTP " + response.code() + " " + responseBody);
                }
                JSONObject root = JSONUtil.parseObj(responseBody);
                JSONArray data = root.getJSONArray("data");
                if (data == null || data.isEmpty()) {
                    throw new BusinessException("SuperToken 创建上传会话失败：响应无 data 数组");
                }
                return data;
            } catch (IOException e) {
                throw new BusinessException("SuperToken 创建上传会话异常: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("SuperToken 创建上传会话被中断: " + e.getMessage());
            }
        }
        throw new BusinessException("SuperToken 创建上传会话失败: 超过最大重试次数 " + MAX_RETRIES);
    }

    private void putFileToStorage(String uploadUrl, String method, String mimeType, byte[] bytes) {
        MediaType mediaType = MediaType.get(mimeType);
        RequestBody requestBody = RequestBody.create(bytes, mediaType);
        Request.Builder requestBuilder = new Request.Builder()
                .url(uploadUrl);

        // 根据代理返回的 method 使用 PUT 或 POST
        if ("POST".equalsIgnoreCase(method)) {
            requestBuilder.post(requestBody);
        } else {
            requestBuilder.put(requestBody);
        }
        requestBuilder.addHeader("Content-Type", mimeType);

        try (Response response = uploadHttpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new BusinessException("SuperToken 上传文件到对象存储失败: HTTP " + response.code() + " " + body);
            }
        } catch (IOException e) {
            throw new BusinessException("SuperToken 上传文件到对象存储异常: " + e.getMessage());
        }
    }

    private Map<String, String> confirmUploadBatch(String baseUrl, String resourceApiKey,
                                                     List<String> uploadIds, List<LoadedFile> loadedFiles) {
        JSONObject body = JSONUtil.createObj();
        JSONArray ids = new JSONArray();
        uploadIds.forEach(ids::add);
        body.set("upload_ids", ids);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/media/uploads/complete")
                    .addHeader("Authorization", "Bearer " + resourceApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = uploadHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429 && attempt < MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                    log.warn("[SuperToken Upload] 确认上传被限流 (429), {}ms 后重试 (attempt={}/{})",
                            delay, attempt + 1, MAX_RETRIES);
                    Thread.sleep(delay);
                    continue;
                }
                if (!response.isSuccessful()) {
                    throw new BusinessException("SuperToken 确认上传失败: HTTP " + response.code() + " " + responseBody);
                }
                JSONObject root = JSONUtil.parseObj(responseBody);
                JSONArray data = root.getJSONArray("data");
                if (data == null || data.isEmpty()) {
                    throw new BusinessException("SuperToken 确认上传失败：响应无 data 数组");
                }
                Map<String, String> result = new LinkedHashMap<>();
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    String cid = item.getStr("client_id");
                    String url = item.getStr("url");
                    if (StrUtil.isNotBlank(cid) && StrUtil.isNotBlank(url)) {
                        result.put(cid, url);
                    }
                }
                return result;
            } catch (IOException e) {
                throw new BusinessException("SuperToken 确认上传异常: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("SuperToken 确认上传被中断: " + e.getMessage());
            }
        }
        throw new BusinessException("SuperToken 确认上传失败: 超过最大重试次数 " + MAX_RETRIES);
    }

    private String resolveMimeType(String detectedMime, String sourceUrl, String kind) {
        String mimeType;
        if (StrUtil.isNotBlank(detectedMime) && !detectedMime.equals("application/octet-stream")) {
            mimeType = detectedMime;
        } else {
            String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4")) {
                mimeType = "video/mp4";
            } else if (lower.endsWith(".mp3")) {
                mimeType = "audio/mpeg";
            } else if (lower.endsWith(".wav")) {
                mimeType = "audio/wav";
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                mimeType = "image/jpeg";
            } else if (lower.endsWith(".webp")) {
                mimeType = "image/webp";
            } else if (lower.endsWith(".gif")) {
                mimeType = "image/gif";
            } else if (lower.endsWith(".m4a")) {
                mimeType = "audio/x-m4a";
            } else if (lower.endsWith(".aac")) {
                mimeType = "audio/aac";
            } else {
                mimeType = switch (kind) {
                    case "video" -> "video/mp4";
                    case "audio" -> "audio/mpeg";
                    default -> "image/png";
                };
            }
        }
        return normalizeMimeType(mimeType);
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return mimeType;
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "audio/mp3" -> "audio/mpeg";
            case "audio/wave" -> "audio/wav";
            case "audio/m4a" -> "audio/x-m4a";
            case "audio/ogg" -> "audio/mpeg";
            case "audio/flac" -> "audio/mpeg";
            default -> mimeType;
        };
    }

    private String resolveFilename(String sourceUrl, String clientId, String mimeType) {
        String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
        int lastSlash = lower.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < lower.length() - 1) {
            String name = lower.substring(lastSlash + 1);
            if (StrUtil.isNotBlank(name) && name.contains(".")) {
                return name;
            }
        }
        String ext = switch (mimeType) {
            case "video/mp4" -> "mp4";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
        return clientId + "." + ext;
    }
}
