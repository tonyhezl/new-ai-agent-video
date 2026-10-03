package com.tonyhe.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.googleflow.GoogleFlowReverseApiClient;
import com.tonyhe.fusion.service.ai.googleflow.GoogleFlowReverseApiSupport;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.strategy.ImageGenerationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Flow Reverse API 图片生成策略。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleFlowReverseApiImageStrategy implements ImageGenerationStrategy {

    private final ImageGenerationService imageGenerationService;
    private final AiModelService aiModelService;
    private final GoogleFlowReverseApiClient client;

    @Override
    public String getName() {
        return GoogleFlowReverseApiSupport.PLATFORM;
    }

    @Override
    public List<String> generate(String prompt, String modelCode, int width, int height, int count,
                                 List<String> imageUrls, ApiConfig apiConfig) {
        String aspectRatio = GoogleFlowReverseApiSupport.deriveImageAspectRatio(width, height);
        String resolution = GoogleFlowReverseApiSupport.deriveImageResolution(width, height);
        String actualModelCode = GoogleFlowReverseApiSupport.resolveImageModelCode(modelCode, aspectRatio, resolution);

        List<String> urls = new ArrayList<>();
        int actualCount = Math.max(count, 1);
        for (int i = 0; i < actualCount; i++) {
            GoogleFlowReverseApiClient.CompletionResult completion = client.generate(prompt, actualModelCode, imageUrls, apiConfig);
            String imageUrl = GoogleFlowReverseApiSupport.extractImageUrl(completion.content());
            if (StrUtil.isBlank(imageUrl)) {
                throw new BusinessException("GoogleFlowReverseApi 未返回图片地址");
            }
            urls.add(imageUrl);
        }
        return urls;
    }

    @Override
    public String submit(ImageTask task, ApiConfig apiConfig) {
        AiModel model = resolveModel(task);
        GoogleFlowReverseApiSupport.ResolvedImageRequest request = GoogleFlowReverseApiSupport.resolveImageRequest(model, task);
        List<String> refImageUrls = parseRefImageUrls(task.getRefImageUrls());
        List<ImageItem> items = imageGenerationService.listItems(task.getId());
        String firstRequestId = null;
        int successCount = 0;

        for (ImageItem item : items) {
            GoogleFlowReverseApiClient.CompletionResult completion = client.generate(
                    task.getPrompt(),
                    request.actualModelCode(),
                    refImageUrls,
                    apiConfig
            );
            String imageUrl = GoogleFlowReverseApiSupport.extractImageUrl(completion.content());
            if (StrUtil.isBlank(imageUrl)) {
                throw new BusinessException("GoogleFlowReverseApi 未返回图片地址");
            }

            if (firstRequestId == null && StrUtil.isNotBlank(completion.requestId())) {
                firstRequestId = completion.requestId();
            }

            item.setPlatformTaskId(StrUtil.blankToDefault(completion.requestId(), task.getTaskId()));
            item.setImageUrl(imageUrl);
            item.setStatus(1);
            imageGenerationService.updateItem(item);
            successCount++;
        }

        task.setSuccessCount(successCount);
        imageGenerationService.update(task);

        log.info("[GoogleFlowReverseApi][Image] 任务完成: taskId={}, model={}, aspectRatio={}, resolution={}, count={}",
                task.getTaskId(), request.actualModelCode(), request.aspectRatio(), request.resolution(), successCount);
        return StrUtil.blankToDefault(firstRequestId, task.getTaskId());
    }

    @Override
    public void poll(String platformTaskId, ImageTask task, ApiConfig apiConfig) {
        // Flow2API 在 submit 阶段已同步返回最终结果
    }

    private AiModel resolveModel(ImageTask task) {
        if (task.getModelId() == null) {
            throw new BusinessException("GoogleFlowReverseApi 图片任务缺少 modelId");
        }
        AiModel model = aiModelService.getById(task.getModelId());
        if (model == null || StrUtil.isBlank(model.getCode())) {
            throw new BusinessException("GoogleFlowReverseApi 图片模型不存在或未配置 code");
        }
        return model;
    }
}