package com.tonyhe.fusion.service.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.service.ai.ModelPresetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生成模型能力解析与输入校验。
 * <p>
 * 目标：在真正发起平台请求前，就能根据当前模型能力给出稳定、可解释的错误，避免静默忽略或将错误延后到平台侧。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationModelCapabilityService {

    private final AiModelMetadataResolver aiModelMetadataResolver;
    private final ModelPresetService modelPresetService;
    private final CapabilityRuleService capabilityRuleService;

    public ImageModelCapability resolveImageCapability(AiModel model) {
        return resolveImageCapability(model, aiModelMetadataResolver.resolvePlatform(model));
    }

    public ImageModelCapability resolveImageCapability(AiModel model, String platform) {
        AiModelMetadata metadata = aiModelMetadataResolver.resolve(model, platform);
        ImageModelCapability inferred = inferImageCapability(model, metadata);
        JSONObject config = getMergedModelConfig(model);

        Boolean supportsReferenceImages = getBoolean(config,
                "supportReferenceImages", "supportsReferenceImages", "supportRefImages", "supportImageReferences");
        Integer minReferenceImages = getInteger(config, "minReferenceImages", "minRefImages");
        Integer maxReferenceImages = getInteger(config, "maxReferenceImages", "maxRefImages");

        boolean finalSupportsReferenceImages = supportsReferenceImages != null
                ? supportsReferenceImages : inferred.supportsReferenceImages();
        int finalMinReferenceImages = minReferenceImages != null
                ? Math.max(minReferenceImages, 0) : inferred.minReferenceImages();
        Integer finalMaxReferenceImages = mergeNullableMax(maxReferenceImages, inferred.maxReferenceImages());

        if (!finalSupportsReferenceImages) {
            finalMinReferenceImages = 0;
            finalMaxReferenceImages = 0;
        } else if (finalMaxReferenceImages == null || finalMaxReferenceImages <= 0) {
            finalMaxReferenceImages = 10;
        }

        return new ImageModelCapability(finalSupportsReferenceImages, finalMinReferenceImages, finalMaxReferenceImages);
    }

    public VideoModelCapability resolveVideoCapability(AiModel model) {
        return resolveVideoCapability(model, aiModelMetadataResolver.resolvePlatform(model));
    }

    public VideoModelCapability resolveVideoCapability(AiModel model, String platform) {
        AiModelMetadata metadata = aiModelMetadataResolver.resolve(model, platform);
        VideoModelCapability inferred = inferVideoCapability(model, metadata);
        JSONObject config = getMergedModelConfig(model);

        Boolean supportsFirstFrame = getBoolean(config,
                "supportFirstFrame", "supportsFirstFrame", "allowFirstFrame", "supportStartImage");
        Boolean supportsLastFrame = getBoolean(config,
                "supportLastFrame", "supportsLastFrame", "allowLastFrame", "supportEndImage");
        Boolean supportsReferenceImages = getBoolean(config,
                "supportReferenceImages", "supportsReferenceImages", "supportRefImages", "supportImageReferences");
        Boolean supportsReferenceVideos = getBoolean(config,
                "supportReferenceVideos", "supportsReferenceVideos", "supportRefVideos", "supportVideoReferences");
        Boolean supportsReferenceAudios = getBoolean(config,
                "supportReferenceAudios", "supportsReferenceAudios", "supportRefAudios", "supportAudioReferences");
        Boolean supportsFirstFrameWithReference = getBoolean(config,
                "supportFirstFrameWithReference", "supportsFirstFrameWithReference");

        Integer minImageInputs = getInteger(config, "minImageInputs", "minImages");
        Integer maxImageInputs = getInteger(config, "maxImageInputs", "maxImages");
        Integer maxReferenceImages = getInteger(config, "maxReferenceImages", "maxRefImages");
        Integer maxReferenceVideos = getInteger(config, "maxReferenceVideos", "maxRefVideos");
        Integer maxReferenceAudios = getInteger(config, "maxReferenceAudios", "maxRefAudios");

        boolean finalSupportsFirstFrame = supportsFirstFrame != null
                ? supportsFirstFrame : inferred.supportsFirstFrame();
        boolean finalSupportsLastFrame = supportsLastFrame != null
                ? supportsLastFrame : inferred.supportsLastFrame();
        boolean finalSupportsReferenceImages = supportsReferenceImages != null
                ? supportsReferenceImages : inferred.supportsReferenceImages();
        boolean finalSupportsReferenceVideos = supportsReferenceVideos != null
                ? supportsReferenceVideos : inferred.supportsReferenceVideos();
        boolean finalSupportsReferenceAudios = supportsReferenceAudios != null
                ? supportsReferenceAudios : inferred.supportsReferenceAudios();
        boolean finalSupportsFirstFrameWithReference = supportsFirstFrameWithReference != null
                ? supportsFirstFrameWithReference : inferred.supportsFirstFrameWithReference();

        int finalMinImageInputs = minImageInputs != null ? Math.max(minImageInputs, 0) : inferred.minImageInputs();
        Integer finalMaxImageInputs = mergeNullableMax(maxImageInputs, inferred.maxImageInputs());
        Integer finalMaxReferenceImages = mergeNullableMax(maxReferenceImages, inferred.maxReferenceImages());
        Integer finalMaxReferenceVideos = mergeNullableMax(maxReferenceVideos, inferred.maxReferenceVideos());
        Integer finalMaxReferenceAudios = mergeNullableMax(maxReferenceAudios, inferred.maxReferenceAudios());

        if (!finalSupportsReferenceImages) {
            finalMaxReferenceImages = 0;
        }
        if (!finalSupportsReferenceVideos) {
            finalMaxReferenceVideos = 0;
        }
        if (!finalSupportsReferenceAudios) {
            finalMaxReferenceAudios = 0;
        }

        log.info("[CapabilityResolve] 视频模型最终能力: model={}, code={}, platform={}, "
                        + "firstFrame={}, lastFrame={}, refImages={}, refVideos={}, refAudios={}, firstFrameWithRef={}, "
                        + "minImages={}, maxImages={}, maxRefImages={}, maxRefVideos={}, maxRefAudios={}",
                modelLabel(model), model != null ? model.getCode() : "null", platform,
                finalSupportsFirstFrame, finalSupportsLastFrame, finalSupportsReferenceImages,
                finalSupportsReferenceVideos, finalSupportsReferenceAudios, finalSupportsFirstFrameWithReference,
                finalMinImageInputs, finalMaxImageInputs, finalMaxReferenceImages,
                finalMaxReferenceVideos, finalMaxReferenceAudios);

        return new VideoModelCapability(
                finalSupportsFirstFrame,
                finalSupportsLastFrame,
                finalSupportsReferenceImages,
                finalSupportsReferenceVideos,
                finalSupportsReferenceAudios,
                finalSupportsFirstFrameWithReference,
                finalMinImageInputs,
                finalMaxImageInputs,
                finalMaxReferenceImages,
                finalMaxReferenceVideos,
                finalMaxReferenceAudios
        );
    }

    public void validateImageTask(AiModel model, ImageTask task) {
        validateImageTask(model, task, aiModelMetadataResolver.resolvePlatform(model));
    }

    public void validateImageTask(AiModel model, ImageTask task, String platform) {
        if (model == null || task == null) {
            return;
        }
        ImageModelCapability capability = resolveImageCapability(model, platform);
        List<String> referenceImages = parseJsonUrls(task.getRefImageUrls());

        if (!referenceImages.isEmpty() && !capability.supportsReferenceImages()) {
            throw new BusinessException("当前图片模型 " + modelLabel(model)
                    + " 不支持参考图输入，请不要传 imageUrls；如需图生图，请切换到支持参考图的模型。");
        }

        if (capability.maxReferenceImages() != null && referenceImages.size() > capability.maxReferenceImages()) {
            throw new BusinessException("当前图片模型 " + modelLabel(model)
                    + " 最多支持 " + capability.maxReferenceImages() + " 张参考图，当前传入了 " + referenceImages.size() + " 张。");
        }

        if (capability.minReferenceImages() > 0 && !referenceImages.isEmpty()
                && referenceImages.size() < capability.minReferenceImages()) {
            throw new BusinessException("当前图片模型 " + modelLabel(model)
                    + " 至少需要 " + capability.minReferenceImages() + " 张参考图，当前仅传入了 " + referenceImages.size() + " 张。");
        }
    }

    /**
     * 强制裁剪图片任务参考图：不支持的清空，超限的截断保留前 N 张。
     * <p>
     * 当 model config 中 failOnUnsupportedRef=true 时，不支持参考图但任务传了参考图会直接抛异常，
     * 而非静默清空。适用于分镜首尾帧等对参考图一致性要求严格的场景。
     */
    public void trimImageTaskInputs(AiModel model, ImageTask task) {
        if (model == null || task == null) {
            return;
        }
        ImageModelCapability capability = resolveImageCapability(model);
        if (!capability.supportsReferenceImages()) {
            if (StrUtil.isNotBlank(task.getRefImageUrls())) {
                JSONObject config = getMergedModelConfig(model);
                Boolean failOnUnsupported = getBoolean(config, "failOnUnsupportedRef", "failOnUnsupportedReference");
                if (Boolean.TRUE.equals(failOnUnsupported)) {
                    throw new BusinessException("当前图片模型 " + modelLabel(model)
                            + " 不支持参考图输入，但任务传入了参考图。"
                            + "如需保证资产一致性，请切换到支持参考图的图片模型。"
                            + "（failOnUnsupportedRef=true）");
                }
                log.warn("[CapabilityTrim] 图片模型 {} 不支持参考图，已清空 refImageUrls", model.getCode());
                task.setRefImageUrls(null);
            }
            return;
        }
        List<String> urls = parseJsonUrls(task.getRefImageUrls());
        if (urls.isEmpty()) {
            return;
        }
        int max = capability.maxReferenceImages() != null ? capability.maxReferenceImages() : urls.size();
        if (urls.size() > max) {
            log.warn("[CapabilityTrim] 图片模型 {} 参考图超限: {}张 → 截断保留前{}张", model.getCode(), urls.size(), max);
            task.setRefImageUrls(JSONUtil.toJsonStr(urls.subList(0, max)));
        }
    }

    public void validateVideoTask(AiModel model, VideoTask task) {
        validateVideoTask(model, task, aiModelMetadataResolver.resolvePlatform(model));
    }

    public void validateVideoTask(AiModel model, VideoTask task, String platform) {
        if (model == null || task == null) {
            return;
        }
        VideoModelCapability capability = resolveVideoCapability(model, platform);
        List<String> referenceImages = parseJsonUrls(task.getReferenceImageUrls());
        List<String> referenceVideos = parseJsonUrls(task.getReferenceVideoUrls());
        List<String> referenceAudios = parseJsonUrls(task.getReferenceAudioUrls());
        boolean hasFirstFrame = StrUtil.isNotBlank(task.getFirstFrameImageUrl());
        boolean hasLastFrame = StrUtil.isNotBlank(task.getLastFrameImageUrl());
        int totalImageInputs = referenceImages.size() + (hasFirstFrame ? 1 : 0) + (hasLastFrame ? 1 : 0);

        if (hasFirstFrame && !capability.supportsFirstFrame()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 不支持首帧图输入，请不要传 firstFrameImageUrl。");
        }
        if (hasLastFrame && !capability.supportsLastFrame()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 不支持尾帧图输入，请不要传 lastFrameImageUrl。");
        }
        if (!referenceImages.isEmpty() && !capability.supportsReferenceImages()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 不支持 referenceImageUrls，请改用支持多图参考的模型。");
        }
        if (!referenceVideos.isEmpty() && !capability.supportsReferenceVideos()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 不支持 referenceVideoUrls。");
        }
        if (!referenceAudios.isEmpty() && !capability.supportsReferenceAudios()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 不支持 referenceAudioUrls。");
        }

        if (capability.minImageInputs() > 0 && totalImageInputs < capability.minImageInputs()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 至少需要 " + capability.minImageInputs() + " 张图片输入，请传 firstFrameImageUrl、lastFrameImageUrl 或 referenceImageUrls。");
        }
        if (capability.maxImageInputs() != null && totalImageInputs > capability.maxImageInputs()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 最多支持 " + capability.maxImageInputs() + " 张图片输入，当前传入了 " + totalImageInputs + " 张。");
        }
        if (capability.maxReferenceImages() != null && referenceImages.size() > capability.maxReferenceImages()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 最多支持 " + capability.maxReferenceImages() + " 张 referenceImageUrls，当前传入了 " + referenceImages.size() + " 张。");
        }
        if (capability.maxReferenceVideos() != null && referenceVideos.size() > capability.maxReferenceVideos()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 最多支持 " + capability.maxReferenceVideos() + " 个 referenceVideoUrls，当前传入了 " + referenceVideos.size() + " 个。");
        }
        if (capability.maxReferenceAudios() != null && referenceAudios.size() > capability.maxReferenceAudios()) {
            throw new BusinessException("当前视频模型 " + modelLabel(model)
                    + " 最多支持 " + capability.maxReferenceAudios() + " 个 referenceAudioUrls，当前传入了 " + referenceAudios.size() + " 个。");
        }
    }

    /**
     * 强制裁剪视频任务输入：按模型能力自动清空不支持的字段、截断超限的参考图列表。
     * <p>
     * 关键规则（保证资产一致性）：
     * 1. 不支持的字段直接清空（首帧/尾帧/参考图/参考视频/参考音频）
     * 2. maxImageInputs 限制时，优先保留首帧，其次参考图（截断到前 N 张）
     * 3. maxReferenceImages 限制时，参考图截断到前 N 张
     * 4. 有首帧且 maxImageInputs=1 时，清空参考图（首帧已锁定画面）
     */
    public void trimVideoTaskInputs(AiModel model, VideoTask task) {
        if (model == null || task == null) {
            return;
        }
        VideoModelCapability capability = resolveVideoCapability(model);
        JSONObject config = getMergedModelConfig(model);
        Boolean failOnUnsupported = getBoolean(config, "failOnUnsupportedRef", "failOnUnsupportedReference");

        // 1. 清空不支持的字段
        //    首帧/尾帧降级：模型不支持首帧/尾帧但支持参考图时，将首帧/尾帧 URL 追加到参考图列表末尾，
        //    并在后续（重排+截断后）于 prompt 中追加提示词指令，间接实现首尾帧效果。
        List<String> downgradeRefImages = new ArrayList<>(parseJsonUrls(task.getReferenceImageUrls()));
        String downgradedFirstFrameUrl = null;
        String downgradedLastFrameUrl = null;

        if (!capability.supportsFirstFrame() && StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            if (Boolean.TRUE.equals(failOnUnsupported)) {
                throw new BusinessException("当前视频模型 " + modelLabel(model)
                        + " 不支持首帧图输入，但任务传入了 firstFrameImageUrl。"
                        + "请切换到支持首帧的模型。"
                        + "（failOnUnsupportedRef=true）");
            }
            if (capability.supportsReferenceImages()) {
                downgradedFirstFrameUrl = task.getFirstFrameImageUrl().trim();
                if (!downgradeRefImages.contains(downgradedFirstFrameUrl)) {
                    downgradeRefImages.add(downgradedFirstFrameUrl);
                }
                log.info("[CapabilityTrim] 视频模型 {} 不支持首帧，降级为参考图 + 提示词指令", model.getCode());
            } else {
                log.warn("[CapabilityTrim] 视频模型 {} 不支持首帧且不支持参考图，已清空 firstFrameImageUrl", model.getCode());
            }
            task.setFirstFrameImageUrl(null);
        }
        if (!capability.supportsLastFrame() && StrUtil.isNotBlank(task.getLastFrameImageUrl())) {
            if (Boolean.TRUE.equals(failOnUnsupported)) {
                throw new BusinessException("当前视频模型 " + modelLabel(model)
                        + " 不支持尾帧图输入，但任务传入了 lastFrameImageUrl。"
                        + "请切换到支持尾帧的模型。"
                        + "（failOnUnsupportedRef=true）");
            }
            if (capability.supportsReferenceImages()) {
                downgradedLastFrameUrl = task.getLastFrameImageUrl().trim();
                if (!downgradeRefImages.contains(downgradedLastFrameUrl)) {
                    downgradeRefImages.add(downgradedLastFrameUrl);
                }
                log.info("[CapabilityTrim] 视频模型 {} 不支持尾帧，降级为参考图 + 提示词指令", model.getCode());
            } else {
                log.warn("[CapabilityTrim] 视频模型 {} 不支持尾帧且不支持参考图，已清空 lastFrameImageUrl", model.getCode());
            }
            task.setLastFrameImageUrl(null);
        }
        if (!downgradeRefImages.equals(parseJsonUrls(task.getReferenceImageUrls()))) {
            task.setReferenceImageUrls(JSONUtil.toJsonStr(downgradeRefImages));
        }
        if (!capability.supportsReferenceImages() && StrUtil.isNotBlank(task.getReferenceImageUrls())) {
            if (Boolean.TRUE.equals(failOnUnsupported)) {
                throw new BusinessException("当前视频模型 " + modelLabel(model)
                        + " 不支持参考图输入，但任务传入了 referenceImageUrls。"
                        + "请切换到支持参考图的模型。"
                        + "（failOnUnsupportedRef=true）");
            }
            log.warn("[CapabilityTrim] 视频模型 {} 不支持参考图，已清空 referenceImageUrls", model.getCode());
            task.setReferenceImageUrls(null);
        }
        if (!capability.supportsReferenceVideos() && StrUtil.isNotBlank(task.getReferenceVideoUrls())) {
            log.warn("[CapabilityTrim] 视频模型 {} 不支持参考视频，已清空 referenceVideoUrls", model.getCode());
            task.setReferenceVideoUrls(null);
        }
        if (!capability.supportsReferenceAudios() && StrUtil.isNotBlank(task.getReferenceAudioUrls())) {
            log.warn("[CapabilityTrim] 视频模型 {} 不支持参考音频，已清空 referenceAudioUrls", model.getCode());
            task.setReferenceAudioUrls(null);
        }

        // 2. 首帧+参考图互斥：模型不支持同时传首帧和参考图时，有首帧则清空参考图
        List<String> refImages = prioritizeReferenceImages(parseJsonUrls(task.getReferenceImageUrls()));
        boolean hasFirstFrame = StrUtil.isNotBlank(task.getFirstFrameImageUrl());
        boolean hasLastFrame = StrUtil.isNotBlank(task.getLastFrameImageUrl());
        if (!capability.supportsFirstFrameWithReference() && hasFirstFrame && !refImages.isEmpty()) {
            log.warn("[CapabilityTrim] 视频模型 {} 不支持首帧+参考图同时传，有首帧时清空 referenceImageUrls",
                    model.getCode());
            task.setReferenceImageUrls(null);
            refImages = List.of();
        }

        // 3. maxImageInputs 限制：首帧 + 参考图总数超限时，优先保留首帧，截断/清空参考图
        //    裁剪时将画风参考图（/api/art-styles/）降级到末尾，优先保留角色/道具/场景图
        int totalImageInputs = refImages.size() + (hasFirstFrame ? 1 : 0) + (hasLastFrame ? 1 : 0);

        if (capability.maxImageInputs() != null && totalImageInputs > capability.maxImageInputs()) {
            int budget = capability.maxImageInputs();
            // 优先保留首帧
            if (hasFirstFrame) {
                budget -= 1;
            }
            // 其次保留尾帧
            if (hasLastFrame && budget > 0) {
                budget -= 1;
            }
            // 剩余预算给参考图
            if (budget > 0 && refImages.size() > budget) {
                log.warn("[CapabilityTrim] 视频模型 {} 参考图截断: {}张 → 保留前{}张 (maxImageInputs={})",
                        model.getCode(), refImages.size(), budget, capability.maxImageInputs());
                task.setReferenceImageUrls(JSONUtil.toJsonStr(refImages.subList(0, budget)));
            } else if (budget <= 0 && !refImages.isEmpty()) {
                log.warn("[CapabilityTrim] 视频模型 {} 图片输入超限，已清空参考图 (maxImageInputs={})",
                        model.getCode(), capability.maxImageInputs());
                task.setReferenceImageUrls(null);
            }
        }

        // 3. maxReferenceImages 单独限制
        refImages = parseJsonUrls(task.getReferenceImageUrls());
        if (capability.maxReferenceImages() != null && refImages.size() > capability.maxReferenceImages()) {
            log.warn("[CapabilityTrim] 视频模型 {} 参考图截断: {}张 → 保留前{}张 (maxReferenceImages={})",
                    model.getCode(), refImages.size(), capability.maxReferenceImages(), capability.maxReferenceImages());
            task.setReferenceImageUrls(JSONUtil.toJsonStr(refImages.subList(0, capability.maxReferenceImages())));
        }

        // 4. 参考视频/音频截断
        List<String> refVideos = parseJsonUrls(task.getReferenceVideoUrls());
        if (capability.maxReferenceVideos() != null && refVideos.size() > capability.maxReferenceVideos()) {
            log.warn("[CapabilityTrim] 视频模型 {} 参考视频截断: {}个 → 保留前{}个",
                    model.getCode(), refVideos.size(), capability.maxReferenceVideos());
            task.setReferenceVideoUrls(JSONUtil.toJsonStr(refVideos.subList(0, capability.maxReferenceVideos())));
        }
        // 参考音频支持结构化 [{imageUrl, audioUrl}] 和扁平 [audioUrl] 两种格式
        int audioCount = countAudioEntries(task.getReferenceAudioUrls());
        if (capability.maxReferenceAudios() != null && audioCount > capability.maxReferenceAudios()) {
            log.warn("[CapabilityTrim] 视频模型 {} 参考音频截断: {}个 → 保留前{}个",
                    model.getCode(), audioCount, capability.maxReferenceAudios());
            task.setReferenceAudioUrls(truncateAudioEntries(task.getReferenceAudioUrls(), capability.maxReferenceAudios()));
        }

        // 5. 首帧/尾帧降级后的提示词指令：在所有重排和截断完成后，根据最终参考图列表中的实际位置追加提示词。
        //    如果降级的 URL 在截断中被丢弃，则不追加对应提示词（避免引用不存在的图片）。
        if (downgradedFirstFrameUrl != null || downgradedLastFrameUrl != null) {
            List<String> finalRefImages = parseJsonUrls(task.getReferenceImageUrls());
            StringBuilder promptSuffix = new StringBuilder();
            if (downgradedFirstFrameUrl != null) {
                int idx = finalRefImages.indexOf(downgradedFirstFrameUrl);
                if (idx >= 0) {
                    promptSuffix.append("\n\n以参考图片").append(idx + 1).append("的画面作为视频首帧起始画面。");
                    log.info("[CapabilityTrim] 视频模型 {} 首帧降级提示词：参考图片{}", model.getCode(), idx + 1);
                } else {
                    log.warn("[CapabilityTrim] 视频模型 {} 首帧降级URL在截断中被丢弃，未追加提示词", model.getCode());
                }
            }
            if (downgradedLastFrameUrl != null) {
                int idx = finalRefImages.indexOf(downgradedLastFrameUrl);
                if (idx >= 0) {
                    promptSuffix.append("\n\n以参考图片").append(idx + 1).append("的画面作为视频末帧结束画面。");
                    log.info("[CapabilityTrim] 视频模型 {} 尾帧降级提示词：参考图片{}", model.getCode(), idx + 1);
                } else {
                    log.warn("[CapabilityTrim] 视频模型 {} 尾帧降级URL在截断中被丢弃，未追加提示词", model.getCode());
                }
            }
            if (promptSuffix.length() > 0) {
                if (StrUtil.isNotBlank(task.getPrompt())) {
                    task.setPrompt(task.getPrompt() + promptSuffix);
                } else {
                    task.setPrompt(promptSuffix.toString().strip());
                }
            }
        }
    }

    /**
     * 统计参考音频条目数量，兼容结构化 [{imageUrl, audioUrl}] 和扁平 [audioUrl] 两种格式。
     */
    private int countAudioEntries(String json) {
        if (StrUtil.isBlank(json)) {
            return 0;
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            return array.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 截断参考音频 JSON，保留前 max 个条目。兼容结构化和扁平两种格式。
     */
    private String truncateAudioEntries(String json, int max) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            if (array.size() <= max) {
                return json;
            }
            JSONArray truncated = new JSONArray();
            for (int i = 0; i < max; i++) {
                truncated.add(array.get(i));
            }
            return truncated.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    public String describeImageCapability(AiModel model) {
        if (model == null) {
            return "当前未配置默认图片模型。";
        }
        ImageModelCapability capability = resolveImageCapability(model);
        if (!capability.supportsReferenceImages()) {
            return "当前默认图片模型：" + modelLabel(model) + "；不支持参考图，仅支持文生图。";
        }
        String limitText = capability.maxReferenceImages() != null && capability.maxReferenceImages() > 0
                ? "，最多 " + capability.maxReferenceImages() + " 张" : "";
        return "当前默认图片模型：" + modelLabel(model) + "；支持参考图" + limitText + "。";
    }

    public JSONObject buildImageCapabilitySnapshot(AiModel model) {
        if (model == null) {
            return JSONUtil.createObj()
                    .set("configured", false)
                    .set("summary", "当前未配置默认图片模型。");
        }

        AiModelMetadata metadata = aiModelMetadataResolver.resolve(model);
        String platform = metadata.platform();
        JSONObject config = getMergedModelConfig(model);
        ImageModelCapability capability = resolveImageCapability(model, platform);

        return JSONUtil.createObj()
                .set("configured", true)
                .set("modelType", "image")
                .set("modelId", model.getId())
                .set("modelName", modelLabel(model))
                .set("modelCode", model.getCode())
                .set("modelFamily", metadata.modelFamily())
                .set("modelProtocol", metadata.modelProtocol())
                .set("platform", platform)
                .set("supportsReferenceImages", capability.supportsReferenceImages())
                .set("minReferenceImages", capability.minReferenceImages())
                .set("maxReferenceImages", capability.maxReferenceImages())
                .set("asyncMode", Boolean.TRUE.equals(getBoolean(config,
                    "asyncMode", "useAsyncMode", "asyncTaskMode", "enableAsyncTask", "asyncEnabled")))
                .set("supportedAspectRatios", getStringList(config, "supportedAspectRatios"))
                .set("supportedSizes", copyJsonObject(config, "supportedSizes"))
                .set("defaultWidth", getInteger(config, "defaultWidth"))
                .set("defaultHeight", getInteger(config, "defaultHeight"))
                .set("summary", describeImageCapability(model));
    }

    public String describeVideoCapability(AiModel model) {
        if (model == null) {
            return "当前未配置默认视频模型。";
        }

        VideoModelCapability capability = resolveVideoCapability(model);
        List<String> parts = new ArrayList<>();
        parts.add("当前默认视频模型：" + modelLabel(model));
        parts.add("首帧图：" + yesNo(capability.supportsFirstFrame()));
        parts.add("尾帧图：" + yesNo(capability.supportsLastFrame()));
        parts.add("参考图：" + referenceSupportText(capability.supportsReferenceImages(), capability.maxReferenceImages(), "张"));
        parts.add("参考视频：" + referenceSupportText(capability.supportsReferenceVideos(), capability.maxReferenceVideos(), "个"));
        parts.add("参考音频：" + referenceSupportText(capability.supportsReferenceAudios(), capability.maxReferenceAudios(), "个"));
        parts.add("首帧+参考图：" + yesNo(capability.supportsFirstFrameWithReference()));
        if (capability.minImageInputs() > 0) {
            parts.add("至少需要 " + capability.minImageInputs() + " 张图片输入");
        }
        return String.join("；", parts) + "。";
    }

    public JSONObject buildVideoCapabilitySnapshot(AiModel model) {
        if (model == null) {
            return JSONUtil.createObj()
                    .set("configured", false)
                    .set("summary", "当前未配置默认视频模型。");
        }

        AiModelMetadata metadata = aiModelMetadataResolver.resolve(model);
        String platform = metadata.platform();
        JSONObject config = getMergedModelConfig(model);
        VideoModelCapability capability = resolveVideoCapability(model, platform);

        return JSONUtil.createObj()
                .set("configured", true)
                .set("modelType", "video")
                .set("modelId", model.getId())
                .set("modelName", modelLabel(model))
                .set("modelCode", model.getCode())
                .set("modelFamily", metadata.modelFamily())
                .set("modelProtocol", metadata.modelProtocol())
                .set("platform", platform)
                .set("supportsFirstFrame", capability.supportsFirstFrame())
                .set("supportsLastFrame", capability.supportsLastFrame())
                .set("supportsReferenceImages", capability.supportsReferenceImages())
                .set("supportsReferenceVideos", capability.supportsReferenceVideos())
                .set("supportsReferenceAudios", capability.supportsReferenceAudios())
                .set("supportsFirstFrameWithReference", capability.supportsFirstFrameWithReference())
                .set("minImageInputs", capability.minImageInputs())
                .set("maxImageInputs", capability.maxImageInputs())
                .set("maxReferenceImages", capability.maxReferenceImages())
                .set("maxReferenceVideos", capability.maxReferenceVideos())
                .set("maxReferenceAudios", capability.maxReferenceAudios())
                .set("supportedAspectRatios", getStringList(config, "supportedAspectRatios"))
                .set("supportedResolutions", getStringList(config, "supportedResolutions"))
                .set("minDuration", getInteger(config, "minDuration"))
                .set("maxDuration", getInteger(config, "maxDuration"))
                .set("defaultDuration", getInteger(config, "defaultDuration"))
                .set("supportCameraFixed", getBoolean(config, "supportCameraFixed"))
                .set("summary", describeVideoCapability(model));
    }

    public JSONObject getMergedModelConfig(AiModel model) {
        JSONObject merged = new JSONObject();
        if (model != null && modelPresetService != null && StrUtil.isNotBlank(model.getCode())) {
            mergeConfig(merged, parseConfig(modelPresetService.getPresetConfig(model.getCode())));
        }
        if (model != null) {
            mergeConfig(merged, parseConfig(model.getConfig()));
        }
        return merged;
    }

    public String resolveModelPlatform(AiModel model) {
        return aiModelMetadataResolver.resolvePlatform(model);
    }

    /**
     * 根据分辨率标签（如 "1K"、"2K"、"4K"）和画面比例（如 "16:9"、"9:16"），
     * 从模型 config 的 supportedSizes 中解析出实际像素宽高。
     * <p>
     * supportedSizes 支持两种格式：
     * <ul>
     *   <li>简单格式：{"2K": "1600x2848"} — 直接解析，再根据 aspectRatio 判断是否需要交换宽高</li>
     *   <li>嵌套格式：{"2K": {"16:9": "1920x1080", "9:16": "1080x1920"}} — 按 aspectRatio 查找</li>
     * </ul>
     *
     * @return int[]{width, height}，未找到返回 null
     */
    public int[] resolveImageSizeFromResolution(AiModel model, String resolutionLabel, String aspectRatio) {
        if (StrUtil.isBlank(resolutionLabel)) {
            return null;
        }
        JSONObject config = getMergedModelConfig(model);
        if (config == null) {
            return null;
        }
        Object supportedSizes = config.get("supportedSizes");
        if (supportedSizes == null) {
            return null;
        }

        String label = resolutionLabel.trim().toUpperCase(Locale.ROOT);
        String ratio = aspectRatio != null ? aspectRatio.trim() : null;

        if (supportedSizes instanceof JSONObject sizesObj) {
            Object entry = sizesObj.get(label);
            if (entry == null) {
                // 尝试小写
                entry = sizesObj.get(resolutionLabel.trim().toLowerCase(Locale.ROOT));
            }
            if (entry == null) {
                return null;
            }

            if (entry instanceof String sizeStr) {
                int[] wh = parseSizeString(sizeStr);
                if (wh != null && ratio != null) {
                    if (isPortrait(ratio) && wh[0] > wh[1]) {
                        return new int[]{wh[1], wh[0]};
                    } else if (!isPortrait(ratio) && wh[0] < wh[1]) {
                        return new int[]{wh[1], wh[0]};
                    }
                }
                return wh;
            }

            if (entry instanceof JSONObject ratioObj && ratio != null) {
                Object ratioEntry = ratioObj.get(ratio);
                if (ratioEntry == null) {
                    // 尝试在 ratioObj 中寻找反向比例（如 9:16 未配置但配置了 16:9，或相反）
                    String flippedRatio = getFlippedRatio(ratio);
                    if (flippedRatio != null && ratioObj.containsKey(flippedRatio)) {
                        Object flippedEntry = ratioObj.get(flippedRatio);
                        if (flippedEntry instanceof String flippedSizeStr) {
                            int[] wh = parseSizeString(flippedSizeStr);
                            if (wh != null) {
                                return new int[]{wh[1], wh[0]};
                            }
                        }
                    }
                    ratioEntry = ratioObj.values().iterator().next();
                }
                if (ratioEntry instanceof String ratioSizeStr) {
                    int[] wh = parseSizeString(ratioSizeStr);
                    if (wh != null) {
                        if (isPortrait(ratio) && wh[0] > wh[1]) {
                            return new int[]{wh[1], wh[0]};
                        } else if (!isPortrait(ratio) && wh[0] < wh[1]) {
                            return new int[]{wh[1], wh[0]};
                        }
                    }
                    return wh;
                }
            }
        }
        return null;
    }

    private int[] parseSizeString(String sizeStr) {
        if (StrUtil.isBlank(sizeStr)) {
            return null;
        }
        String[] parts = sizeStr.trim().toLowerCase(Locale.ROOT).split("x");
        if (parts.length == 2) {
            try {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                if (w > 0 && h > 0) {
                    return new int[]{w, h};
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean isPortrait(String ratio) {
        if (StrUtil.isBlank(ratio)) {
            return false;
        }
        String[] parts = ratio.split(":");
        if (parts.length == 2) {
            try {
                return Integer.parseInt(parts[0]) < Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return "9:16".equals(ratio) || "3:4".equals(ratio) || "portrait".equalsIgnoreCase(ratio);
    }

    private String getFlippedRatio(String ratio) {
        if (StrUtil.isBlank(ratio)) {
            return null;
        }
        String[] parts = ratio.split(":");
        if (parts.length == 2) {
            return parts[1] + ":" + parts[0];
        }
        return null;
    }

    private ImageModelCapability inferImageCapability(AiModel model, AiModelMetadata metadata) {
        String code = model != null && StrUtil.isNotBlank(model.getCode())
                ? model.getCode().toLowerCase(Locale.ROOT) : "";
        JSONObject ruleConfig = capabilityRuleService.matchImageCapability(metadata, code);
        if (ruleConfig != null) {
            boolean supportsRef = ruleConfig.getBool("supportReferenceImages", false);
            int minRef = ruleConfig.getInt("minReferenceImages", 0);
            Integer maxRef = ruleConfig.getInt("maxReferenceImages");
            log.debug("[CapabilityResolve] 图片模型能力规则命中: code={}, platform={}, family={}, protocol={}, "
                            + "supportsRef={}, minRef={}, maxRef={}",
                    code, metadata.normalizedPlatform(), metadata.effectiveFamily(), metadata.effectiveProtocol(),
                    supportsRef, minRef, maxRef);
            return new ImageModelCapability(supportsRef, minRef, maxRef);
        }
        log.warn("[CapabilityResolve] 图片模型能力规则未命中，回退全 false: code={}, platform={}, family={}, protocol={}",
                code, metadata.normalizedPlatform(), metadata.effectiveFamily(), metadata.effectiveProtocol());
        return new ImageModelCapability(false, 0, 0);
    }

    private VideoModelCapability inferVideoCapability(AiModel model, AiModelMetadata metadata) {
        String code = model != null && StrUtil.isNotBlank(model.getCode())
                ? model.getCode().toLowerCase(Locale.ROOT) : "";
        JSONObject ruleConfig = capabilityRuleService.matchVideoCapability(metadata, code);
        if (ruleConfig != null) {
            boolean firstFrame = ruleConfig.getBool("supportFirstFrame", false);
            boolean lastFrame = ruleConfig.getBool("supportLastFrame", false);
            boolean refImages = ruleConfig.getBool("supportReferenceImages", false);
            boolean refVideos = ruleConfig.getBool("supportReferenceVideos", false);
            boolean refAudios = ruleConfig.getBool("supportReferenceAudios", false);
            boolean firstFrameWithRef = ruleConfig.getBool("supportFirstFrameWithReference", false);
            int minImageInputs = ruleConfig.getInt("minImageInputs", 0);
            Integer maxImageInputs = ruleConfig.getInt("maxImageInputs");
            Integer maxRefImages = ruleConfig.getInt("maxReferenceImages");
            Integer maxRefVideos = ruleConfig.getInt("maxReferenceVideos");
            Integer maxRefAudios = ruleConfig.getInt("maxReferenceAudios");
            log.debug("[CapabilityResolve] 视频模型能力规则命中: code={}, platform={}, family={}, protocol={}, "
                            + "firstFrame={}, lastFrame={}, refImages={}, refVideos={}, refAudios={}, "
                            + "minImages={}, maxImages={}, maxRefImages={}, maxRefVideos={}, maxRefAudios={}",
                    code, metadata.normalizedPlatform(), metadata.effectiveFamily(), metadata.effectiveProtocol(),
                    firstFrame, lastFrame, refImages, refVideos, refAudios,
                    minImageInputs, maxImageInputs, maxRefImages, maxRefVideos, maxRefAudios);
            return new VideoModelCapability(firstFrame, lastFrame, refImages, refVideos, refAudios, firstFrameWithRef,
                    minImageInputs, maxImageInputs, maxRefImages, maxRefVideos, maxRefAudios);
        }
        log.warn("[CapabilityResolve] 视频模型能力规则未命中，尝试精确兜底: code={}, platform={}, family={}, protocol={}",
                code, metadata.normalizedPlatform(), metadata.effectiveFamily(), metadata.effectiveProtocol());
        // Seedance 精确兜底：code 包含 seedance 时，已知支持首帧/尾帧/参考图/参考视频/参考音频
        if (code.contains("seedance")) {
            log.info("[CapabilityResolve] Seedance 精确兜底命中: code={}, 推断 supportsFirstFrame=true, supportsLastFrame=true, supportsReferenceImages=true, supportsFirstFrameWithReference=true",
                    code);
            return new VideoModelCapability(true, true, true, true, true, true,
                    0, 10, 9, 1, 1);
        }
        // Wan2.7 系列精确兜底
        if (code.contains("wan2.7-") || code.contains("wan2.7_")) {
            if (code.contains("r2v")) {
                // reference-to-video：支持参考图、首帧+参考图同时传、参考音频
                log.info("[CapabilityResolve] Wan2.7-r2v 精确兜底命中: code={}, 推断 supportsFirstFrame=true, supportsReferenceImages=true, supportsFirstFrameWithReference=true, supportsReferenceAudios=true",
                        code);
                return new VideoModelCapability(true, false, true, false, true, true,
                        0, 10, 9, 0, 1);
            }
            if (code.contains("i2v")) {
                // image-to-video：支持首帧
                log.info("[CapabilityResolve] Wan2.7-i2v 精确兜底命中: code={}, 推断 supportsFirstFrame=true",
                        code);
                return new VideoModelCapability(true, false, false, false, false, false,
                        0, 1, 0, 0, 0);
            }
            if (code.contains("kf2v")) {
                // keyframe-to-video：支持首帧+尾帧
                log.info("[CapabilityResolve] Wan2.7-kf2v 精确兜底命中: code={}, 推断 supportsFirstFrame=true, supportsLastFrame=true",
                        code);
                return new VideoModelCapability(true, true, false, false, false, false,
                        0, 2, 0, 0, 0);
            }
            if (code.contains("videoedit")) {
                // video-edit：支持参考视频
                log.info("[CapabilityResolve] Wan2.7-videoedit 精确兜底命中: code={}, 推断 supportsReferenceVideos=true",
                        code);
                return new VideoModelCapability(false, false, false, true, false, false,
                        0, 0, 0, 1, 0);
            }
            // wan2.7-t2v：纯文生视频，无图片输入
            log.info("[CapabilityResolve] Wan2.7-t2v 精确兜底命中: code={}", code);
            return new VideoModelCapability(false, false, false, false, false, false,
                    0, 0, 0, 0, 0);
        }
        return new VideoModelCapability(false, false, false, false, false, false,
                0, 0, 0, 0, 0);
    }

    private JSONObject parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception ignored) {
            return new JSONObject();
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

    private String modelLabel(AiModel model) {
        if (model == null) {
            return "未命名模型";
        }
        return StrUtil.blankToDefault(model.getName(), model.getCode());
    }

    private Boolean getBoolean(JSONObject config, String... keys) {
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
        for (String key : keys) {
            if (!config.containsKey(key)) {
                continue;
            }
            try {
                return config.getInt(key);
            } catch (Exception ignored) {
                Object value = config.get(key);
                if (value != null) {
                    try {
                        return Integer.parseInt(value.toString());
                    } catch (NumberFormatException ignoredAgain) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private List<String> getStringList(JSONObject config, String key) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return List.of();
        }
        Object value = config.get(key);
        if (value instanceof JSONArray array) {
            return array.toList(String.class).stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .toList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? null : item.toString())
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .toList();
        }
        if (value != null) {
            String text = value.toString().trim();
            return StrUtil.isBlank(text) ? List.of() : List.of(text);
        }
        return List.of();
    }

    private JSONObject copyJsonObject(JSONObject config, String key) {
        if (config == null || StrUtil.isBlank(key) || !config.containsKey(key)) {
            return null;
        }
        Object value = config.get(key);
        if (value instanceof JSONObject jsonObject) {
            return JSONUtil.parseObj(jsonObject);
        }
        if (value != null) {
            try {
                return JSONUtil.parseObj(value);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer mergeNullableMax(Integer explicitValue, Integer inferredValue) {
        if (explicitValue != null) {
            return Math.max(explicitValue, 0);
        }
        return inferredValue;
    }

    /**
     * 解析图片任务的参考图 URL 列表（公开方法，供工具层可观测使用）。
     */
    public List<String> parseImageRefUrls(String json) {
        return parseJsonUrls(json);
    }

    private List<String> parseJsonUrls(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            return array.toList(String.class).stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 将画风参考图（/api/art-styles/）降级到列表末尾，
     * 确保裁剪时优先保留角色/道具/场景图（对人物一致性更关键）。
     */
    private List<String> prioritizeReferenceImages(List<String> urls) {
        if (urls == null || urls.size() <= 1) {
            return urls;
        }
        List<String> prioritized = new ArrayList<>();
        List<String> artStyles = new ArrayList<>();
        for (String url : urls) {
            if (url != null && url.startsWith("/api/art-styles/")) {
                artStyles.add(url);
            } else {
                prioritized.add(url);
            }
        }
        prioritized.addAll(artStyles);
        return prioritized;
    }

    private String yesNo(boolean value) {
        return value ? "支持" : "不支持";
    }

    private String referenceSupportText(boolean supported, Integer maxCount, String unit) {
        if (!supported) {
            return "不支持";
        }
        if (maxCount != null && maxCount > 0) {
            return "支持，最多 " + maxCount + unit;
        }
        return "支持";
    }

    public record ImageModelCapability(boolean supportsReferenceImages,
                                       int minReferenceImages,
                                       Integer maxReferenceImages) {
    }

    public record VideoModelCapability(boolean supportsFirstFrame,
                                       boolean supportsLastFrame,
                                       boolean supportsReferenceImages,
                                       boolean supportsReferenceVideos,
                                       boolean supportsReferenceAudios,
                                       boolean supportsFirstFrameWithReference,
                                       int minImageInputs,
                                       Integer maxImageInputs,
                                       Integer maxReferenceImages,
                                       Integer maxReferenceVideos,
                                       Integer maxReferenceAudios) {
    }
}