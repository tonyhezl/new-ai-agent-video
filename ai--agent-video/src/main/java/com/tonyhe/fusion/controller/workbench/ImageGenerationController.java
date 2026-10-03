package com.tonyhe.fusion.controller.workbench;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.workbench.ImageModelPrice;
import com.tonyhe.fusion.entity.workbench.WorkbenchImageResult;
import com.tonyhe.fusion.entity.workbench.WorkbenchImageTask;
import com.tonyhe.fusion.mapper.ai.AiModelMapper;
import com.tonyhe.fusion.mapper.workbench.ImageModelPriceMapper;
import com.tonyhe.fusion.mapper.workbench.WorkbenchImageResultMapper;
import com.tonyhe.fusion.mapper.workbench.WorkbenchImageTaskMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.moderation.PromptModerationService;
import com.tonyhe.fusion.service.workbench.QuotaService;
import com.tonyhe.fusion.service.workbench.RateLimitService;
import com.tonyhe.fusion.service.workbench.WorkbenchImageExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "生图工作台")
@RestController("workbenchImageGenerationController")
@RequestMapping("/api/image-generation")
@RequiredArgsConstructor
@Slf4j
public class ImageGenerationController {

    private final QuotaService quotaService;
    private final PromptModerationService promptModerationService;
    private final AiModelMapper aiModelMapper;
    private final ImageModelPriceMapper imageModelPriceMapper;
    private final WorkbenchImageTaskMapper taskMapper;
    private final WorkbenchImageResultMapper resultMapper;
    private final WorkbenchImageExecutionService executionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitService rateLimitService;

    private static final String TASK_RATE_LIMIT_PREFIX = "workbench:task:rate:";
    private static final int TASK_RATE_LIMIT_SECONDS = 10;

    @GetMapping("/quota")
    @Operation(summary = "获取当前额度与余额")
    public CommonResult<QuotaRespVO> getQuota(HttpServletRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String guestId = request.getHeader("X-Guest-Id");

        QuotaService.QuotaInfo info = quotaService.getQuotaInfo(userId, guestId);

        QuotaRespVO vo = new QuotaRespVO();
        vo.setGuest(info.isGuest());
        vo.setGuestId(info.getGuestId());
        vo.setFreeQuotaTotal(info.getFreeQuotaTotal());
        vo.setFreeQuotaRemaining(info.getFreeQuotaRemaining());
        vo.setCreditBalance(info.getCreditBalance());
        vo.setMustLogin(info.isMustLogin());
        return success(vo);
    }

    @GetMapping("/models")
    @Operation(summary = "获取可用模型与价格")
    public CommonResult<ModelsRespVO> getModels() {
        List<AiModel> models = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelType, 2)
                        .eq(AiModel::getStatus, 1)
                        .orderByAsc(AiModel::getSort));

        List<ImageModelPrice> allPrices = imageModelPriceMapper.selectList(
                new LambdaQueryWrapper<ImageModelPrice>()
                        .eq(ImageModelPrice::getStatus, 1));

        Map<Long, List<ImageModelPrice>> priceMap = new HashMap<>();
        for (ImageModelPrice p : allPrices) {
            priceMap.computeIfAbsent(p.getModelId(), k -> new ArrayList<>()).add(p);
        }

        List<ModelsRespVO.ModelVO> modelVOs = new ArrayList<>();
        for (AiModel model : models) {
            ModelsRespVO.ModelVO vo = new ModelsRespVO.ModelVO();
            vo.setId(model.getId());
            vo.setCode(model.getCode());
            vo.setName(model.getName());
            vo.setFreeModel(Boolean.TRUE.equals(model.getIsFreeModel()));

            // 从 model.config 解析 supportedAspectRatios 和 supportedSizes 的 key（分辨率档位）
            if (StrUtil.isNotBlank(model.getConfig())) {
                try {
                    cn.hutool.json.JSONObject config = cn.hutool.json.JSONUtil.parseObj(model.getConfig());
                    cn.hutool.json.JSONArray arArray = config.getJSONArray("supportedAspectRatios");
                    if (arArray != null) {
                        vo.setSupportedAspectRatios(arArray.toList(String.class));
                    }
                    cn.hutool.json.JSONObject sizes = config.getJSONObject("supportedSizes");
                    if (sizes != null) {
                        vo.setSupportedQualities(new ArrayList<>(sizes.keySet()));
                    }
                } catch (Exception ignored) {
                }
            }

            List<ImageModelPrice> prices = priceMap.getOrDefault(model.getId(), List.of());
            Map<String, ModelsRespVO.QualityVO> qualityMap = new LinkedHashMap<>();
            for (ImageModelPrice p : prices) {
                ModelsRespVO.QualityVO q = qualityMap.computeIfAbsent(p.getQualityLevel(), ql -> {
                    ModelsRespVO.QualityVO nq = new ModelsRespVO.QualityVO();
                    nq.setLevel(ql);
                    nq.setPrices(new ArrayList<>());
                    return nq;
                });
                ModelsRespVO.PriceVO pvo = new ModelsRespVO.PriceVO();
                pvo.setRatio(p.getAspectRatio());
                pvo.setCreditCost(p.getCreditCost());
                pvo.setDefaultPrice(p.getIsDefault() != null && p.getIsDefault() == 1);
                if (pvo.isDefaultPrice()) {
                    q.setDefaultRatio(p.getAspectRatio());
                }
                q.getPrices().add(pvo);
            }
            vo.setQualities(new ArrayList<>(qualityMap.values()));
            modelVOs.add(vo);
        }

        ModelsRespVO resp = new ModelsRespVO();
        resp.setModels(modelVOs);
        return success(resp);
    }

    @PostMapping("/tasks")
    @Operation(summary = "提交生图任务")
    public CommonResult<TaskRespVO> submitTask(@Valid @RequestBody TaskReqVO reqVO,
                                                HttpServletRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String guestId = request.getHeader("X-Guest-Id");
        String ip = getClientIp(request);

        // 0. 限流：10 秒内只能提交一次
        String rateLimitKey = TASK_RATE_LIMIT_PREFIX + (userId != null ? "u:" + userId : "g:" + guestId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(rateLimitKey))) {
            long ttl = stringRedisTemplate.getExpire(rateLimitKey, TimeUnit.SECONDS);
            throw new BusinessException(429, "操作过于频繁，请" + (ttl > 0 ? ttl : 1) + "秒后重试");
        }
        stringRedisTemplate.opsForValue().set(rateLimitKey, "1", Duration.ofSeconds(TASK_RATE_LIMIT_SECONDS));

        // 0.1 IP 级生图限流
        if (!rateLimitService.checkIpGenerate(ip)) {
            throw new BusinessException(429, "当前 IP 生图请求过多，请稍后再试");
        }

        // 1. 提示词审核
        PromptModerationService.ModerationResult modResult = promptModerationService.check(
                reqVO.getPrompt(), userId, guestId, ip);
        if (modResult.isBlocked()) {
            throw new BusinessException(400, "提示词包含违规内容，请修改后再生成");
        }
        if (!modResult.isPassed()) {
            throw new BusinessException(400, modResult.getErrorMessage());
        }

        // 2. 计费校验
        QuotaService.BillingResult billing = quotaService.resolveBilling(
                userId, guestId, reqVO.getModelId(), reqVO.getQualityLevel(), reqVO.getAspectRatio());

        // 3. 扣费
        if (billing.isUseFreeQuota()) {
            quotaService.deductFreeQuota(userId, guestId);
        } else {
            quotaService.deductCredit(userId, billing.getCreditCost());
        }

        // 4. 创建任务
        String taskNo = generateTaskNo();
        WorkbenchImageTask task = WorkbenchImageTask.builder()
                .taskNo(taskNo)
                .userId(userId)
                .guestId(guestId)
                .modelId(reqVO.getModelId())
                .priceId(billing.getPrice().getId())
                .qualityLevel(reqVO.getQualityLevel())
                .aspectRatio(reqVO.getAspectRatio())
                .creditCost(billing.getCreditCost())
                .isFreeUsed(billing.isUseFreeQuota() ? 1 : 0)
                .billed(billing.isUseFreeQuota() ? 1 : 0) // 免费额度立即标记已扣
                .prompt(reqVO.getPrompt())
                .negativePrompt(reqVO.getNegativePrompt())
                .status(0)
                .source("workbench")
                .clientIp(ip)
                .deletedId(0L)
                .build();
        taskMapper.insert(task);

        // 5. 返回
        TaskRespVO resp = new TaskRespVO();
        resp.setTaskId(task.getId());
        resp.setTaskNo(taskNo);
        resp.setCreditCost(billing.getCreditCost());
        resp.setFreeUsed(billing.isUseFreeQuota());

        // 更新剩余额度
        QuotaService.QuotaInfo info = quotaService.getQuotaInfo(userId, guestId);
        resp.setFreeQuotaRemaining(info.getFreeQuotaRemaining());
        resp.setCreditBalance(info.getCreditBalance());
        resp.setSseEndpoint("/api/image-generation/tasks/" + task.getId() + "/stream");

        log.info("Image task submitted: taskNo={}, userId={}, guestId={}, modelId={}, free={}",
                taskNo, userId, guestId, reqVO.getModelId(), billing.isUseFreeQuota());

        // 6. 异步执行生图
        executionService.executeAsync(task.getId());

        return success(resp);
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务状态")
    public CommonResult<WorkbenchImageTask> getTask(@PathVariable Long taskId, HttpServletRequest request) {
        WorkbenchImageTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        checkTaskOwnership(task, request);
        return success(task);
    }

    @GetMapping("/tasks/{taskId}/results")
    @Operation(summary = "获取任务结果图")
    public CommonResult<List<WorkbenchImageResult>> getResults(@PathVariable Long taskId, HttpServletRequest request) {
        WorkbenchImageTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        checkTaskOwnership(task, request);
        return success(resultMapper.selectList(
                new LambdaQueryWrapper<WorkbenchImageResult>()
                        .eq(WorkbenchImageResult::getTaskId, taskId)
                        .orderByAsc(WorkbenchImageResult::getSort)));
    }

    @GetMapping("/history")
    @Operation(summary = "获取当前用户/游客的生成历史")
    public CommonResult<List<HistoryItemVO>> getHistory(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = SecurityUtils.getCurrentUserId();
        String guestId = request.getHeader("X-Guest-Id");

        LambdaQueryWrapper<WorkbenchImageTask> wrapper = new LambdaQueryWrapper<WorkbenchImageTask>()
                .eq(WorkbenchImageTask::getStatus, 2)
                .orderByDesc(WorkbenchImageTask::getId)
                .last("LIMIT " + ((pageNo - 1) * pageSize) + "," + pageSize);

        if (userId != null) {
            wrapper.eq(WorkbenchImageTask::getUserId, userId);
        } else if (StrUtil.isNotBlank(guestId)) {
            wrapper.eq(WorkbenchImageTask::getGuestId, guestId);
        } else {
            return success(List.of());
        }

        List<WorkbenchImageTask> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) return success(List.of());

        List<Long> taskIds = tasks.stream().map(WorkbenchImageTask::getId).toList();
        Map<Long, String> firstImageMap = new HashMap<>();
        for (WorkbenchImageResult r : resultMapper.selectList(
                new LambdaQueryWrapper<WorkbenchImageResult>()
                        .in(WorkbenchImageResult::getTaskId, taskIds)
                        .eq(WorkbenchImageResult::getIsMain, 1)
                        .orderByAsc(WorkbenchImageResult::getSort))) {
            firstImageMap.putIfAbsent(r.getTaskId(), r.getImageUrl());
        }

        List<HistoryItemVO> items = new ArrayList<>();
        for (WorkbenchImageTask task : tasks) {
            HistoryItemVO vo = new HistoryItemVO();
            vo.setTaskId(task.getId());
            vo.setPrompt(task.getPrompt());
            vo.setNegativePrompt(task.getNegativePrompt());
            vo.setAspectRatio(task.getAspectRatio());
            vo.setQualityLevel(task.getQualityLevel());
            vo.setModelId(task.getModelId());
            vo.setCreateTime(task.getCreateTime());
            vo.setImageUrl(firstImageMap.getOrDefault(task.getId(), null));
            items.add(vo);
        }
        return success(items);
    }

    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "获取任务 SSE 流")
    public Flux<String> streamTask(@PathVariable Long taskId, HttpServletRequest request) {
        WorkbenchImageTask existing = taskMapper.selectById(taskId);
        if (existing == null) {
            return Flux.just("event: error\ndata: {\"message\":\"任务不存在\"}\n\n");
        }
        try {
            checkTaskOwnership(existing, request);
        } catch (BusinessException e) {
            return Flux.just("event: error\ndata: {\"message\":\"" + e.getMessage() + "\"}\n\n");
        }
        return Flux.interval(Duration.ofSeconds(2))
                .map(tick -> {
                    WorkbenchImageTask task = taskMapper.selectById(taskId);
                    if (task == null) {
                        return "event: error\ndata: {\"message\":\"任务不存在\"}\n\n";
                    }
                    int status = task.getStatus();
                    if (status == 2) {
                        List<WorkbenchImageResult> results = resultMapper.selectList(
                                new LambdaQueryWrapper<WorkbenchImageResult>()
                                        .eq(WorkbenchImageResult::getTaskId, taskId)
                                        .orderByAsc(WorkbenchImageResult::getSort));
                        StringBuilder sb = new StringBuilder();
                        sb.append("event: status\ndata: {\"status\":\"GENERATING\",\"progress\":100}\n\n");
                        for (WorkbenchImageResult r : results) {
                            sb.append("event: result\ndata: {\"imageUrl\":\"").append(r.getImageUrl()).append("\"}\n\n");
                        }
                        sb.append("event: done\ndata: {\"taskId\":").append(taskId).append(",\"status\":\"SUCCESS\"}\n\n");
                        return sb.toString();
                    } else if (status == 3) {
                        String errMsg = task.getErrorMsg() != null ? task.getErrorMsg() : "生成失败";
                        return "event: error\ndata: {\"taskId\":" + taskId + ",\"status\":\"FAILED\",\"message\":\"" + errMsg + "\"}\n\n";
                    } else {
                        int progress = status == 1 ? 50 : 10;
                        return "event: status\ndata: {\"status\":\"" + (status == 1 ? "GENERATING" : "QUEUED") + "\",\"progress\":" + progress + "}\n\n";
                    }
                })
                .takeUntil(s -> s.contains("event: done") || s.contains("event: error"))
                .timeout(Duration.ofMinutes(10))
                .onErrorResume(e -> {
                    log.warn("SSE stream error: taskId={}", taskId, e);
                    return Flux.just("event: error\ndata: {\"message\":\"连接超时\"}\n\n");
                });
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip)) ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip)) ip = request.getRemoteAddr();
        return ip;
    }

    private String generateTaskNo() {
        return "IMG-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private void checkTaskOwnership(WorkbenchImageTask task, HttpServletRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            if (!userId.equals(task.getUserId())) {
                throw new BusinessException(403, "无权访问该任务");
            }
            return;
        }
        String guestId = request.getHeader("X-Guest-Id");
        if (StrUtil.isBlank(guestId) || !guestId.equals(task.getGuestId())) {
            throw new BusinessException(403, "无权访问该任务");
        }
    }

    // ===== VOs =====

    @Data
    public static class QuotaRespVO {
        private boolean guest;
        private String guestId;
        private int freeQuotaTotal;
        private int freeQuotaRemaining;
        private int creditBalance;
        private boolean mustLogin;
    }

    @Data
    public static class ModelsRespVO {
        private List<ModelVO> models;

        @Data
        public static class ModelVO {
            private Long id;
            private String code;
            private String name;
            private boolean freeModel;
            private List<String> supportedAspectRatios;
            private List<String> supportedQualities;
            private List<QualityVO> qualities;
        }

        @Data
        public static class QualityVO {
            private String level;
            private String defaultRatio;
            private List<PriceVO> prices;
        }

        @Data
        public static class PriceVO {
            private String ratio;
            private int creditCost;
            private boolean defaultPrice;
        }
    }

    @Data
    public static class TaskReqVO {
        @NotNull(message = "模型 ID 不能为空")
        private Long modelId;

        private String qualityLevel = "standard";
        private String aspectRatio = "1:1";

        @NotBlank(message = "提示词不能为空")
        private String prompt;

        private String negativePrompt;
    }

    @Data
    public static class TaskRespVO {
        private Long taskId;
        private String taskNo;
        private int creditCost;
        private boolean freeUsed;
        private int freeQuotaRemaining;
        private int creditBalance;
        private String sseEndpoint;
    }

    @Data
    public static class HistoryItemVO {
        private Long taskId;
        private String prompt;
        private String negativePrompt;
        private String aspectRatio;
        private String qualityLevel;
        private Long modelId;
        private String imageUrl;
        private LocalDateTime createTime;
    }
}
