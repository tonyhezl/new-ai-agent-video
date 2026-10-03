package com.tonyhe.fusion.service.moderation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.service.system.SystemConfigService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 云审核服务（P1）。
 * <p>
 * 支持阿里云内容安全文本审核和结果图审核。
 * 配置项在 afv_system_config：
 * - cloud_moderation_enabled: 是否启用
 * - cloud_moderation_api_key: API Key
 * - cloud_moderation_endpoint: 审核 API 地址
 * <p>
 * 未配置时自动降级为仅本地审核（P0 已实现）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudModerationService {

    private final SystemConfigService systemConfigService;

    private static final String ALIYUN_TEXT_ENDPOINT = "https://green.cn-beijing.aliyuncs.com";
    private static final int TIMEOUT_MS = 10000;

    @Data
    @AllArgsConstructor
    public static class CloudModerationResult {
        private boolean passed;
        private String category;
        private String detail;
    }

    /**
     * 文本审核。
     *
     * @param text 待审核文本
     * @return 审核结果，未配置云审核时返回 pass
     */
    public CloudModerationResult checkText(String text) {
        if (!isEnabled()) {
            return new CloudModerationResult(true, null, "云审核未启用，跳过");
        }

        if (StrUtil.isBlank(text)) {
            return new CloudModerationResult(true, null, "空文本");
        }

        try {
            String apiKey = systemConfigService.getValue("cloud_moderation_api_key");
            String endpoint = StrUtil.blankToDefault(
                    systemConfigService.getValue("cloud_moderation_endpoint"), ALIYUN_TEXT_ENDPOINT);

            JSONObject body = JSONUtil.createObj()
                    .set("content", text);

            String response = HttpUtil.createPost(endpoint + "/green/text/scan")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();

            JSONObject result = JSONUtil.parseObj(response);
            JSONArray data = result.getJSONArray("data");
            if (data != null && !data.isEmpty()) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    String label = item.getStr("label");
                    double confidence = item.getDouble("confidence", 0.0);
                    if (confidence > 0.5 && !"nonLabel".equals(label)) {
                        log.info("Cloud moderation blocked text: label={}, confidence={}", label, confidence);
                        return new CloudModerationResult(false, label, "云审核拦截: " + label);
                    }
                }
            }

            return new CloudModerationResult(true, null, "云审核通过");
        } catch (Exception e) {
            log.warn("Cloud text moderation failed, fallback to pass: {}", e.getMessage());
            return new CloudModerationResult(true, null, "云审核异常，降级通过");
        }
    }

    /**
     * 图片 URL 审核。
     *
     * @param imageUrl 图片 URL
     * @return 审核结果，未配置时返回 pass
     */
    public CloudModerationResult checkImage(String imageUrl) {
        if (!isEnabled()) {
            return new CloudModerationResult(true, null, "云审核未启用，跳过");
        }

        if (StrUtil.isBlank(imageUrl)) {
            return new CloudModerationResult(true, null, "空 URL");
        }

        try {
            String apiKey = systemConfigService.getValue("cloud_moderation_api_key");
            String endpoint = StrUtil.blankToDefault(
                    systemConfigService.getValue("cloud_moderation_endpoint"), ALIYUN_TEXT_ENDPOINT);

            JSONObject body = JSONUtil.createObj()
                    .set("url", imageUrl);

            String response = HttpUtil.createPost(endpoint + "/green/image/scan")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();

            JSONObject result = JSONUtil.parseObj(response);
            JSONArray data = result.getJSONArray("data");
            if (data != null && !data.isEmpty()) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    String label = item.getStr("label");
                    double confidence = item.getDouble("confidence", 0.0);
                    if (confidence > 0.5 && !"nonLabel".equals(label)) {
                        log.info("Cloud moderation blocked image: label={}, confidence={}", label, confidence);
                        return new CloudModerationResult(false, label, "图片云审核拦截: " + label);
                    }
                }
            }

            return new CloudModerationResult(true, null, "图片云审核通过");
        } catch (Exception e) {
            log.warn("Cloud image moderation failed, fallback to pass: {}", e.getMessage());
            return new CloudModerationResult(true, null, "图片云审核异常，降级通过");
        }
    }

    private boolean isEnabled() {
        return systemConfigService.getBooleanValue("cloud_moderation_enabled", false);
    }
}
