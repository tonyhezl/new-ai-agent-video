package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.generation.VideoTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NewAPI 视频协议公共组包能力。
 */
@Component
public class NewApiVideoProtocolSupport {

    public JSONObject buildGenericSubmitBody(NewApiVideoProtocolContext context) {
        JSONObject body = JSONUtil.createObj();
        String modelCode = StrUtil.trim(context.model().getCode());
        if (StrUtil.isBlank(modelCode)) {
            throw new BusinessException("New API 视频模型未配置 code");
        }
        body.set("model", modelCode);

        String prompt = StrUtil.trim(context.task().getPrompt());
        if (StrUtil.isNotBlank(prompt)) {
            body.set("prompt", prompt);
        }

        String inputImage = resolveInputImage(context.task());
        if (StrUtil.isNotBlank(inputImage)) {
            body.set("image", inputImage);
        }

        if (StrUtil.isBlank(prompt) && StrUtil.isBlank(inputImage)) {
            throw new BusinessException("New API 视频任务至少需要 prompt 或 image 其一");
        }

        Integer duration = firstPositive(
                context.task().getDuration(),
                getPositiveInteger(context.modelConfig(), "defaultDuration", "duration"));
        if (duration != null) {
            body.set("duration", duration);
        }

        int[] dimensions = resolveDimensions(context.task(), context.modelConfig());
        if (dimensions[0] > 0 && dimensions[1] > 0) {
            body.set("width", dimensions[0]);
            body.set("height", dimensions[1]);
        }

        Integer fps = getPositiveInteger(context.modelConfig(), "fps", "frameRate", "frame_rate");
        if (fps != null) {
            body.set("fps", fps);
        }

        if (context.task().getSeed() != null) {
            body.set("seed", context.task().getSeed());
        }

        body.set("n", 1);

        String responseFormat = getString(context.modelConfig(), "responseFormat", "response_format");
        if (StrUtil.isNotBlank(responseFormat)) {
            body.set("response_format", responseFormat);
        }

        String user = context.task().getUserId() != null
                ? String.valueOf(context.task().getUserId())
                : getString(context.modelConfig(), "user");
        if (StrUtil.isNotBlank(user)) {
            body.set("user", user);
        }

        JSONObject metadata = resolveMetadata(context.modelConfig());
        if (!metadata.isEmpty()) {
            body.set("metadata", metadata);
        }
        return body;
    }

    private JSONObject resolveMetadata(JSONObject modelConfig) {
        JSONObject metadata = new JSONObject();
        Object metadataObject = modelConfig.get("metadata");
        if (metadataObject instanceof JSONObject jsonObject) {
            mergeJsonObject(metadata, jsonObject);
        } else if (metadataObject instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    metadata.set(entry.getKey().toString(), entry.getValue());
                }
            }
        }

        appendOptionalString(metadata, "negative_prompt", getString(modelConfig, "negativePrompt", "negative_prompt"));
        appendOptionalString(metadata, "style", getString(modelConfig, "style"));
        appendOptionalString(metadata, "quality_level", getString(modelConfig, "qualityLevel", "quality_level"));
        return metadata;
    }

    private int[] resolveDimensions(VideoTask task, JSONObject modelConfig) {
        int[] parsedTaskResolution = parseDimensions(task.getResolution());
        if (parsedTaskResolution != null) {
            return parsedTaskResolution;
        }

        Integer width = getPositiveInteger(modelConfig, "width", "defaultWidth", "videoWidth", "video_width");
        Integer height = getPositiveInteger(modelConfig, "height", "defaultHeight", "videoHeight", "video_height");
        if (width != null && height != null) {
            return new int[]{width, height};
        }

        int[] parsedConfigResolution = parseDimensions(getString(modelConfig, "resolution", "defaultResolution"));
        if (parsedConfigResolution != null) {
            return parsedConfigResolution;
        }

        return new int[]{0, 0};
    }

    private int[] parseDimensions(String resolution) {
        if (StrUtil.isBlank(resolution)) {
            return null;
        }

        String normalized = resolution.trim()
                .toLowerCase()
                .replace("*", "x")
                .replace(" ", "");
        if (!normalized.matches("\\d+x\\d+")) {
            return null;
        }

        String[] parts = normalized.split("x", 2);
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width > 0 && height > 0) {
                return new int[]{width, height};
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private String resolveInputImage(VideoTask task) {
        if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            return task.getFirstFrameImageUrl();
        }
        List<String> referenceImages = parseJsonUrls(task.getReferenceImageUrls());
        return referenceImages.isEmpty() ? null : referenceImages.get(0);
    }

    private List<String> parseJsonUrls(String jsonUrls) {
        List<String> urls = new ArrayList<>();
        if (StrUtil.isBlank(jsonUrls)) {
            return urls;
        }

        String trimmed = jsonUrls.trim();
        if (!trimmed.startsWith("[")) {
            if (StrUtil.isNotBlank(trimmed)) {
                urls.add(trimmed);
            }
            return urls;
        }

        try {
            JSONArray array = JSONUtil.parseArray(trimmed);
            for (Object item : array) {
                String value = item == null ? null : item.toString().trim();
                if (StrUtil.isNotBlank(value)) {
                    urls.add(value);
                }
            }
            return urls;
        } catch (Exception e) {
            throw new BusinessException("解析参考图列表失败: " + e.getMessage());
        }
    }

    private void mergeJsonObject(JSONObject target, JSONObject source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (String key : source.keySet()) {
            target.set(key, source.get(key));
        }
    }

    private void appendOptionalString(JSONObject target, String key, String value) {
        if (target != null && StrUtil.isNotBlank(value)) {
            target.set(key, value);
        }
    }

    private String getString(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private Integer getPositiveInteger(JSONObject config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value == null) {
                continue;
            }
            try {
                int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString().trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer firstPositive(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }
}