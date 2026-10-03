package com.tonyhe.fusion.service.ai.dashscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.storage.StorageConfig;
import com.tonyhe.fusion.enums.ai.AiModelTypeEnum;
import com.tonyhe.fusion.service.storage.StorageConfigService;
import com.tonyhe.fusion.service.system.PresetArtStyleResourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * DashScope / 阿里云百炼生成类接口公共工具。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashScopeGenerationSupport {

    public static final String PLATFORM = "dashscope";
    public static final String DEFAULT_ROOT_URL = "https://dashscope.aliyuncs.com";
    public static final String DEFAULT_GENERATION_BASE_URL = DEFAULT_ROOT_URL + "/api/v1";
    public static final String DEFAULT_COMPATIBLE_BASE_URL = DEFAULT_ROOT_URL + "/compatible-mode/v1";

    private static final String DEFAULT_LOCAL_MEDIA_BASE_PATH = "./data/media";

    private final StorageConfigService storageConfigService;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;

    private final OkHttpClient downloadHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static String resolveCompatibleBaseUrl(String baseUrl) {
        String root = normalizeRootUrl(baseUrl);
        return ensureSuffix(root, "/compatible-mode/v1");
    }

    public static String resolveRootBaseUrl(String baseUrl) {
        return normalizeRootUrl(baseUrl);
    }

    public static String resolveGenerationBaseUrl(String baseUrl) {
        String root = normalizeRootUrl(baseUrl);
        return ensureSuffix(root, "/api/v1");
    }

    public static String resolveGenerationBaseUrl(ApiConfig apiConfig) {
        return resolveGenerationBaseUrl(apiConfig != null ? apiConfig.getApiUrl() : null);
    }

    public static void requireApiKey(ApiConfig apiConfig) {
        if (apiConfig == null || StrUtil.isBlank(apiConfig.getApiKey())) {
            throw new BusinessException("DashScope 模型缺少 API Key 配置");
        }
    }

    public static JSONObject parseModelConfig(AiModel model) {
        if (model == null || StrUtil.isBlank(model.getConfig())) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(model.getConfig());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static List<String> parseJsonUrls(String jsonUrls) {
        if (StrUtil.isBlank(jsonUrls)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(jsonUrls);
            return array.toList(String.class).stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static Integer inferRemoteModelType(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return null;
        }
        String code = modelCode.trim().toLowerCase(Locale.ROOT);
        if (code.startsWith("qwen-image")
                || code.startsWith("wan2.") && code.contains("image")
                || code.startsWith("wanx") && code.contains("t2i")
                || code.startsWith("wan") && code.contains("t2i")
                || code.startsWith("z-image")) {
            return AiModelTypeEnum.IMAGE.getType();
        }
        if (code.startsWith("wan") && (code.contains("t2v") || code.contains("i2v")
                || code.contains("kf2v") || code.contains("r2v") || code.contains("videoedit")
                || code.contains("vace") || code.contains("animate"))
                || code.startsWith("pixverse/")
                || code.startsWith("kling/")
                || code.startsWith("vidu/")) {
            return AiModelTypeEnum.VIDEO.getType();
        }
        if (code.contains("tts") || code.contains("cosyvoice") || code.contains("sambert")
                || code.contains("speech") || code.contains("voice")) {
            return AiModelTypeEnum.TTS.getType();
        }
        if (code.contains("asr") || code.contains("sensevoice") || code.contains("paraformer")) {
            return AiModelTypeEnum.STT.getType();
        }
        return AiModelTypeEnum.CHAT.getType();
    }

    public static boolean getBoolean(JSONObject config, String key, boolean defaultValue) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value != null) {
            String text = value.toString().trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return defaultValue;
    }

    public static Integer getInteger(JSONObject config, String key) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return null;
        }
        try {
            return config.getInt(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Long getLong(JSONObject config, String key) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return null;
        }
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String getString(JSONObject config, String key) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return null;
        }
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static String normalizeResolutionLabel(String resolution) {
        if (StrUtil.isBlank(resolution)) {
            return null;
        }
        String text = resolution.trim();
        if (text.matches("(?i)^\\d{3,4}p$")) {
            return text.substring(0, text.length() - 1) + "P";
        }
        return text;
    }

    public String toMediaUrl(String sourceUrl) throws IOException {
        if (StrUtil.isBlank(sourceUrl)) {
            throw new BusinessException("DashScope 参考媒体地址为空");
        }
        String trimmed = sourceUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return trimmed;
        }
        if (presetArtStyleResourceResolver.isPresetArtStylePath(trimmed)) {
            PresetArtStyleResourceResolver.PresetArtStyleResource presetResource = presetArtStyleResourceResolver.load(trimmed);
            return "data:" + presetResource.mimeType() + ";base64," + Base64.getEncoder().encodeToString(presetResource.bytes());
        }

        BinaryResource resource;
        if (trimmed.startsWith("/media/")) {
            resource = loadLocalMedia(trimmed);
        } else if (trimmed.startsWith("file:")) {
            resource = loadFile(Paths.get(URI.create(trimmed)));
        } else {
            Path path = Paths.get(trimmed);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return trimmed;
            }
            resource = loadFile(path);
        }
        return "data:" + resource.mimeType() + ";base64," + Base64.getEncoder().encodeToString(resource.bytes());
    }

    private BinaryResource loadLocalMedia(String sourceUrl) throws IOException {
        String relativePath = sourceUrl.replaceFirst("^/media/?", "");
        List<Path> candidates = new ArrayList<>();
        StorageConfig config = storageConfigService.getDefaultConfig();
        if (config != null && StrUtil.isNotBlank(config.getBasePath())) {
            candidates.add(Paths.get(config.getBasePath()).resolve(relativePath));
        }
        candidates.add(Paths.get(DEFAULT_LOCAL_MEDIA_BASE_PATH).resolve(relativePath));

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return loadFile(candidate);
            }
        }
        throw new BusinessException("本地参考媒体不存在: " + sourceUrl);
    }

    private BinaryResource loadFile(Path path) throws IOException {
        return new BinaryResource(Files.readAllBytes(path), normalizeMimeType(null, path.getFileName().toString()));
    }

    public BinaryResource download(String sourceUrl) throws IOException {
        Request request = new Request.Builder()
                .url(sourceUrl)
                .get()
                .addHeader("Accept", "image/*,video/*,audio/*,*/*;q=0.8")
                .build();
        try (Response response = downloadHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException("下载参考媒体失败: HTTP " + response.code() + " url=" + sourceUrl);
            }
            return new BinaryResource(response.body().bytes(), normalizeMimeType(response.header("Content-Type"), sourceUrl));
        }
    }

    public static String normalizeMimeType(String contentType, String sourceUrl) {
        if (StrUtil.isNotBlank(contentType)) {
            return contentType.split(";", 2)[0].trim();
        }
        String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        return "image/png";
    }

    private static String normalizeRootUrl(String baseUrl) {
        String normalized = StrUtil.isBlank(baseUrl) ? DEFAULT_ROOT_URL : baseUrl.trim().replaceAll("/+$", "");
        normalized = normalized.replaceAll("(?i)/compatible-mode/v1$", "");
        normalized = normalized.replaceAll("(?i)/api/v1$", "");
        return normalized;
    }

    private static String ensureSuffix(String root, String suffix) {
        String normalizedRoot = StrUtil.blankToDefault(root, DEFAULT_ROOT_URL).replaceAll("/+$", "");
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        return normalizedRoot + normalizedSuffix;
    }

    /**
     * 将音频 URL 转换为 DashScope 可接受的格式。
     * <p>
     * DashScope 视频 API 的 reference_voice 字段不支持 base64 data URL，
     * 仅支持公网 URL（HTTP/HTTPS）或 OSS 临时 URL（oss://）。
     * 对于本地 /media/ 路径的音频，上传到 DashScope 临时存储空间获取 oss:// URL。
     *
     * @param sourceUrl 音频源 URL 或本地路径
     * @param apiKey    DashScope API Key
     * @param modelCode 视频模型 code（用于上传凭证申请）
     * @return DashScope 可接受的音频 URL（公网 URL 或 oss:// URL）
     */
    public String toAudioUrl(String sourceUrl, String apiKey, String modelCode) throws IOException {
        if (StrUtil.isBlank(sourceUrl)) {
            throw new BusinessException("DashScope 参考音频地址为空");
        }
        String trimmed = sourceUrl.trim();
        // 公网 URL 直接使用
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        // oss:// URL 直接使用
        if (trimmed.startsWith("oss://")) {
            return trimmed;
        }
        // data: URL 不支持，抛出明确错误
        if (trimmed.startsWith("data:")) {
            throw new BusinessException("DashScope reference_voice 不支持 base64 data URL，请配置公网可访问的音频 URL 或使用 S3 存储");
        }
        // 本地路径 → 上传到 DashScope 临时存储
        BinaryResource resource;
        if (trimmed.startsWith("/media/")) {
            resource = loadLocalMedia(trimmed);
        } else if (trimmed.startsWith("file:")) {
            resource = loadFile(Paths.get(URI.create(trimmed)));
        } else {
            Path path = Paths.get(trimmed);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                throw new BusinessException("DashScope 参考音频文件不存在: " + trimmed);
            }
            resource = loadFile(path);
        }
        return uploadToDashScopeOss(resource, apiKey, modelCode);
    }

    /**
     * 上传文件到 DashScope 临时存储空间，返回 oss:// URL（48 小时有效）。
     */
    private String uploadToDashScopeOss(BinaryResource resource, String apiKey, String modelCode) throws IOException {
        String modelName = StrUtil.isNotBlank(modelCode) ? modelCode : "wan2.7-r2v";

        // 步骤1: 获取上传凭证
        String uploadPolicyUrl = DEFAULT_ROOT_URL + "/api/v1/uploads?action=getPolicy&model=" + modelName;
        Request policyRequest = new Request.Builder()
                .url(uploadPolicyUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .get()
                .build();

        String policyData;
        String signature;
        String uploadDir;
        String uploadHost;
        String ossAccessKeyId;
        String xOssObjectAcl;
        String xOssForbidOverwrite;

        try (Response response = downloadHttpClient.newCall(policyRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException("获取 DashScope 上传凭证失败: HTTP " + response.code());
            }
            JSONObject body = JSONUtil.parseObj(response.body().string());
            JSONObject data = body.getJSONObject("data");
            if (data == null) {
                throw new BusinessException("DashScope 上传凭证响应缺少 data 字段: " + body);
            }
            policyData = data.getStr("policy");
            signature = data.getStr("signature");
            uploadDir = data.getStr("upload_dir");
            uploadHost = data.getStr("upload_host");
            ossAccessKeyId = data.getStr("oss_access_key_id");
            xOssObjectAcl = data.getStr("x_oss_object_acl");
            xOssForbidOverwrite = data.getStr("x_oss_forbid_overwrite");
        }

        // 步骤2: 上传文件到 OSS
        String fileName = "audio_" + cn.hutool.core.util.IdUtil.fastSimpleUUID() + ".mp3";
        String key = uploadDir + "/" + fileName;

        RequestBody fileBody = RequestBody.create(resource.bytes(), MediaType.parse(resource.mimeType()));
        MultipartBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("OSSAccessKeyId", ossAccessKeyId)
                .addFormDataPart("Signature", signature)
                .addFormDataPart("policy", policyData)
                .addFormDataPart("x-oss-object-acl", xOssObjectAcl)
                .addFormDataPart("x-oss-forbid-overwrite", xOssForbidOverwrite)
                .addFormDataPart("key", key)
                .addFormDataPart("success_action_status", "200")
                .addFormDataPart("file", fileName, fileBody)
                .build();

        Request uploadRequest = new Request.Builder()
                .url(uploadHost)
                .post(multipartBody)
                .build();

        try (Response response = downloadHttpClient.newCall(uploadRequest).execute()) {
            if (!response.isSuccessful()) {
                String respBody = response.body() != null ? response.body().string() : "";
                throw new BusinessException("上传音频到 DashScope OSS 失败: HTTP " + response.code() + " " + respBody.substring(0, Math.min(respBody.length(), 200)));
            }
        }

        String ossUrl = "oss://" + key;
        log.info("[DashScope] 音频已上传到临时存储: {} → {}", fileName, ossUrl);
        return ossUrl;
    }

    public record BinaryResource(byte[] bytes, String mimeType) {
    }
}
