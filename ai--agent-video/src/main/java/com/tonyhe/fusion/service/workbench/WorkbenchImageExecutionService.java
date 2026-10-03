package com.tonyhe.fusion.service.workbench;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.workbench.WorkbenchImageResult;
import com.tonyhe.fusion.entity.workbench.WorkbenchImageTask;
import com.tonyhe.fusion.mapper.workbench.WorkbenchImageResultMapper;
import com.tonyhe.fusion.mapper.workbench.WorkbenchImageTaskMapper;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ApiConfigService;
import com.tonyhe.fusion.service.generation.strategy.ImageGenerationStrategy;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import com.tonyhe.fusion.service.workbench.RewardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作台生图执行服务。
 * <p>
 * 复用现有 ImageGenerationStrategy 的纯 API 调用能力（generate 方法），
 * 但使用独立的 afv_workbench_image_task/result 表，不经过 Redis 队列和 ImageGenerationConsumer。
 * <p>
 * 调用链：ImageGenerationController.submitTask → 建任务 → 本服务异步执行 → 写结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchImageExecutionService {

    private final WorkbenchImageTaskMapper taskMapper;
    private final WorkbenchImageResultMapper resultMapper;
    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final MediaStorageService mediaStorageService;
    private final RewardService rewardService;
    private final List<ImageGenerationStrategy> strategies;

    private Map<String, ImageGenerationStrategy> strategyMap;

    private Map<String, ImageGenerationStrategy> getStrategyMap() {
        if (strategyMap == null) {
            strategyMap = strategies.stream()
                    .collect(Collectors.toMap(ImageGenerationStrategy::getName, s -> s));
        }
        return strategyMap;
    }

    /**
     * 异步执行生图任务。
     * <p>
     * 在独立线程中调用上游模型，完成后更新任务状态和结果。
     *
     * @param taskId 任务 ID
     */
    public void executeAsync(Long taskId) {
        new Thread(() -> {
            try {
                execute(taskId);
            } catch (Exception e) {
                log.error("[WorkbenchImage] Async execution failed: taskId={}", taskId, e);
                markFailed(taskId, e.getMessage());
            }
        }, "workbench-image-exec-" + taskId).start();
    }

    /**
     * 同步执行生图任务（含上游调用 + 持久化 + 状态更新）。
     */
    @Transactional
    public void execute(Long taskId) {
        WorkbenchImageTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在: " + taskId);
        }

        // 更新为处理中
        task.setStatus(1);
        taskMapper.updateById(task);

        try {
            // 解析模型
            AiModel model = aiModelService.getById(task.getModelId());
            if (model == null) {
                throw new BusinessException("模型不存在: " + task.getModelId());
            }

            // 解析 API 配置
            ApiConfig apiConfig = resolveApiConfig(model);
            if (apiConfig == null) {
                throw new BusinessException("找不到模型对应的 API 配置");
            }

            // 选择策略
            ImageGenerationStrategy strategy = resolveStrategy(model, apiConfig);
            if (strategy == null) {
                throw new BusinessException("找不到匹配的生图策略");
            }

            // 解析尺寸
            int[] size = resolveSize(task, model);

            log.info("[WorkbenchImage] 开始调用上游: taskId={}, model={}, strategy={}, size={}x{}",
                    taskId, model.getCode(), strategy.getName(), size[0], size[1]);

            // 调用上游生图 API（传入 AiModel 以读取数据库配置，指定存储路径为 workbench-images）
            List<String> imageUrls = strategy.generate(
                    task.getPrompt(),
                    model.getCode(),
                    size[0],
                    size[1],
                    1,
                    null,
                    apiConfig,
                    model,
                    "workbench-images");

            if (imageUrls == null || imageUrls.isEmpty()) {
                throw new BusinessException("上游未返回图片");
            }

            // 持久化图片到本地/OSS
            for (int i = 0; i < imageUrls.size(); i++) {
                String remoteUrl = imageUrls.get(i);
                String persistedUrl;
                try {
                    persistedUrl = mediaStorageService.downloadAndStore(remoteUrl, "workbench-images", null);
                } catch (Exception e) {
                    log.warn("[WorkbenchImage] 图片持久化失败，保留原始 URL: {}", e.getMessage());
                    persistedUrl = remoteUrl;
                }

                WorkbenchImageResult result = WorkbenchImageResult.builder()
                        .taskId(taskId)
                        .imageUrl(persistedUrl)
                        .width(size[0])
                        .height(size[1])
                        .isMain(i == 0 ? 1 : 0)
                        .sort(i)
                        .build();
                resultMapper.insert(result);
            }

            // 标记完成
            task.setStatus(2);
            task.setBilled(1);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[WorkbenchImage] 任务完成: taskId={}, imageCount={}", taskId, imageUrls.size());

        } catch (Exception e) {
            log.error("[WorkbenchImage] 任务执行失败: taskId={}", taskId, e);
            markFailed(taskId, e.getMessage());

            // 失败退点
            if (task.getCreditCost() > 0 && task.getUserId() != null && task.getBilled() == 1) {
                rewardService.refundFailedTask(task.getUserId(), task.getCreditCost(), taskId);
                task.setBilled(0);
                taskMapper.updateById(task);
                log.info("[WorkbenchImage] 失败退点: taskId={}, userId={}, credit={}",
                        taskId, task.getUserId(), task.getCreditCost());
            }
        }
    }

    /**
     * 标记任务失败。
     */
    public void markFailed(Long taskId, String errorMsg) {
        WorkbenchImageTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        task.setStatus(3);
        task.setErrorMsg(StrUtil.sub(errorMsg, 0, 512));
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model.getApiConfigId() != null) {
            return apiConfigService.getById(model.getApiConfigId());
        }
        // 回退：找同平台的第一个启用配置
        List<ApiConfig> configs = apiConfigService.getEnabledList();
        if (configs.isEmpty()) return null;
        return configs.get(0);
    }

    private ImageGenerationStrategy resolveStrategy(AiModel model, ApiConfig apiConfig) {
        Map<String, ImageGenerationStrategy> map = getStrategyMap();
        if (map.isEmpty()) return null;

        // 优先按模型 code 匹配
        ImageGenerationStrategy strategy = map.get(model.getCode());
        if (strategy != null) return strategy;

        // 按 API 配置的 platform 匹配
        if (apiConfig != null && apiConfig.getPlatform() != null) {
            String platform = apiConfig.getPlatform();
            strategy = map.get(platform);
            if (strategy != null) return strategy;

            // 常见别名
            String normalized = "openai".equalsIgnoreCase(platform) ? "openai_compatible" : platform.toLowerCase(Locale.ROOT);
            strategy = map.get(normalized);
            if (strategy != null) return strategy;

            strategy = switch (normalized) {
                case "openai_compatible" -> map.get("openai");
                case "newapi" -> map.get("openai_compatible");
                case "volcengine_plan" -> map.get("volcengine");
                default -> null;
            };
            if (strategy != null) return strategy;
        }

        // 兜底：第一个策略
        return map.values().iterator().next();
    }

    private int[] resolveSize(WorkbenchImageTask task, AiModel model) {
        String ratio = task.getAspectRatio();
        String quality = task.getQualityLevel();

        // 优先从模型 config 的 supportedSizes 中查找精确像素尺寸
        if (model != null && StrUtil.isNotBlank(model.getConfig())) {
            try {
                JSONObject config = JSONUtil.parseObj(model.getConfig());
                JSONObject supportedSizes = config.getJSONObject("supportedSizes");
                if (supportedSizes != null) {
                    String qualityKey = normalizeQualityKey(quality);
                    JSONObject tier = supportedSizes.getJSONObject(qualityKey);
                    if (tier != null) {
                        String sizeStr = tier.getStr(ratio);
                        if (StrUtil.isNotBlank(sizeStr)) {
                            String[] parts = sizeStr.split("x");
                            if (parts.length == 2) {
                                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 回退：从模型 config 读取默认尺寸
        int baseWidth = 1024;
        int baseHeight = 1024;
        if (model != null && StrUtil.isNotBlank(model.getConfig())) {
            try {
                JSONObject config = JSONUtil.parseObj(model.getConfig());
                if (config.containsKey("defaultWidth")) baseWidth = config.getInt("defaultWidth", 1024);
                if (config.containsKey("defaultHeight")) baseHeight = config.getInt("defaultHeight", 1024);
            } catch (Exception ignored) {
            }
        }

        // 按比例调整
        int[] wh = applyAspectRatio(baseWidth, baseHeight, ratio);

        // 按质量/分辨率等级调整分辨率
        if ("fast".equalsIgnoreCase(quality)) {
            wh[0] = (int) (wh[0] * 0.75);
            wh[1] = (int) (wh[1] * 0.75);
        } else if ("2k".equalsIgnoreCase(quality) || "ultra".equalsIgnoreCase(quality)) {
            wh[0] = wh[0] * 2;
            wh[1] = wh[1] * 2;
        } else if ("3k".equalsIgnoreCase(quality)) {
            wh[0] = wh[0] * 3;
            wh[1] = wh[1] * 3;
        } else if ("4k".equalsIgnoreCase(quality)) {
            wh[0] = wh[0] * 4;
            wh[1] = wh[1] * 4;
        }

        return wh;
    }

    private String normalizeQualityKey(String quality) {
        if (quality == null) return "1K";
        String q = quality.trim().toUpperCase(Locale.ROOT);
        if ("STANDARD".equals(q) || "FAST".equals(q)) return "1K";
        if ("ULTRA".equals(q)) return "2K";
        return q;
    }

    private int[] applyAspectRatio(int baseW, int baseH, String ratio) {
        if (StrUtil.isBlank(ratio) || "1:1".equals(ratio)) {
            int size = Math.min(baseW, baseH);
            return new int[]{size, size};
        }
        String[] parts = ratio.split(":");
        if (parts.length != 2) {
            int size = Math.min(baseW, baseH);
            return new int[]{size, size};
        }
        try {
            int rw = Integer.parseInt(parts[0]);
            int rh = Integer.parseInt(parts[1]);
            int base = Math.min(baseW, baseH);
            int w = base;
            int h = base;
            if (rw > rh) {
                h = (int) ((double) base * rh / rw);
            } else if (rh > rw) {
                w = (int) ((double) base * rw / rh);
            }
            return new int[]{w, h};
        } catch (NumberFormatException e) {
            int size = Math.min(baseW, baseH);
            return new int[]{size, size};
        }
    }
}
