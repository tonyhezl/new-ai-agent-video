package com.tonyhe.fusion.service.generation.consumer;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.infrastructure.queue.RedisTaskQueue;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ApiConfigService;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.TaskOwnershipSnapshotService;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategy;
import com.tonyhe.fusion.service.generation.strategy.AudioGenerationStrategyRouter;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AI 配音任务消费器
 * <p>
 * 复用 Redis 队列模式，按 TTS 模型分队列消费。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationConsumer {

    private static final String BASE_QUEUE_NAME = "audio_generation";
    private static final String MODEL_QUEUE_PREFIX = BASE_QUEUE_NAME + ":model:";
    private static final int MODEL_TYPE_TTS = 4;

    private final RedisTaskQueue taskQueue;
    private final AudioGenerationService audioGenerationService;
    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final AudioGenerationStrategyRouter audioStrategyRouter;
    private final StoryboardService storyboardService;
    private final TaskOwnershipSnapshotService taskOwnershipSnapshotService;

    private final AtomicInteger workerThreadCounter = new AtomicInteger(1);
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "audio-generation-worker-" + workerThreadCounter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 提交配音任务到队列
     */
    public String submitTask(AudioTask task) {
        task.setTeamId(taskOwnershipSnapshotService.resolveTeamId(
                task.getUserId(), task.getProjectId(), task.getTeamId()));
        AiModel queueModel = resolveQueueModel(task.getModelId());
        if (queueModel == null) {
            throw new BusinessException("没有可用的 TTS 模型");
        }
        task.setModelId(queueModel.getId());
        task.setModelCode(queueModel.getCode());

        String queueName = resolveQueueName(task.getModelId());
        String taskId = IdUtil.fastSimpleUUID();
        task.setTaskId(taskId);
        task.setStatus(0);
        audioGenerationService.create(task);

        refreshQueueMaxConcurrent(queueName, task.getModelId());
        taskQueue.push(queueName, taskId);
        log.info("[AudioConsumer] 任务入队: taskId={}, queue={}, modelId={}", taskId, queueName, task.getModelId());
        return taskId;
    }

    /**
     * 提交配音任务并同步等待完成
     *
     * @param task      配音任务
     * @param timeoutMs 最大等待时间（毫秒）
     * @return 完成的 AudioTask
     */
    public AudioTask submitAndWait(AudioTask task, long timeoutMs) throws InterruptedException {
        String taskId = submitTask(task);

        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollInterval = 1500L;

        log.info("[AudioConsumer] 同步等待配音任务完成: taskId={}, timeout={}ms", taskId, timeoutMs);

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollInterval);

            AudioTask current = audioGenerationService.getByTaskId(taskId);
            if (current == null) {
                continue;
            }
            switch (current.getStatus()) {
                case 2: // 已完成
                    log.info("[AudioConsumer] 配音任务已完成: taskId={}, audioUrl={}", taskId, current.getAudioUrl());
                    return current;
                case 3: // 失败
                    String errorMsg = current.getErrorMessage() != null ? current.getErrorMessage() : "未知错误";
                    throw new RuntimeException("配音任务失败: " + errorMsg);
                default:
                    // 0-排队中 1-处理中，继续等待
                    break;
            }
        }

        audioGenerationService.updateStatus(task.getId(), 3, "同步等待超时");
        throw new RuntimeException("配音任务排队超时（等待 " + (timeoutMs / 1000) + " 秒），请稍后重试");
    }

    @Scheduled(fixedDelay = 5000)
    public void consume() {
        for (String queueName : collectQueueNamesToConsume()) {
            drainQueue(queueName);
        }
    }

    private void drainQueue(String queueName) {
        while (true) {
            String taskId = taskQueue.acquireAndPop(queueName, 1);
            if (taskId == null) {
                return;
            }
            dispatchTask(queueName, taskId);
        }
    }

    private void dispatchTask(String queueName, String taskId) {
        workerExecutor.execute(() -> {
            try {
                taskQueue.markRunning(queueName, taskId, 10);
                processTask(queueName, taskId);
            } catch (Exception e) {
                log.error("[AudioConsumer] 任务处理失败: taskId={}", taskId, e);
            } finally {
                taskQueue.markComplete(queueName, taskId);
                taskQueue.release(queueName);
            }
        });
    }

    private void processTask(String queueName, String taskId) {
        AudioTask task;
        try {
            task = audioGenerationService.getByTaskId(taskId);
        } catch (Exception e) {
            log.error("[AudioConsumer] 任务不存在: taskId={}", taskId);
            return;
        }

        refreshQueueMaxConcurrent(queueName, task.getModelId());
        try {
            taskOwnershipSnapshotService.assertParentActive(task.getProjectId(), task.getTeamId());
        } catch (BusinessException e) {
            audioGenerationService.updateStatus(task.getId(), 3, e.getMessage());
            return;
        }
        audioGenerationService.updateStatus(task.getId(), 1, null);

        AiModel model = null;
        if (task.getModelId() != null) {
            try {
                model = aiModelService.getById(task.getModelId());
            } catch (Exception e) {
                log.warn("[AudioConsumer] 模型配置获取失败: modelId={}", task.getModelId());
            }
        }
        if (model == null) {
            audioGenerationService.updateStatus(task.getId(), 3, "TTS 模型不存在或已禁用");
            return;
        }

        try {
            ApiConfig apiConfig = resolveApiConfig(model);
            AudioGenerationStrategy strategy = audioStrategyRouter.resolve(model);

            // 注入鉴权信息到 model config（策略内部读取 _auth 节点）
            AiModel modelWithAuth = injectAuthInfo(model, apiConfig);

            // Agent Plan 用 /plan/ 路径的 TTS endpoint，可直接用 Ark API Key 鉴权
            String baseUrl = resolveTtsEndpoint(apiConfig);
            String apiKey = apiConfig.getApiKey();

            AudioGenerationStrategy.AudioSynthesizeResult result =
                    strategy.synthesize(task, modelWithAuth, apiKey, baseUrl);

            taskOwnershipSnapshotService.assertParentActive(task.getProjectId(), task.getTeamId());

            task.setAudioUrl(result.audioUrl());
            task.setDurationMs(result.durationMs());
            task.setStatus(2);
            task.setErrorMessage(null);
            audioGenerationService.update(task);

            // 检查该镜头所有句是否完成 → 更新 StoryboardItem.audioStatus
            checkItemCompletion(task.getStoryboardItemId());

            log.info("[AudioConsumer] 任务完成: taskId={}, audioUrl={}", taskId, result.audioUrl());
        } catch (Exception e) {
            log.error("[AudioConsumer] 任务执行失败: taskId={}", taskId, e);
            audioGenerationService.updateStatus(task.getId(), 3, e.getMessage());
            // 仍需检查镜头整体状态
            checkItemCompletion(task.getStoryboardItemId());
        }
    }

    /**
     * 检查某个 StoryboardItem 的所有 AudioTask 是否都已完成，
     * 若全部成功则更新 StoryboardItem.audioStatus=2。
     * <p>
     * 重生成会创建新任务（parentTaskId 指向旧任务），只检查每个 seq 的最新任务。
     */
    private void checkItemCompletion(Long storyboardItemId) {
        if (storyboardItemId == null) {
            return;
        }
        try {
            List<AudioTask> itemTasks = audioGenerationService.listByItemId(storyboardItemId);
            if (itemTasks.isEmpty()) {
                return;
            }

            // 按 seq 分组，每组只取 id 最大的（即最新的）任务
            Map<Integer, AudioTask> latestBySeq = itemTasks.stream()
                    .collect(Collectors.toMap(
                            AudioTask::getSeq,
                            t -> t,
                            (a, b) -> a.getId() > b.getId() ? a : b));

            List<AudioTask> latestTasks = new ArrayList<>(latestBySeq.values());

            boolean allSuccess = latestTasks.stream().allMatch(t -> t.getStatus() != null && t.getStatus() == 2);
            boolean anyFailed = latestTasks.stream().anyMatch(t -> t.getStatus() != null && t.getStatus() == 3);
            boolean anyPending = latestTasks.stream().anyMatch(t -> t.getStatus() != null
                    && (t.getStatus() == 0 || t.getStatus() == 1));

            if (allSuccess) {
                StoryboardItem item = storyboardService.getItemById(storyboardItemId);
                item.setAudioStatus(2);
                // 如果只有一句，直接把音频 URL 设为 StoryboardItem.audioUrl
                if (latestTasks.size() == 1) {
                    item.setAudioUrl(latestTasks.get(0).getAudioUrl());
                }
                storyboardService.updateItem(item);
                log.info("[AudioConsumer] 镜头配音全部完成: itemId={}", storyboardItemId);
            } else if (anyFailed && !anyPending) {
                StoryboardItem item = storyboardService.getItemById(storyboardItemId);
                item.setAudioStatus(3);
                storyboardService.updateItem(item);
            }
        } catch (Exception e) {
            log.warn("[AudioConsumer] 检查镜头完成状态失败: itemId={}", storyboardItemId, e);
        }
    }

    private AiModel injectAuthInfo(AiModel model, ApiConfig apiConfig) {
        // 在 model.config 中追加 _auth 节点，供策略读取
        String configJson = model.getConfig();
        JSONObject config;
        if (StrUtil.isNotBlank(configJson)) {
            try {
                config = JSONUtil.parseObj(configJson);
            } catch (Exception e) {
                config = new JSONObject();
            }
        } else {
            config = new JSONObject();
        }
        JSONObject auth = new JSONObject();
        auth.set("appId", apiConfig.getAppId());
        auth.set("appSecret", apiConfig.getAppSecret());
        auth.set("apiKey", apiConfig.getApiKey());
        config.set("_auth", auth);

        AiModel copy = new AiModel();
        copy.setId(model.getId());
        copy.setCode(model.getCode());
        copy.setName(model.getName());
        copy.setModelType(model.getModelType());
        copy.setModelProtocol(model.getModelProtocol());
        copy.setApiConfigId(model.getApiConfigId());
        copy.setConfig(config.toString());
        return copy;
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model != null && model.getApiConfigId() != null) {
            ApiConfig config = apiConfigService.getById(model.getApiConfigId());
            if (config != null) {
                return config;
            }
        }
        throw new BusinessException("未找到 TTS API 配置，请在系统设置中配置火山引擎语音 API Key");
    }

    /**
     * 根据 API 配置平台解析 TTS endpoint。
     * <p>
     * - volcengine_plan：使用 Agent Plan 路径 /api/v3/plan/tts/unidirectional，可直接用 Ark API Key
     * - volcengine / 其他：使用标准路径 /api/v3/tts/unidirectional，需语音控制台 API Key
     * - 如果 apiConfig.apiUrl 已显式配置，优先使用
     */
    private String resolveTtsEndpoint(ApiConfig apiConfig) {
        if (StrUtil.isNotBlank(apiConfig.getApiUrl())) {
            return apiConfig.getApiUrl();
        }
        String platform = apiConfig.getPlatform();
        if ("volcengine_plan".equalsIgnoreCase(platform)) {
            return "https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional";
        }
        return null; // 策略内部使用默认 endpoint
    }

    private List<String> collectQueueNamesToConsume() {
        List<String> queueNames = new ArrayList<>(taskQueue.listRegisteredQueuesByPrefix(MODEL_QUEUE_PREFIX));
        queueNames.sort(String::compareTo);
        return queueNames;
    }

    private void refreshQueueMaxConcurrent(String queueName, Long modelId) {
        int maxConcurrent = resolveQueueMaxConcurrent(modelId);
        taskQueue.setMaxConcurrent(queueName, maxConcurrent);
    }

    private int resolveQueueMaxConcurrent(Long modelId) {
        AiModel model = resolveQueueModel(modelId);
        Integer configured = model != null ? model.getMaxConcurrency() : null;
        return configured != null && configured > 0 ? configured : 3;
    }

    private AiModel resolveQueueModel(Long modelId) {
        if (modelId != null) {
            try {
                AiModel model = aiModelService.getById(modelId);
                if (model != null && model.getStatus() != null && model.getStatus() == 1) {
                    return model;
                }
            } catch (Exception e) {
                log.warn("[AudioConsumer] 读取 TTS 模型配置失败: modelId={}", modelId, e);
            }
        }
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_TTS);
        if (defaultModel != null) {
            return defaultModel;
        }
        List<AiModel> ttsModels = aiModelService.getListByType(MODEL_TYPE_TTS);
        return ttsModels.isEmpty() ? null : ttsModels.get(0);
    }

    private String resolveQueueName(Long modelId) {
        if (modelId == null) {
            throw new BusinessException("TTS 任务缺少 modelId，无法路由到模型队列");
        }
        return MODEL_QUEUE_PREFIX + modelId;
    }

    @PreDestroy
    public void shutdownWorkerExecutor() {
        workerExecutor.shutdownNow();
    }
}
