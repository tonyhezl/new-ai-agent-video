package com.tonyhe.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.storage.StorageConfig;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ModelPresetService;
import com.tonyhe.fusion.service.ai.proxy.AiProxySupport;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.strategy.ImageGenerationStrategy;
import com.tonyhe.fusion.service.storage.MediaStorageService;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容图片生成策略
 * <p>
 * 仅使用官方 Images API，支持文生图（/images/generations）和参考图编辑（/images/edits）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiImageStrategy implements ImageGenerationStrategy {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_IMAGE_MODEL = "gpt-image-1";
    private static final String DEFAULT_LOCAL_MEDIA_BASE_PATH = "./data/media";
    private static final int DEFAULT_ASYNC_TASK_INITIAL_DELAY_SECONDS = 10;
    private static final int DEFAULT_ASYNC_TASK_POLL_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_ASYNC_TASK_TIMEOUT_SECONDS = 3600;
    private static final int RESPONSE_PREVIEW_LENGTH = 240;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final byte[] PLACEHOLDER_PNG_BYTES = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==");
    private static final java.util.regex.Pattern CHAT_COMPLETIONS_IMAGE_PATTERN =
            java.util.regex.Pattern.compile("data:image/(\\w+);base64,([A-Za-z0-9+/=\\n]+)");

    private final ImageGenerationService imageGenerationService;
    private final AiModelService aiModelService;
    private final ModelPresetService modelPresetService;
    private final MediaStorageService mediaStorageService;
    private final StorageConfigService storageConfigService;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(1200, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "openai_compatible";
    }

    @Override
    public List<String> generate(String prompt, String modelCode, int width, int height, int count,
                                  List<String> imageUrls, ApiConfig apiConfig) {
        if (apiConfig == null || StrUtil.isBlank(apiConfig.getApiKey())) {
            throw new BusinessException("OpenAI 图片模型缺少 apiKey 配置");
        }

        String actualModelCode = StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL);
        JSONObject modelConfig = resolveModelConfig(actualModelCode, null);
        return generateInternal(prompt, actualModelCode, width, height, count, imageUrls, apiConfig, modelConfig, "images");
    }

    @Override
    public List<String> generate(String prompt, String modelCode, int width, int height, int count,
                                  List<String> imageUrls, ApiConfig apiConfig, AiModel model) {
        return generate(prompt, modelCode, width, height, count, imageUrls, apiConfig, model, "images");
    }

    @Override
    public List<String> generate(String prompt, String modelCode, int width, int height, int count,
                                  List<String> imageUrls, ApiConfig apiConfig, AiModel model,
                                  String storagePath) {
        if (apiConfig == null || StrUtil.isBlank(apiConfig.getApiKey())) {
            throw new BusinessException("OpenAI 图片模型缺少 apiKey 配置");
        }

        String actualModelCode = StrUtil.blankToDefault(modelCode,
                model != null && StrUtil.isNotBlank(model.getCode()) ? model.getCode() : DEFAULT_IMAGE_MODEL);
        JSONObject modelConfig = resolveModelConfig(actualModelCode, model);
        return generateInternal(prompt, actualModelCode, width, height, count, imageUrls, apiConfig, modelConfig,
                StrUtil.blankToDefault(storagePath, "images"));
    }

    private List<String> generateInternal(String prompt, String modelCode, int width, int height, int count,
                                          List<String> imageUrls,
                                          ApiConfig apiConfig,
                                          JSONObject modelConfig,
                                          String storagePath) {
        String actualModelCode = StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL);
        // Gemini 等模型通过 /v1/chat/completions 接口生成图片
        if (Boolean.TRUE.equals(getBoolean(modelConfig, "useChatCompletions"))) {
            return generateViaChatCompletions(prompt, actualModelCode, width, height, count, imageUrls,
                    apiConfig, modelConfig, storagePath);
        }
        if (isAsyncMode(modelConfig)) {
            return generateViaAsyncGenerations(prompt, actualModelCode, width, height, count, imageUrls,
                    apiConfig, modelConfig);
        }
        if (imageUrls != null && !imageUrls.isEmpty()) {
            // Grok Imagine 等模型通过 /images/generations + image_urls 字段支持多图参考
            // 其他模型默认走 /images/edits multipart 接口
            if (preferGenerationsWithRefImages(actualModelCode, modelConfig)) {
                return generateViaGenerations(prompt, actualModelCode, width, height, count, imageUrls, apiConfig, modelConfig);
            }
            return generateViaEdits(prompt, actualModelCode, width, height, count, imageUrls, apiConfig, modelConfig);
        }

        return generateViaGenerations(prompt, actualModelCode, width, height, count, null, apiConfig, modelConfig);
    }

    /**
     * 判断是否应使用 /images/generations + image_urls 方式传参考图（而非 /images/edits）
     * Grok Imagine 等模型原生支持 generations 接口的 image_urls 字段
     */
    private boolean preferGenerationsWithRefImages(String modelCode, JSONObject modelConfig) {
        if (modelConfig != null && modelConfig.getBool("useGenerationsWithRefImages", false)) {
            return true;
        }
        String code = modelCode.toLowerCase(Locale.ROOT);
        return code.startsWith("grok-imagine");
    }

    private List<String> generateViaGenerations(String prompt, String modelCode, int width, int height, int count,
                                                List<String> imageUrls,
                                                ApiConfig apiConfig, JSONObject modelConfig) {
        String requestUrl = resolveImagesGenerateUrl(apiConfig);
        String requestBody = buildGenerationRequestBody(prompt, modelCode, width, height, count, imageUrls, modelConfig);

        log.info("[OpenAI] 调用文生图 API: model={}, prompt={}, size={}x{}, url={}",
                modelCode, prompt, width, height, requestUrl);

        Request request = new Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI 图片生成失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            return parseImageUrls(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * Gemini 等模型通过 /v1/chat/completions 接口生成图片。
     * SuperToken Gemini 图片生成使用 OpenAI 聊天格式，图片以 base64 嵌入在 content 字符串中返回。
     */
    private List<String> generateViaChatCompletions(String prompt, String modelCode, int width, int height, int count,
                                                     List<String> imageUrls,
                                                     ApiConfig apiConfig, JSONObject modelConfig,
                                                     String storagePath) {
        String requestUrl = resolveChatCompletionsUrl(apiConfig);
        String requestBody = buildChatCompletionsRequestBody(prompt, modelCode, width, height, count, imageUrls, modelConfig);

        log.info("[OpenAI] 调用 ChatCompletions 图片生成: model={}, prompt={}, size={}x{}, url={}",
                modelCode, prompt, width, height, requestUrl);

        Request request = new Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI 图片生成失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            return parseChatCompletionsImageResponse(responseBody, storagePath);
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 /v1/chat/completions 图片生成响应。
     * 图片以 Markdown 图片语法嵌入在 choices[0].message.content 字符串中：
     * ![image](data:image/jpeg;base64,<base64字符串>)
     */
    private List<String> parseChatCompletionsImageResponse(String responseBody, String storagePath) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String errorMessage = extractErrorMessage(root);
            if (StrUtil.isNotBlank(errorMessage)) {
                throw new RuntimeException("OpenAI 图片生成失败: " + errorMessage);
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("OpenAI ChatCompletions 返回无 choices");
            }

            List<String> urls = new ArrayList<>();
            for (JsonNode choice : choices) {
                String content = choice.path("message").path("content").asText("");
                if (StrUtil.isBlank(content)) {
                    continue;
                }
                // 提取 data:image/xxx;base64,... 格式的 base64 数据
                java.util.regex.Matcher matcher = CHAT_COMPLETIONS_IMAGE_PATTERN.matcher(content);
                if (matcher.find()) {
                    String mimeType = matcher.group(1);
                    String b64Data = matcher.group(2).replace("\n", "").trim();
                    // 补齐 base64 padding
                    b64Data += "=".repeat((-b64Data.length() % 4 + 4) % 4);
                    String extension = mimeType.contains("png") ? "png" : "jpg";
                    urls.add(storeBase64Image(b64Data, extension, storagePath));
                }
            }
            if (urls.isEmpty()) {
                throw new RuntimeException("OpenAI ChatCompletions 响应中未找到图片数据: "
                        + StrUtil.sub(responseBody, 0, 200));
            }
            return urls;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("OpenAI 响应解析失败: " + e.getMessage(), e);
        }
    }

    private String buildChatCompletionsRequestBody(String prompt, String modelCode, int width, int height, int count,
                                                    List<String> imageUrls,
                                                    JSONObject modelConfig) {
        try {
            var root = OBJECT_MAPPER.createObjectNode();
            root.put("model", StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL));
            root.put("max_tokens", 4096);
            root.put("temperature", 0.8);
            root.put("stream", false);

            // 构建 messages
            var messages = root.putArray("messages");
            var userMsg = messages.addObject();
            userMsg.put("role", "user");
            var content = userMsg.putArray("content");

            // 如果有参考图，先添加 image_url 类型
            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (String imageUrl : imageUrls) {
                    if (StrUtil.isNotBlank(imageUrl)) {
                        var imagePart = content.addObject();
                        imagePart.put("type", "image_url");
                        var imageUrlObj = imagePart.putObject("image_url");
                        imageUrlObj.put("url", imageUrl.trim());
                    }
                }
            }

            // 添加文本提示词
            var textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            // imageConfig 用于控制图片比例与分辨率（SuperToken OpenAI 聊天格式直接放在请求体顶层）
            String[] ar = resolveAspectRatioAndResolution(width, height, modelConfig);
            var imageConfig = root.putObject("imageConfig");
            imageConfig.put("aspectRatio", ar[0]);
            imageConfig.put("imageSize", ar[1]);

            log.info("[OpenAI] ChatCompletions imageConfig: aspectRatio={}, imageSize={}, pixelSize={}x{}",
                    ar[0], ar[1], width, height);

            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("构建 ChatCompletions 请求体失败: " + e.getMessage(), e);
        }
    }

    private String resolveChatCompletionsUrl(ApiConfig apiConfig) {
        String baseUrl = normalizeBaseUrl(StrUtil.blankToDefault(apiConfig != null ? apiConfig.getApiUrl() : null,
                DEFAULT_BASE_URL));
        if (endsWithIgnoreCase(baseUrl, "/chat/completions")) {
            return baseUrl;
        }
        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        if (endsWithIgnoreCase(baseUrl, "/v1")) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + (appendV1 ? "/v1/chat/completions" : "/chat/completions");
    }

    private List<String> generateViaAsyncGenerations(String prompt, String modelCode, int width, int height, int count,
                                                     List<String> imageUrls,
                                                     ApiConfig apiConfig,
                                                     JSONObject modelConfig) {
        String platformTaskId = submitAsyncGeneration(prompt, modelCode, width, height, count, imageUrls,
                apiConfig, modelConfig);
        if (platformTaskId.startsWith("sync:")) {
            return parseImageUrls(platformTaskId.substring(5));
        }
        return waitForAsyncTask(platformTaskId, apiConfig, modelConfig);
    }

    private String submitAsyncGeneration(String prompt, String modelCode, int width, int height, int count,
                                         List<String> imageUrls,
                                         ApiConfig apiConfig,
                                         JSONObject modelConfig) {
        String requestUrl = resolveAsyncSubmitUrl(apiConfig, modelConfig);
        RequestBody requestBody = buildAsyncTaskRequestBody(prompt, modelCode, width, height, count,
                imageUrls, apiConfig, modelConfig);

        log.info("[OpenAI] 调用异步生图提交 API: model={}, prompt={}, size={}x{}, url={}",
                modelCode, prompt, width, height, requestUrl);

        Request request = new Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .post(requestBody)
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI 异步图片任务提交失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            return parseAsyncTaskId(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 异步图片任务提交异常: " + e.getMessage(), e);
        }
    }

    private List<String> waitForAsyncTask(String platformTaskId, ApiConfig apiConfig, JSONObject modelConfig) {
        sleepQuietly(resolveAsyncInitialDelayMillis(modelConfig));

        long pollIntervalMillis = resolveAsyncPollIntervalMillis(modelConfig);
        long deadline = System.currentTimeMillis() + resolveAsyncTimeoutMillis(modelConfig);

        while (System.currentTimeMillis() <= deadline) {
            AsyncTaskResult result = fetchAsyncTaskResult(platformTaskId, apiConfig, modelConfig);
            if (result.completed()) {
                if (result.urls().isEmpty()) {
                    throw new RuntimeException("OpenAI 异步图片任务已完成但未返回图片 URL");
                }
                return result.urls();
            }
            if (result.failed()) {
                throw new RuntimeException("OpenAI 异步图片任务失败: "
                        + StrUtil.blankToDefault(result.errorMessage(), "未知错误"));
            }
            sleepQuietly(pollIntervalMillis);
        }

        throw new RuntimeException("OpenAI 异步图片任务轮询超时: taskId=" + platformTaskId);
    }

    private AsyncTaskResult fetchAsyncTaskResult(String platformTaskId, ApiConfig apiConfig, JSONObject modelConfig) {
        String requestUrl = resolveAsyncTaskUrl(apiConfig, modelConfig, platformTaskId);
        String queryApiKey = resolveResourceApiKey(apiConfig, modelConfig);
        Request request = new Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer " + queryApiKey)
                .get()
                .build();

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI 异步图片任务查询失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            return parseAsyncTaskResult(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 异步图片任务查询异常: " + e.getMessage(), e);
        }
    }

    @Override
    public String submit(ImageTask task, ApiConfig apiConfig) {
        AiModel model = resolveModel(task);
        String modelCode = (model != null && StrUtil.isNotBlank(model.getCode())) ? model.getCode() : DEFAULT_IMAGE_MODEL;
        JSONObject modelConfig = resolveModelConfig(modelCode, model);
        int[] size = resolveConfiguredSize(task, modelConfig);
        int count = (task.getCount() != null && task.getCount() > 0) ? task.getCount() : 1;

        // 解析参考图（图生图场景）
        List<String> imageUrls = parseRefImageUrls(task.getRefImageUrls());

        // task 级 negativePrompt 优先于 model config
        if (StrUtil.isNotBlank(task.getNegativePrompt())) {
            modelConfig.set("negativePrompt", task.getNegativePrompt());
        }

        if (isAsyncMode(modelConfig)) {
            try {
                String platformTaskId = submitAsyncGeneration(task.getPrompt(), modelCode, size[0], size[1], count,
                        imageUrls, apiConfig, modelConfig);
                // 同步回退标记：submitAsyncGeneration 检测到 API 返回了同步结果而非 task_id
                if (platformTaskId.startsWith("sync:")) {
                    List<String> urls = parseImageUrls(platformTaskId.substring(5));
                    updateImageTaskResults(task, urls);
                    log.info("[OpenAI] 文生图完成(异步配置同步回退): taskId={}, imageCount={}", task.getTaskId(), urls.size());
                    return null; // 同步已完成，返回 null 让 poll() 跳过
                }
                log.info("[OpenAI] 异步生图已提交: taskId={}, platformTaskId={}", task.getTaskId(), platformTaskId);
                return platformTaskId;
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("未找到 task_id")) {
                    log.warn("[OpenAI] 模型 {} 配置了异步模式但返回了同步结果，回退到同步处理", modelCode);
                    List<String> urls = generateInternal(task.getPrompt(), modelCode, size[0], size[1], count, imageUrls,
                            apiConfig, modelConfig, "images");
                    updateImageTaskResults(task, urls);
                    log.info("[OpenAI] 文生图完成(异步配置同步回退): taskId={}, imageCount={}", task.getTaskId(), urls.size());
                    return null; // 同步已完成，返回 null 让 poll() 跳过
                }
                throw e;
            }
        }

        List<String> urls = generateInternal(task.getPrompt(), modelCode, size[0], size[1], count, imageUrls,
            apiConfig, modelConfig, "images");

        updateImageTaskResults(task, urls);

        log.info("[OpenAI] 文生图完成: taskId={}, imageCount={}", task.getTaskId(), urls.size());
        return task.getTaskId();
    }

    @Override
    public void poll(String platformTaskId, ImageTask task, ApiConfig apiConfig) {
        AiModel model = resolveModel(task);
        String modelCode = (model != null && StrUtil.isNotBlank(model.getCode())) ? model.getCode() : DEFAULT_IMAGE_MODEL;
        JSONObject modelConfig = resolveModelConfig(modelCode, model);
        if (!isAsyncMode(modelConfig) || StrUtil.isBlank(platformTaskId)) {
            return;
        }

        List<String> urls = waitForAsyncTask(platformTaskId, apiConfig, modelConfig);
        updateImageTaskResults(task, urls);
        log.info("[OpenAI] 异步生图完成: taskId={}, platformTaskId={}, imageCount={}",
                task.getTaskId(), platformTaskId, urls.size());
    }

    private void updateImageTaskResults(ImageTask task, List<String> urls) {
        List<ImageItem> items = imageGenerationService.listItems(task.getId());
        for (int i = 0; i < urls.size() && i < items.size(); i++) {
            ImageItem item = items.get(i);
            item.setImageUrl(urls.get(i));
            item.setStatus(1);
            imageGenerationService.updateItem(item);
        }

        task.setSuccessCount(Math.min(urls.size(), items.size()));
        imageGenerationService.update(task);
    }

    private AiModel resolveModel(ImageTask task) {
        if (task.getModelId() == null) {
            log.warn("[OpenAI] resolveModel: task.getModelId() 为 null, taskId={}, modelCode={}", task.getTaskId(), task.getModelCode());
            return null;
        }
        AiModel model = aiModelService.getById(task.getModelId());
        if (model == null) {
            log.warn("[OpenAI] resolveModel: 模型不存在, modelId={}, taskId={}", task.getModelId(), task.getTaskId());
            return null;
        }
        if (StrUtil.isBlank(model.getCode())) {
            log.warn("[OpenAI] resolveModel: 模型 code 为空, modelId={}, modelName={}, taskId={}", model.getId(), model.getName(), task.getTaskId());
            return null;
        }
        return model;
    }

    /**
     * 将像素尺寸映射到 OpenAI 支持的尺寸值。
     * 支持的固定尺寸、自定义尺寸开关和尺寸约束均从模型 config JSON 读取。
     */
    private String mapSize(String modelCode, int width, int height, JSONObject modelConfig) {
        String actualModelCode = StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL);
        String sizeStr = width + "x" + height;
        if (isConfiguredSizeSupported(modelConfig, sizeStr)
                || (isCustomSizeEnabled(modelConfig) && isValidConfiguredCustomSize(width, height, modelConfig))) {
            return sizeStr;
        }

        String fallback = resolveFallbackSize(modelConfig);
        if (!sizeStr.equalsIgnoreCase(fallback)) {
            log.warn("[OpenAI] 模型 {} 不支持尺寸 {}，回退到 {}", actualModelCode, sizeStr, fallback);
        }
        return fallback;
    }

    /**
     * 从 supportedSizes 配置中反查 width×height 对应的 aspect_ratio 和 resolution 标签。
     * supportedSizes 结构: {"1K": {"1:1": "1024x1024", ...}, "2K": {...}, "4K": {...}}
     * 返回 String[]{"1:1", "1K"} 格式；未匹配时按像素尺寸推断比例并默认 "1K"。
     */
    private String[] resolveAspectRatioAndResolution(int width, int height, JSONObject modelConfig) {
        String sizeStr = width + "x" + height;
        Object supportedSizes = modelConfig != null ? modelConfig.get("supportedSizes") : null;
        if (supportedSizes instanceof JSONObject tiers) {
            for (String resolution : tiers.keySet()) {
                Object tierValue = tiers.get(resolution);
                if (tierValue instanceof JSONObject ratios) {
                    for (String ratio : ratios.keySet()) {
                        String configuredSize = normalizeSizeText(ratios.getStr(ratio));
                        if (sizeStr.equalsIgnoreCase(configuredSize)) {
                            return new String[]{ratio, resolution};
                        }
                    }
                }
            }
        }
        String derivedRatio = deriveAspectRatioLabel(width, height);
        String derivedResolution = deriveResolutionLabel(width, height);
        log.warn("[OpenAI] supportedSizes 中未找到尺寸 {}，推断 aspect_ratio={}, resolution={}",
                sizeStr, derivedRatio, derivedResolution);
        return new String[]{derivedRatio, derivedResolution};
    }

    private String deriveAspectRatioLabel(int width, int height) {
        int gcd = gcd(width, height);
        int rw = width / gcd;
        int rh = height / gcd;
        if (rw > 21 || rh > 21) {
            double ratio = (double) width / height;
            if (ratio > 1) {
                return String.format(Locale.ROOT, "%.0f:1", ratio);
            } else {
                return String.format(Locale.ROOT, "1:%.0f", 1.0 / ratio);
            }
        }
        return rw + ":" + rh;
    }

    private String deriveResolutionLabel(int width, int height) {
        int longEdge = Math.max(width, height);
        if (longEdge >= 3500) return "4K";
        if (longEdge >= 1800) return "2K";
        return "1K";
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private String buildGenerationRequestBody(String prompt, String modelCode, int width, int height, int count,
                                              List<String> imageUrls,
                                              JSONObject modelConfig) {
        try {
            var root = OBJECT_MAPPER.createObjectNode();
            root.put("prompt", prompt);
            root.put("model", StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL));
            root.put("n", Math.max(count, 1));
            boolean useAspectRatioResolution = Boolean.TRUE.equals(getBoolean(modelConfig, "useAspectRatioResolution"));
            if (useAspectRatioResolution) {
                String[] ar = resolveAspectRatioAndResolution(width, height, modelConfig);
                root.put("aspect_ratio", ar[0]);
                root.put("resolution", ar[1]);
            } else {
                root.put("size", mapSize(modelCode, width, height, modelConfig));
                appendOptionalString(root, "resolution", getString(modelConfig, "resolution", "defaultResolution"));
            }
            if (imageUrls != null && !imageUrls.isEmpty()) {
                var imageUrlArray = root.putArray("image_urls");
                imageUrls.stream()
                        .filter(StrUtil::isNotBlank)
                        .map(String::trim)
                        .forEach(imageUrlArray::add);
            }
            appendOptionalString(root, "quality", getString(modelConfig, "quality", "imageQuality"));
            appendOptionalString(root, "background", getString(modelConfig, "background"));
            appendOptionalString(root, "moderation", getString(modelConfig, "moderation"));
            appendOptionalString(root, "output_format", getString(modelConfig, "outputFormat", "output_format"));
            appendOptionalInteger(root, "output_compression", getInteger(modelConfig, "outputCompression", "output_compression"));
            appendOptionalString(root, "response_format", getString(modelConfig, "responseFormat", "response_format"));
            appendOptionalString(root, "mask_url", getString(modelConfig, "maskUrl", "mask_url"));
            appendOptionalString(root, "style", getString(modelConfig, "style"));
            appendOptionalString(root, "negative_prompt", getString(modelConfig, "negativePrompt", "negative_prompt"));
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建 OpenAI 图片请求失败: " + e.getMessage(), e);
        }
    }

    private List<String> generateViaEdits(String prompt, String modelCode, int width, int height, int count,
                                          List<String> imageUrls,
                                          ApiConfig apiConfig,
                                          JSONObject modelConfig) {
        String requestUrl = resolveImagesEditUrl(apiConfig);
        RequestBody requestBody = buildEditRequestBody(prompt, modelCode, width, height, count, imageUrls,
                apiConfig, modelConfig);
        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        Request request = new Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer " + apiConfig.getApiKey())
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI 图片编辑失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            return parseImageUrls(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 图片编辑调用异常: " + e.getMessage(), e);
        }
    }

    private RequestBody buildEditRequestBody(String prompt, String modelCode, int width, int height, int count,
                                             List<String> imageUrls,
                                             ApiConfig apiConfig,
                                             JSONObject modelConfig) {
        boolean useAspectRatioResolution = Boolean.TRUE.equals(getBoolean(modelConfig, "useAspectRatioResolution"));
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL))
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", String.valueOf(Math.max(count, 1)));
        if (useAspectRatioResolution) {
            String[] ar = resolveAspectRatioAndResolution(width, height, modelConfig);
            builder.addFormDataPart("aspect_ratio", ar[0]);
            builder.addFormDataPart("resolution", ar[1]);
        } else {
            builder.addFormDataPart("size", mapSize(modelCode, width, height, modelConfig));
            appendOptionalFormField(builder, "resolution", getString(modelConfig, "resolution", "defaultResolution"));
        }

        appendOptionalFormField(builder, "quality", getString(modelConfig, "quality", "imageQuality"));
        appendOptionalFormField(builder, "background", getString(modelConfig, "background"));
        appendOptionalFormField(builder, "moderation", getString(modelConfig, "moderation"));
        appendOptionalFormField(builder, "output_format", getString(modelConfig, "outputFormat", "output_format"));
        appendOptionalFormField(builder, "output_compression", getString(modelConfig, "outputCompression", "output_compression"));
        appendOptionalFormField(builder, "style", getString(modelConfig, "style"));

        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            BinaryResource resource;
            try {
                resource = loadBinaryResource(imageUrl, apiConfig);
            } catch (IOException e) {
                throw new RuntimeException("加载 OpenAI 参考图失败: " + e.getMessage(), e);
            }
            builder.addFormDataPart(
                    "image[]",
                    "reference-" + (i + 1) + "." + resource.extension(),
                    RequestBody.create(resource.bytes(), mediaTypeOrDefault(resource.mimeType()))
            );
        }

        return builder.build();
    }

    /**
     * 构建异步图片任务的 multipart 请求体（SuperToken /v1/image/tasks）。
     * 与 buildEditRequestBody 的区别：
     * - form field 名为 "image"（非 "image[]"）
     * - 已预上传的公网 URL 通过 image_urls 字段传递，不再下载重传
     * - 无参考图时自动附加 1x1 占位图（SuperToken 要求至少一个 image 文件）
     */
    private RequestBody buildAsyncTaskRequestBody(String prompt, String modelCode, int width, int height, int count,
                                                   List<String> imageUrls,
                                                   ApiConfig apiConfig,
                                                   JSONObject modelConfig) {
        boolean useAspectRatioResolution = Boolean.TRUE.equals(getBoolean(modelConfig, "useAspectRatioResolution"));
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", StrUtil.blankToDefault(modelCode, DEFAULT_IMAGE_MODEL))
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", String.valueOf(Math.max(count, 1)));
        if (useAspectRatioResolution) {
            String[] ar = resolveAspectRatioAndResolution(width, height, modelConfig);
            builder.addFormDataPart("aspect_ratio", ar[0]);
            builder.addFormDataPart("resolution", ar[1]);
        } else {
            builder.addFormDataPart("size", mapSize(modelCode, width, height, modelConfig));
            appendOptionalFormField(builder, "resolution", getString(modelConfig, "resolution", "defaultResolution"));
        }

        appendOptionalFormField(builder, "quality", getString(modelConfig, "quality", "imageQuality"));
        appendOptionalFormField(builder, "background", getString(modelConfig, "background"));
        appendOptionalFormField(builder, "moderation", getString(modelConfig, "moderation"));
        appendOptionalFormField(builder, "output_format", getString(modelConfig, "outputFormat", "output_format"));
        appendOptionalFormField(builder, "output_compression", getString(modelConfig, "outputCompression", "output_compression"));
        appendOptionalFormField(builder, "style", getString(modelConfig, "style"));
        appendOptionalFormField(builder, "negative_prompt", getString(modelConfig, "negativePrompt", "negative_prompt"));

        boolean hasImageFile = false;
        if (imageUrls != null) {
            for (int i = 0; i < imageUrls.size(); i++) {
                String imageUrl = imageUrls.get(i).trim();
                if (StrUtil.isBlank(imageUrl)) {
                    continue;
                }
                if (StrUtil.startWithIgnoreCase(imageUrl, "http://") || StrUtil.startWithIgnoreCase(imageUrl, "https://")) {
                    builder.addFormDataPart("image_urls", imageUrl);
                } else {
                    try {
                        BinaryResource resource = loadBinaryResource(imageUrl, apiConfig);
                        builder.addFormDataPart("image",
                                "reference-" + (i + 1) + "." + resource.extension(),
                                RequestBody.create(resource.bytes(), mediaTypeOrDefault(resource.mimeType())));
                        hasImageFile = true;
                    } catch (IOException e) {
                        throw new RuntimeException("加载异步生图参考图失败: " + e.getMessage(), e);
                    }
                }
            }
        }

        if (!hasImageFile) {
            builder.addFormDataPart("image", "placeholder.png",
                    RequestBody.create(PLACEHOLDER_PNG_BYTES, MediaType.get("image/png")));
        }

        return builder.build();
    }

    private List<String> parseImageUrls(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String errorMessage = extractErrorMessage(root);
            if (StrUtil.isNotBlank(errorMessage)) {
                throw new RuntimeException("OpenAI 图片生成失败: " + errorMessage);
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new RuntimeException("OpenAI 返回空结果，响应预览: " + previewResponse(responseBody));
            }

            List<String> urls = new ArrayList<>();
            for (JsonNode item : data) {
                String url = textValue(item, "url");
                if (StrUtil.isBlank(url)) {
                    url = textValue(item, "image_url");
                }
                if (StrUtil.isNotBlank(url)) {
                    urls.add(url);
                    continue;
                }

                String b64Json = textValue(item, "b64_json");
                if (StrUtil.isNotBlank(b64Json)) {
                    urls.add(storeBase64Image(b64Json, resolveImageExtension(root, item)));
                }
            }

            if (urls.isEmpty()) {
                throw new RuntimeException("OpenAI 图片响应中未找到 url 或 b64_json，响应预览: "
                        + previewResponse(responseBody));
            }
            return urls;
        } catch (IOException e) {
            throw new RuntimeException("解析 OpenAI 图片响应失败: " + e.getMessage(), e);
        }
    }

    private String parseAsyncTaskId(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String errorMessage = extractErrorMessage(root);
            if (StrUtil.isNotBlank(errorMessage)) {
                throw new RuntimeException("OpenAI 异步图片任务提交失败: " + errorMessage);
            }

            JsonNode data = root.path("data");
            JsonNode taskNode = data.isArray() && !data.isEmpty() ? data.get(0) : data;
            String taskId = firstText(taskNode, "task_id", "taskId", "id");
            if (StrUtil.isBlank(taskId)) {
                taskId = firstText(root, "task_id", "taskId", "id");
            }
            if (StrUtil.isBlank(taskId)) {
                // 检查是否为同步返回（API 直接返回了图片 URL 而非 task_id）
                JsonNode dataNode = root.path("data");
                if (dataNode.isArray() && !dataNode.isEmpty()) {
                    boolean hasImageUrl = false;
                    for (JsonNode item : dataNode) {
                        if (StrUtil.isNotBlank(textValue(item, "url"))
                                || StrUtil.isNotBlank(textValue(item, "image_url"))
                                || StrUtil.isNotBlank(textValue(item, "b64_json"))) {
                            hasImageUrl = true;
                            break;
                        }
                    }
                    if (hasImageUrl) {
                        log.info("[OpenAI] 异步提交响应中未找到 task_id，但检测到同步图片 URL，标记为同步结果");
                        return "sync:" + responseBody;
                    }
                }
                throw new RuntimeException("OpenAI 异步图片任务提交响应中未找到 task_id，响应预览: "
                        + previewResponse(responseBody));
            }
            return taskId;
        } catch (IOException e) {
            throw new RuntimeException("解析 OpenAI 异步图片任务提交响应失败: " + e.getMessage(), e);
        }
    }

    private AsyncTaskResult parseAsyncTaskResult(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String errorMessage = extractErrorMessage(root);
            if (StrUtil.isNotBlank(errorMessage)) {
                return new AsyncTaskResult(null, true, false, List.of(), errorMessage);
            }

            JsonNode data = root.path("data");
            JsonNode taskNode = data.isArray() && !data.isEmpty() ? data.get(0) : data;
            if (taskNode.isMissingNode() || taskNode.isNull()) {
                taskNode = root;
            }

            String status = StrUtil.blankToDefault(firstText(taskNode, "status", "state"),
                    firstText(root, "status", "state"));
            String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
            if (isAsyncSuccessStatus(normalizedStatus)) {
                return new AsyncTaskResult(status, false, true, extractAsyncImageUrls(root), null);
            }
            if (isAsyncFailureStatus(normalizedStatus)) {
                return new AsyncTaskResult(status, true, false, List.of(), extractAsyncErrorMessage(root, taskNode));
            }

            return new AsyncTaskResult(status, false, false, List.of(), null);
        } catch (IOException e) {
            throw new RuntimeException("解析 OpenAI 异步图片任务查询响应失败: " + e.getMessage(), e);
        }
    }

    private List<String> extractAsyncImageUrls(JsonNode root) {
        List<String> urls = new ArrayList<>();
        JsonNode data = root.path("data");
        JsonNode taskNode = data.isArray() && !data.isEmpty() ? data.get(0) : data;
        collectAsyncImageUrls(taskNode.path("result").path("images"), urls);
        collectAsyncImageUrls(taskNode.path("images"), urls);
        collectAsyncImageUrls(root.path("result").path("images"), urls);
        collectAsyncImageUrls(root.path("images"), urls);

        if (urls.isEmpty() && data.isArray()) {
            for (JsonNode item : data) {
                collectUrlFields(item, urls);
            }
        }
        return urls;
    }

    private void collectAsyncImageUrls(JsonNode imagesNode, List<String> urls) {
        if (imagesNode == null || imagesNode.isMissingNode() || imagesNode.isNull()) {
            return;
        }
        if (imagesNode.isArray()) {
            for (JsonNode imageNode : imagesNode) {
                collectUrlFields(imageNode, urls);
            }
            return;
        }
        collectUrlFields(imagesNode, urls);
    }

    private void collectUrlFields(JsonNode node, List<String> urls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        collectUrlValue(node.path("url"), urls);
        collectUrlValue(node.path("urls"), urls);
        collectUrlValue(node.path("image_url"), urls);
        collectUrlValue(node.path("imageUrl"), urls);
    }

    private void collectUrlValue(JsonNode value, List<String> urls) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                collectUrlValue(item, urls);
            }
            return;
        }
        if (value.isTextual() && StrUtil.isNotBlank(value.asText())) {
            urls.add(value.asText().trim());
        }
    }

    private boolean isAsyncSuccessStatus(String status) {
        return "completed".equals(status) || "succeeded".equals(status)
                || "success".equals(status) || "done".equals(status);
    }

    private boolean isAsyncFailureStatus(String status) {
        return "failed".equals(status) || "error".equals(status)
                || "cancelled".equals(status) || "canceled".equals(status);
    }

    private String extractAsyncErrorMessage(JsonNode root, JsonNode taskNode) {
        String message = firstText(taskNode, "error", "error_message", "errorMessage", "message");
        if (StrUtil.isNotBlank(message)) {
            return message;
        }
        return StrUtil.blankToDefault(extractErrorMessage(root), previewResponse(root.toString()));
    }

    private String storeBase64Image(String base64Payload, String extension) {
        return storeBase64Image(base64Payload, extension, "images");
    }

    private String storeBase64Image(String base64Payload, String extension, String storagePath) {
        String trimmed = StrUtil.trim(base64Payload);
        String actualExtension = extension;

        if (StrUtil.startWithIgnoreCase(trimmed, "data:")) {
            int commaIndex = trimmed.indexOf(',');
            String metadata = commaIndex > 0 ? trimmed.substring(0, commaIndex) : trimmed;
            if (commaIndex > 0) {
                trimmed = trimmed.substring(commaIndex + 1);
            }
            actualExtension = resolveExtensionFromMetadata(metadata, actualExtension);
        }

        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(trimmed.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("OpenAI 返回的图片 base64 数据无效", e);
        }
        return mediaStorageService.storeBytes(imageBytes, StrUtil.blankToDefault(storagePath, "images"), actualExtension);
    }

    private String resolveImagesEditUrl(ApiConfig apiConfig) {
        String baseUrl = normalizeBaseUrl(StrUtil.blankToDefault(apiConfig != null ? apiConfig.getApiUrl() : null,
                DEFAULT_BASE_URL));
        if (endsWithIgnoreCase(baseUrl, "/images/edits")) {
            return baseUrl;
        }

        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        if (endsWithIgnoreCase(baseUrl, "/v1")) {
            return baseUrl + "/images/edits";
        }
        return baseUrl + (appendV1 ? "/v1/images/edits" : "/images/edits");
    }

    private String resolveImagesGenerateUrl(ApiConfig apiConfig) {
        String baseUrl = normalizeBaseUrl(StrUtil.blankToDefault(apiConfig != null ? apiConfig.getApiUrl() : null,
                DEFAULT_BASE_URL));
        if (endsWithIgnoreCase(baseUrl, "/images/generations")) {
            return baseUrl;
        }

        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        if (endsWithIgnoreCase(baseUrl, "/v1")) {
            return baseUrl + "/images/generations";
        }
        return baseUrl + (appendV1 ? "/v1/images/generations" : "/images/generations");
    }

    private String resolveAsyncTaskUrl(ApiConfig apiConfig, JSONObject modelConfig, String taskId) {
        String configuredPath = getString(modelConfig, "asyncTaskStatusPath", "asyncTaskPath", "taskStatusPath");
        if (StrUtil.isNotBlank(configuredPath)) {
            String path = configuredPath.trim()
                    .replace("{task_id}", taskId)
                    .replace("{taskId}", taskId)
                    .replace("{id}", taskId);
            return resolvePathAgainstRoot(apiConfig, path);
        }

        String submitPath = getString(modelConfig, "asyncSubmitPath", "asyncTaskSubmitPath", "taskSubmitPath");
        String rootUrl = resolveApiRootUrl(apiConfig);
        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        if (StrUtil.isNotBlank(submitPath) && submitPath.contains("/image/tasks")) {
            if (endsWithIgnoreCase(rootUrl, "/v1")) {
                return rootUrl + "/image/tasks/" + taskId;
            }
            return rootUrl + (appendV1 ? "/v1/image/tasks/" : "/image/tasks/") + taskId;
        }
        if (endsWithIgnoreCase(rootUrl, "/v1")) {
            return rootUrl + "/tasks/" + taskId;
        }
        return rootUrl + (appendV1 ? "/v1/tasks/" : "/tasks/") + taskId;
    }

    /**
     * 解析异步任务提交 URL。
     * 支持通过 modelConfig.asyncSubmitPath 自定义提交路径（如 SuperToken 的 /v1/image/tasks），
     * 否则回退到默认的 /images/generations。
     */
    private String resolveAsyncSubmitUrl(ApiConfig apiConfig, JSONObject modelConfig) {
        String configuredPath = getString(modelConfig, "asyncSubmitPath", "asyncTaskSubmitPath", "taskSubmitPath");
        if (StrUtil.isNotBlank(configuredPath)) {
            return resolvePathAgainstRoot(apiConfig, configuredPath);
        }
        return resolveImagesGenerateUrl(apiConfig);
    }

    /**
     * 解析资源 API Key（用于查询任务、预上传等资源类操作）。
     * 部分代理（如 SuperToken）创建任务用模型 API Token（sk-...），
     * 查询任务用资源 API Key（ak_...）。
     * 优先级：modelConfig.resourceApiKey > apiConfig.appSecret > apiConfig.apiKey
     */
    private String resolveResourceApiKey(ApiConfig apiConfig, JSONObject modelConfig) {
        String resourceApiKey = getString(modelConfig, "resourceApiKey", "queryApiKey", "resourceKey");
        if (StrUtil.isNotBlank(resourceApiKey)) {
            return resourceApiKey;
        }
        if (apiConfig != null && StrUtil.isNotBlank(apiConfig.getAppSecret())) {
            return apiConfig.getAppSecret();
        }
        return apiConfig != null ? apiConfig.getApiKey() : null;
    }

    /**
     * 异步模式下预处理参考图 URL。
     * 当 modelConfig.asyncPreUploadImages=true 时，将非公网可访问的参考图（本地 /media/ 路径、
     * data: URL、本地文件）通过资源 API 的预上传接口上传，获取公网可访问的 URL。
     * SuperToken 等代理的异步任务接口通过 image_urls 字段传递参考图，要求 URL 公网可访问。
     */
    private List<String> prepareAsyncImageUrls(List<String> imageUrls, ApiConfig apiConfig, JSONObject modelConfig) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return imageUrls;
        }
        boolean preUpload = Boolean.TRUE.equals(getBoolean(modelConfig,
                "asyncPreUploadImages", "preUploadImages", "enablePreUpload"));
        if (!preUpload) {
            return imageUrls;
        }
        String uploadUrl = resolveAsyncUploadUrl(apiConfig, modelConfig);
        String uploadApiKey = resolveResourceApiKey(apiConfig, modelConfig);

        List<String> prepared = new ArrayList<>();
        for (String imageUrl : imageUrls) {
            if (StrUtil.isBlank(imageUrl)) {
                continue;
            }
            String trimmed = imageUrl.trim();
            if (StrUtil.startWithIgnoreCase(trimmed, "http://") || StrUtil.startWithIgnoreCase(trimmed, "https://")) {
                prepared.add(trimmed);
                continue;
            }
            try {
                BinaryResource resource = loadBinaryResource(trimmed, apiConfig);
                String uploadedUrl = uploadImageResource(uploadUrl, uploadApiKey, resource,
                        "ref-" + (prepared.size() + 1));
                prepared.add(uploadedUrl);
                log.info("[OpenAI] 参考图预上传成功: source={} -> uploaded={}", trimmed, uploadedUrl);
            } catch (Exception e) {
                log.warn("[OpenAI] 参考图预上传失败，回退到原始 URL: {}", trimmed, e);
                prepared.add(trimmed);
            }
        }
        return prepared;
    }

    /**
     * 解析异步预上传 URL。
     * 支持 modelConfig.asyncUploadPath 自定义（如 SuperToken 的 /v1/image/uploads），
     * 否则回退到 /v1/uploads。
     */
    private String resolveAsyncUploadUrl(ApiConfig apiConfig, JSONObject modelConfig) {
        String configuredPath = getString(modelConfig, "asyncUploadPath", "asyncTaskUploadPath", "uploadPath");
        if (StrUtil.isNotBlank(configuredPath)) {
            return resolvePathAgainstRoot(apiConfig, configuredPath);
        }
        String rootUrl = resolveApiRootUrl(apiConfig);
        boolean appendV1 = shouldAutoAppendV1Path(apiConfig);
        if (endsWithIgnoreCase(rootUrl, "/v1")) {
            return rootUrl + "/uploads";
        }
        return rootUrl + (appendV1 ? "/v1/uploads" : "/uploads");
    }

    /**
     * 通过 multipart 上传图片资源到代理的预上传接口，返回公网可访问的 URL。
     */
    private String uploadImageResource(String uploadUrl, String apiKey, BinaryResource resource, String filenameHint)
            throws IOException {
        String filename = filenameHint + "." + resource.extension();
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", filename,
                        RequestBody.create(resource.bytes(), mediaTypeOrDefault(resource.mimeType())))
                .build();
        Request request = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, null);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("预上传失败: HTTP " + response.code()
                        + (StrUtil.isNotBlank(responseBody) ? " - " + responseBody : ""));
            }
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String url = firstText(root, "url", "image_url", "imageUrl", "link");
            if (StrUtil.isBlank(url)) {
                JsonNode data = root.path("data");
                url = firstText(data, "url", "image_url", "imageUrl", "link");
            }
            if (StrUtil.isBlank(url)) {
                throw new IOException("预上传响应中未找到 URL，响应预览: " + previewResponse(responseBody));
            }
            return url;
        }
    }

    /**
     * 将配置的相对路径拼接到 API root URL 上，自动处理 /v1 重复问题。
     * 当 rootUrl 已以 /v1 结尾且 path 以 /v1/ 开头时，去掉 path 中的 /v1 前缀。
     */
    private String resolvePathAgainstRoot(ApiConfig apiConfig, String path) {
        String trimmedPath = path.trim();
        if (StrUtil.startWithIgnoreCase(trimmedPath, "http://") || StrUtil.startWithIgnoreCase(trimmedPath, "https://")) {
            return trimmedPath;
        }
        String rootUrl = resolveApiRootUrl(apiConfig);
        if (endsWithIgnoreCase(rootUrl, "/v1") && StrUtil.startWithIgnoreCase(trimmedPath, "/v1/")) {
            trimmedPath = trimmedPath.substring(3);
        }
        return rootUrl + (trimmedPath.startsWith("/") ? trimmedPath : "/" + trimmedPath);
    }

    private String resolveApiRootUrl(ApiConfig apiConfig) {
        String baseUrl = normalizeBaseUrl(StrUtil.blankToDefault(apiConfig != null ? apiConfig.getApiUrl() : null,
                DEFAULT_BASE_URL));
        if (endsWithIgnoreCase(baseUrl, "/images/generations")) {
            return baseUrl.substring(0, baseUrl.length() - "/images/generations".length());
        }
        if (endsWithIgnoreCase(baseUrl, "/images/edits")) {
            return baseUrl.substring(0, baseUrl.length() - "/images/edits".length());
        }
        return baseUrl;
    }

    private boolean shouldAutoAppendV1Path(ApiConfig apiConfig) {
        if (apiConfig == null) {
            return true;
        }
        if (!"openai_compatible".equalsIgnoreCase(apiConfig.getPlatform())) {
            return true;
        }
        return !Boolean.FALSE.equals(apiConfig.getAutoAppendV1Path());
    }

    private String extractErrorMessage(JsonNode root) {
        JsonNode error = root.path("error");
        if (error.isMissingNode() || error.isNull()) {
            return null;
        }

        String message = textValue(error, "message");
        if (StrUtil.isNotBlank(message)) {
            return message;
        }
        return error.isTextual() ? error.asText() : previewResponse(error.toString());
    }

    private BinaryResource loadBinaryResource(String sourceUrl, ApiConfig apiConfig) throws IOException {
        if (StrUtil.isBlank(sourceUrl)) {
            throw new BusinessException("OpenAI 参考图地址为空");
        }
        String trimmed = sourceUrl.trim();
        if (trimmed.startsWith("data:")) {
            return parseDataUrl(trimmed);
        }
        if (trimmed.startsWith("/media/")) {
            return loadLocalMedia(trimmed);
        }
        if (presetArtStyleResourceResolver.isPresetArtStylePath(trimmed)) {
            PresetArtStyleResourceResolver.PresetArtStyleResource resource = presetArtStyleResourceResolver.load(trimmed);
            return new BinaryResource(resource.bytes(), resource.mimeType(), extensionFromMimeType(resource.mimeType()));
        }
        if (trimmed.startsWith("file:")) {
            return loadFile(Paths.get(URI.create(trimmed)));
        }
        if (StrUtil.startWithIgnoreCase(trimmed, "http://") || StrUtil.startWithIgnoreCase(trimmed, "https://")) {
            Request request = new Request.Builder()
                    .url(trimmed)
                    .get()
                    .addHeader("Accept", "image/*,*/*;q=0.8")
                    .build();
            OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new BusinessException("下载参考图失败: HTTP " + response.code() + " url=" + trimmed);
                }
                String mimeType = normalizeMimeType(response.header("Content-Type"), trimmed);
                return new BinaryResource(response.body().bytes(), mimeType, extensionFromMimeType(mimeType));
            }
        }

        Path localPath = Paths.get(trimmed);
        if (Files.exists(localPath) && Files.isRegularFile(localPath)) {
            return loadFile(localPath);
        }
        throw new BusinessException("参考图地址不可访问: " + trimmed);
    }

    private BinaryResource parseDataUrl(String sourceUrl) {
        int commaIndex = sourceUrl.indexOf(',');
        if (commaIndex <= 0) {
            throw new BusinessException("OpenAI 参考图 data URL 格式非法");
        }

        String metadata = sourceUrl.substring(0, commaIndex);
        String payload = sourceUrl.substring(commaIndex + 1);
        String mimeType = normalizeMimeType(metadata.substring("data:".length()), sourceUrl);
        try {
            byte[] bytes = Base64.getDecoder().decode(payload.getBytes(StandardCharsets.UTF_8));
            return new BinaryResource(bytes, mimeType, extensionFromMimeType(mimeType));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("OpenAI 参考图 data URL base64 非法: " + e.getMessage());
        }
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
        throw new BusinessException("本地参考图不存在: " + sourceUrl);
    }

    private BinaryResource loadFile(Path path) throws IOException {
        String mimeType = normalizeMimeType(null, path.getFileName().toString());
        return new BinaryResource(Files.readAllBytes(path), mimeType, extensionFromMimeType(mimeType));
    }

    private String resolveImageExtension(JsonNode root, JsonNode item) {
        String outputFormat = textValue(root, "output_format");
        if (StrUtil.isBlank(outputFormat)) {
            outputFormat = textValue(item, "output_format");
        }
        if (StrUtil.isBlank(outputFormat)) {
            outputFormat = textValue(item, "mime_type");
        }
        if (StrUtil.isBlank(outputFormat)) {
            return "png";
        }
        String normalized = outputFormat.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) {
            normalized = normalized.substring("image/".length());
        }
        return switch (normalized) {
            case "png", "jpeg", "jpg", "webp", "gif" -> normalized;
            default -> "png";
        };
    }

    private JSONObject resolveModelConfig(String modelCode, AiModel model) {
        JSONObject merged = new JSONObject();
        String actualModelCode = StrUtil.blankToDefault(modelCode, model != null ? model.getCode() : null);
        if (modelPresetService != null && StrUtil.isNotBlank(actualModelCode)) {
            mergeConfig(merged, parseConfig(modelPresetService.getPresetConfig(actualModelCode), actualModelCode));
        }
        if (model != null) {
            mergeConfig(merged, parseConfig(model.getConfig(), model.getCode()));
        }
        return merged;
    }

    private JSONObject parseConfig(String configJson, String modelCode) {
        if (StrUtil.isBlank(configJson)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception e) {
            log.warn("解析图片模型配置失败，已忽略 OpenAI 图片附加参数: modelCode={}, message={}",
                    modelCode, e.getMessage());
            return null;
        }
    }

    private void mergeConfig(JSONObject target, JSONObject source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (String key : source.keySet()) {
            target.set(key, source.get(key));
        }
    }

    private String getString(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            String value = config.getStr(key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Boolean getBoolean(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Boolean bool) {
                return bool;
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
        }
        return null;
    }

    private Integer getInteger(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Long getLong(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Double getDouble(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value != null) {
                try {
                    return Double.parseDouble(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void appendOptionalString(com.fasterxml.jackson.databind.node.ObjectNode root, String fieldName,
                                      String value) {
        if (StrUtil.isNotBlank(value)) {
            root.put(fieldName, value.trim());
        }
    }

    private void appendOptionalInteger(com.fasterxml.jackson.databind.node.ObjectNode root, String fieldName,
                                       Integer value) {
        if (value != null) {
            root.put(fieldName, value);
        }
    }

    private void appendOptionalFormField(MultipartBody.Builder builder, String fieldName, String value) {
        if (StrUtil.isNotBlank(value)) {
            builder.addFormDataPart(fieldName, value.trim());
        }
    }

    private boolean isAsyncMode(JSONObject modelConfig) {
        return Boolean.TRUE.equals(getBoolean(modelConfig,
                "asyncMode", "useAsyncMode", "asyncTaskMode", "enableAsyncTask", "asyncEnabled"));
    }

    private int[] resolveConfiguredSize(ImageTask task, JSONObject modelConfig) {
        int width = (task.getWidth() != null && task.getWidth() > 0) ? task.getWidth() : 0;
        int height = (task.getHeight() != null && task.getHeight() > 0) ? task.getHeight() : 0;

        // 校验 task 传入的尺寸是否在模型支持范围内（maxPixels / supportedSizes）
        if (width > 0 && height > 0 && !isConfiguredSizeSupported(modelConfig, width + "x" + height)) {
            log.warn("[OpenAI] 任务尺寸 {}x{} 不在模型支持范围内，回退到默认尺寸", width, height);
            width = 0;
            height = 0;
        }

        if (width <= 0) {
            Integer configuredWidth = getInteger(modelConfig, "defaultWidth", "width");
            width = configuredWidth != null ? configuredWidth : 0;
        }
        if (height <= 0) {
            Integer configuredHeight = getInteger(modelConfig, "defaultHeight", "height");
            height = configuredHeight != null ? configuredHeight : 0;
        }

        if (width <= 0 || height <= 0) {
            int[] fallback = parseSizeText(resolveFallbackSize(modelConfig));
            if (width <= 0) {
                width = fallback[0];
            }
            if (height <= 0) {
                height = fallback[1];
            }
        }

        if (width <= 0) width = 1024;
        if (height <= 0) height = 1024;
        return new int[]{width, height};
    }

    private boolean isConfiguredSizeSupported(JSONObject modelConfig, String sizeStr) {
        if (modelConfig == null || StrUtil.isBlank(sizeStr)) {
            return false;
        }
        if (!containsConfiguredSize(modelConfig.get("supportedSizes"), sizeStr)) {
            return false;
        }
        // 即使尺寸在 supportedSizes 中，也要校验 maxPixels
        int[] wh = parseSizeText(sizeStr);
        if (wh != null) {
            long pixels = (long) wh[0] * wh[1];
            Long maxPixels = getLong(modelConfig, "maxPixels");
            if (maxPixels != null && maxPixels > 0 && pixels > maxPixels) {
                return false;
            }
        }
        return true;
    }

    private boolean containsConfiguredSize(Object value, String sizeStr) {
        if (value == null) {
            return false;
        }
        if (value instanceof JSONObject jsonObject) {
            for (String key : jsonObject.keySet()) {
                if (containsConfiguredSize(jsonObject.get(key), sizeStr)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsConfiguredSize(item, sizeStr)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsConfiguredSize(item, sizeStr)) {
                    return true;
                }
            }
            return false;
        }
        return sizeStr.equalsIgnoreCase(normalizeSizeText(value.toString()));
    }

    private boolean isCustomSizeEnabled(JSONObject modelConfig) {
        return Boolean.TRUE.equals(getBoolean(modelConfig,
                "supportCustomSize", "supportsCustomSize", "allowCustomSize", "customSizeEnabled"));
    }

    private boolean isValidConfiguredCustomSize(int width, int height, JSONObject modelConfig) {
        if (width <= 0 || height <= 0) {
            return false;
        }

        Integer sizeMultiple = getInteger(modelConfig, "sizeMultiple", "imageSizeMultiple", "customSizeMultiple");
        if (sizeMultiple != null && sizeMultiple > 1
                && (width % sizeMultiple != 0 || height % sizeMultiple != 0)) {
            return false;
        }

        Integer maxEdge = getInteger(modelConfig, "maxEdge", "maxImageEdge", "maxDimension");
        if (maxEdge != null && maxEdge > 0 && Math.max(width, height) > maxEdge) {
            return false;
        }

        Integer minEdge = getInteger(modelConfig, "minEdge", "minImageEdge", "minDimension");
        if (minEdge != null && minEdge > 0 && Math.min(width, height) < minEdge) {
            return false;
        }

        Double maxAspectRatio = getDouble(modelConfig, "maxAspectRatio", "maxRatio");
        if (maxAspectRatio != null && maxAspectRatio > 0) {
            int longEdge = Math.max(width, height);
            int shortEdge = Math.min(width, height);
            if (shortEdge <= 0 || (double) longEdge / (double) shortEdge > maxAspectRatio) {
                return false;
            }
        }

        long pixels = (long) width * height;
        Long minPixels = getLong(modelConfig, "minPixels");
        if (minPixels != null && minPixels > 0 && pixels < minPixels) {
            return false;
        }
        Long maxPixels = getLong(modelConfig, "maxPixels");
        return maxPixels == null || maxPixels <= 0 || pixels <= maxPixels;
    }

    private String resolveFallbackSize(JSONObject modelConfig) {
        String defaultSize = getString(modelConfig, "defaultSize");
        if (isPixelSizeText(defaultSize)) {
            return normalizeSizeText(defaultSize);
        }

        Integer defaultWidth = getInteger(modelConfig, "defaultWidth", "width");
        Integer defaultHeight = getInteger(modelConfig, "defaultHeight", "height");
        if (defaultWidth != null && defaultWidth > 0 && defaultHeight != null && defaultHeight > 0) {
            return defaultWidth + "x" + defaultHeight;
        }

        String firstConfiguredSize = findFirstConfiguredSize(modelConfig != null ? modelConfig.get("supportedSizes") : null);
        if (StrUtil.isNotBlank(firstConfiguredSize)) {
            return firstConfiguredSize;
        }
        return "1024x1024";
    }

    private String findFirstConfiguredSize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject jsonObject) {
            for (String key : jsonObject.keySet()) {
                String found = findFirstConfiguredSize(jsonObject.get(key));
                if (StrUtil.isNotBlank(found)) {
                    return found;
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                String found = findFirstConfiguredSize(item);
                if (StrUtil.isNotBlank(found)) {
                    return found;
                }
            }
            return null;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String found = findFirstConfiguredSize(item);
                if (StrUtil.isNotBlank(found)) {
                    return found;
                }
            }
            return null;
        }
        String text = normalizeSizeText(value.toString());
        return isPixelSizeText(text) ? text : null;
    }

    private int[] parseSizeText(String sizeText) {
        String normalized = normalizeSizeText(sizeText);
        if (!isPixelSizeText(normalized)) {
            return new int[]{1024, 1024};
        }
        String[] parts = normalized.split("x", 2);
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException ignored) {
            return new int[]{1024, 1024};
        }
    }

    private String normalizeSizeText(String sizeText) {
        return StrUtil.blankToDefault(sizeText, "").trim().toLowerCase(Locale.ROOT).replace('*', 'x');
    }

    private boolean isPixelSizeText(String sizeText) {
        return StrUtil.isNotBlank(sizeText) && normalizeSizeText(sizeText).matches("\\d+x\\d+");
    }

    private long resolveAsyncInitialDelayMillis(JSONObject modelConfig) {
        return secondsToMillis(getIntegerOrDefault(modelConfig, DEFAULT_ASYNC_TASK_INITIAL_DELAY_SECONDS,
                "asyncTaskInitialDelaySeconds", "asyncInitialDelaySeconds", "taskInitialDelaySeconds"));
    }

    private long resolveAsyncPollIntervalMillis(JSONObject modelConfig) {
        return Math.max(100L, secondsToMillis(getIntegerOrDefault(modelConfig, DEFAULT_ASYNC_TASK_POLL_INTERVAL_SECONDS,
                "asyncTaskPollIntervalSeconds", "asyncPollIntervalSeconds", "taskPollIntervalSeconds")));
    }

    private long resolveAsyncTimeoutMillis(JSONObject modelConfig) {
        return secondsToMillis(getIntegerOrDefault(modelConfig, DEFAULT_ASYNC_TASK_TIMEOUT_SECONDS,
                "asyncTaskTimeoutSeconds", "asyncTimeoutSeconds", "taskTimeoutSeconds"));
    }

    private Integer getIntegerOrDefault(JSONObject config, int defaultValue, String... keys) {
        Integer value = getInteger(config, keys);
        return value != null && value >= 0 ? value : defaultValue;
    }

    private long secondsToMillis(Integer seconds) {
        return Math.max(seconds != null ? seconds : 0, 0) * 1000L;
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI 异步图片任务轮询被中断", e);
        }
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = textValue(node, fieldName);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String resolveExtensionFromMetadata(String metadata, String fallback) {
        String normalized = metadata == null ? "" : metadata.toLowerCase(Locale.ROOT);
        if (normalized.contains("image/jpeg")) {
            return "jpeg";
        }
        if (normalized.contains("image/jpg")) {
            return "jpg";
        }
        if (normalized.contains("image/webp")) {
            return "webp";
        }
        if (normalized.contains("image/gif")) {
            return "gif";
        }
        if (normalized.contains("image/png")) {
            return "png";
        }
        return StrUtil.blankToDefault(fallback, "png");
    }

    private String normalizeMimeType(String contentType, String sourceUrl) {
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
        return "image/png";
    }

    private String extensionFromMimeType(String mimeType) {
        String normalized = normalizeMimeType(mimeType, mimeType).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private MediaType mediaTypeOrDefault(String mimeType) {
        try {
            return MediaType.get(StrUtil.blankToDefault(mimeType, "image/png"));
        } catch (Exception ignored) {
            return MediaType.get("application/octet-stream");
        }
    }

    private String textValue(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StrUtil.isBlank(text) ? null : text;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return StrUtil.blankToDefault(baseUrl, DEFAULT_BASE_URL).trim().replaceAll("/+$", "");
    }

    private boolean endsWithIgnoreCase(String text, String suffix) {
        return text != null && suffix != null && text.toLowerCase(Locale.ROOT)
                .endsWith(suffix.toLowerCase(Locale.ROOT));
    }

    private String previewResponse(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "<empty>";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= RESPONSE_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_PREVIEW_LENGTH) + "...";
    }

    private record BinaryResource(byte[] bytes, String mimeType, String extension) {
    }

    private record AsyncTaskResult(String status, boolean failed, boolean completed, List<String> urls,
                                   String errorMessage) {
    }
}
