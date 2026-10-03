package com.tonyhe.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.storage.StorageConfig;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.strategy.ImageGenerationStrategy;
import com.tonyhe.fusion.service.storage.StorageConfigService;
import com.tonyhe.fusion.service.system.PresetArtStyleResourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 火山引擎（方舟）图片生成策略
 * <p>
 * 支持 Seedream 系列模型（5.0/4.0/3.0 等）文生图。
 * 通过 volcengine-java-sdk-ark-runtime 的 ArkService 调用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VolcengineImageStrategy implements ImageGenerationStrategy {

    private final ImageGenerationService imageGenerationService;
    private final AiModelService aiModelService;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;
    private final StorageConfigService storageConfigService;

    @Override
    public String getName() {
        return "volcengine";
    }

    @Override
    public List<String> generate(String prompt, String modelCode, int width, int height, int count,
            List<String> imageUrls, ApiConfig apiConfig) {
        return generate(prompt, null, modelCode, width, height, count, imageUrls, apiConfig);
    }

    private List<String> generate(String prompt, String negativePrompt, String modelCode, int width, int height,
            int count, List<String> imageUrls, ApiConfig apiConfig) {
        String resolvedBaseUrl;
        if (StrUtil.isNotBlank(apiConfig.getApiUrl())) {
            resolvedBaseUrl = apiConfig.getApiUrl();
        } else if ("volcengine_plan".equalsIgnoreCase(apiConfig.getPlatform())) {
            resolvedBaseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
        } else {
            resolvedBaseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        }
        String requestUrl = resolvedBaseUrl + "/images/generations";

        log.info("[Volcengine] 调用文生图 API: url={}, model={}, size={}x{}, hasRefImages={}, hasNegativePrompt={}",
                requestUrl, modelCode, width, height, imageUrls != null && !imageUrls.isEmpty(),
                StrUtil.isNotBlank(negativePrompt));

        JSONObject requestBody = JSONUtil.createObj()
                .set("model", modelCode)
                .set("prompt", prompt)
                .set("watermark", false)
                .set("size", width + "x" + height);

        if (StrUtil.isNotBlank(negativePrompt)) {
            requestBody.set("negative_prompt", negativePrompt);
        }

        if (imageUrls != null && !imageUrls.isEmpty()) {
            // 将本地相对路径图片转换为 base64 data URI，解决无公网地址时 API 无法访问的问题
            List<String> resolvedImageUrls = new ArrayList<>();
            for (String url : imageUrls) {
                String converted = convertLocalImageToDataUri(url);
                resolvedImageUrls.add(converted);
            }
            requestBody.set("image", resolvedImageUrls);
        }

        try (HttpResponse response = HttpRequest.post(requestUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiConfig.getApiKey())
                .body(requestBody.toString())
                .timeout(120_000)
                .execute()) {

            String respBody = response.body();
            if (!response.isOk()) {
                log.error("[Volcengine] API 调用失败: status={}, body={}", response.getStatus(), respBody);
                JSONObject errObj = JSONUtil.parseObj(respBody);
                String errMsg = errObj.getByPath("error.message", String.class);
                if (StrUtil.isBlank(errMsg)) errMsg = respBody;
                throw new RuntimeException("火山引擎图片生成失败: " + errMsg);
            }

            JSONObject respJson = JSONUtil.parseObj(respBody);
            JSONArray data = respJson.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                throw new RuntimeException("火山引擎返回空结果");
            }

            List<String> urls = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject img = data.getJSONObject(i);
                urls.add(img.getStr("url"));
            }
            return urls;
        }
    }

    private static final int DEFAULT_MAX_PIXELS = 4624220;

    @Override
    public String submit(ImageTask task, ApiConfig apiConfig) {
        AiModel model = resolveModel(task);
        String modelCode = (model != null && StrUtil.isNotBlank(model.getCode())) ? model.getCode()
                : "doubao-seedream-3-0-t2i-250415";
        int[] size = resolveDefaultSize(model, task);
        int count = (task.getCount() != null && task.getCount() > 0) ? task.getCount() : 1;

        // 火山 Seedream 系列限制图片面积上限，从 model config 读取 maxPixels，默认 4624220
        int maxPixels = DEFAULT_MAX_PIXELS;
        if (model != null && StrUtil.isNotBlank(model.getConfig())) {
            try {
                JSONObject cfg = JSONUtil.parseObj(model.getConfig());
                Integer configured = cfg.getInt("maxPixels");
                if (configured != null && configured > 0) {
                    maxPixels = configured;
                }
            } catch (Exception ignored) {
            }
        }
        if (size[0] * size[1] > maxPixels) {
            double scale = Math.sqrt((double) maxPixels / (size[0] * size[1]));
            int newW = (int) Math.floor(size[0] * scale);
            int newH = (int) Math.floor(size[1] * scale);
            log.warn("[Volcengine] 模型 {} 分辨率 {}x{} 超过 maxPixels={} 限制，降级为 {}x{}",
                    modelCode, size[0], size[1], maxPixels, newW, newH);
            size[0] = newW;
            size[1] = newH;
        }

        // 解析参考图（图生图场景）
        List<String> imageUrls = parseRefImageUrls(task.getRefImageUrls());

        // 复用纯 API 调用
        List<String> urls = generate(task.getPrompt(), task.getNegativePrompt(), modelCode, size[0], size[1], count, imageUrls, apiConfig);

        // 更新数据库记录
        List<ImageItem> items = imageGenerationService.listItems(task.getId());
        for (int i = 0; i < urls.size() && i < items.size(); i++) {
            ImageItem item = items.get(i);
            item.setImageUrl(urls.get(i));
            item.setStatus(1);
            imageGenerationService.updateItem(item);
        }

        task.setSuccessCount(Math.min(urls.size(), items.size()));
        imageGenerationService.update(task);

        log.info("[Volcengine] 文生图完成: taskId={}, imageCount={}", task.getTaskId(), urls.size());
        return task.getTaskId();
    }

    @Override
    public void poll(String platformTaskId, ImageTask task, ApiConfig apiConfig) {
        // 火山引擎 createImages 是同步 API，submit 中已处理完成，无需轮询
    }

    private AiModel resolveModel(ImageTask task) {
        if (task.getModelId() != null) {
            AiModel model = aiModelService.getById(task.getModelId());
            if (model != null && StrUtil.isNotBlank(model.getCode())) {
                return model;
            }
        }
        return null;
    }

    /**
     * 将本地图片路径转换为 base64 data URI。
     * 支持：
     * - 预设画风图（/api/art-styles/** 或 /art-styles/**）→ 从 classpath 读取
     * - 本地存储图片（/media/**）→ 从磁盘读取
     * - 已是完整 URL（http/https）→ 直接返回
     */
    private String convertLocalImageToDataUri(String url) {
        if (StrUtil.isBlank(url)) {
            return url;
        }
        // 已是完整公网 URL，直接返回
        // 但 localhost / 127.0.0.1 的 URL 外部 API 无法访问，需要提取路径转 base64
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.contains("://localhost") || url.contains("://127.0.0.1")) {
                // 提取 path 部分，复用本地文件读取逻辑
                String path = extractPathFromUrl(url);
                if (path != null) {
                    log.info("[Volcengine] 检测到 localhost URL，转为本地路径处理: url={} -> path={}", url, path);
                    return convertLocalImageToDataUri(path);
                }
            }
            return url;
        }
        try {
            byte[] imageBytes;
            String mimeType;

            // 预设画风图：从 classpath 读取
            if (presetArtStyleResourceResolver.isPresetArtStylePath(url)) {
                PresetArtStyleResourceResolver.PresetArtStyleResource resource =
                        presetArtStyleResourceResolver.load(url);
                imageBytes = resource.bytes();
                mimeType = resource.mimeType();
            }
            // 本地存储图片：从磁盘读取
            else if (url.startsWith("/media/")) {
                String basePath = resolveLocalStorageBasePath();
                String relativePath = url.substring("/media/".length());
                Path filePath = Paths.get(basePath, relativePath);
                imageBytes = Files.readAllBytes(filePath);
                mimeType = guessMimeType(url);
            } else {
                // 未知格式，直接返回原值（让 API 报错时能看到原始 URL）
                log.warn("[Volcengine] 无法识别的图片路径格式，直接传给 API: url={}", url);
                return url;
            }

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + mimeType + ";base64," + base64;
            log.info("[Volcengine] 本地图片转 base64: url={}, size={}bytes, type={}",
                    url, imageBytes.length, mimeType);
            return dataUri;
        } catch (Exception e) {
            log.warn("[Volcengine] 本地图片转 base64 失败，回退原始 URL: url={}", url, e);
            return url;
        }
    }

    private String resolveLocalStorageBasePath() {
        try {
            StorageConfig config = storageConfigService.getDefaultConfig();
            if (config != null && StrUtil.isNotBlank(config.getBasePath())) {
                return config.getBasePath();
            }
        } catch (Exception ignored) {
        }
        return "./data/media";
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    /**
     * 从完整 URL 中提取 path 部分。
     * 如 http://localhost:18080/api/art-styles/cg1.png → /api/art-styles/cg1.png
     */
    private String extractPathFromUrl(String url) {
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return null;
            int pathStart = url.indexOf("/", schemeEnd + 3);
            if (pathStart < 0) return null;
            String path = url.substring(pathStart);
            // 去掉 query string
            int queryStart = path.indexOf("?");
            if (queryStart >= 0) {
                path = path.substring(0, queryStart);
            }
            return path;
        } catch (Exception e) {
            return null;
        }
    }
}
