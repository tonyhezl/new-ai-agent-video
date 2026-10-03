package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.config.ArtStylePresets;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.consumer.ImageGenerationConsumer;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import com.tonyhe.fusion.service.storyboard.SceneStatePromptCompiler;
import com.tonyhe.fusion.service.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 生图工具（generate_image）
 * <p>
 * 职责：解析参数 → 构建 ImageTask → 提交到队列并同步等待结果。
 * <p>
 * 排队、并发控制、策略路由等全部由 {@link ImageGenerationConsumer} 统一处理，
 * 本工具通过 {@link ImageGenerationConsumer#submitAndWait} 复用其完整流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerateImageToolExecutor implements ToolExecutor {

    /** 模型类型常量：图片生成 */
    private static final int MODEL_TYPE_IMAGE = 2;

    /** 同步等待超时时间（30 分钟） */
    private static final long WAIT_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final Pattern REFERENCE_IMAGE_INDEX_PATTERN = Pattern.compile("图片\\s*(\\d+)");

    private final AiModelService aiModelService;
    private final ImageGenerationService imageGenerationService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final ProjectService projectService;
    private final StoryboardService storyboardService;
    private final SceneStatePromptCompiler sceneStatePromptCompiler;
    private final SystemConfigService systemConfigService;

    @Override
    public String getToolName() {
        return "generate_image";
    }

    @Override
    public String getDisplayName() {
        return "AI 生成图片";
    }

    @Override
    public String getToolDescription() {
        return """
                生成AI图片。此工具仅负责生图，不会自动保存到资产库。

                适用场景：
                1. 为角色生成立绘：根据角色的外貌、性格描述生成设定图
                2. 为分镜生成画面：根据分镜的场景、内容描述生成画面图
                3. 生成场景图、道具图等创意素材

                重要提示：
                - 生成完成后，如需将图片保存到角色/场景/道具等资产中，请使用 update_asset_image 工具
                - 提示词要详细具体，包含画面的主体、风格、视角等信息
                - 可以使用中文提示词，系统会自动处理
                - 如果你打算传 imageUrls，或不确定当前默认模型是否支持参考图，请先调用 get_generation_model_capabilities
                
                %s
                """.formatted(describeCurrentModelCapability());
    }

    @Override
    public String getParametersSchema() {
            AiModel model = resolvePreferredModelOrNull();
            GenerationModelCapabilityService.ImageModelCapability capability = model != null
                ? generationModelCapabilityService.resolveImageCapability(model)
                : null;
            String imageUrlDescription = capability != null && !capability.supportsReferenceImages()
                ? "当前默认模型不支持参考图，请不要传该字段"
                : "参考图片 URL 列表（必须传入 suggestedReferenceImages 中的所有 URL，不得省略任何一张）。当有角色/场景/道具/画风参考图时必须传入全部，在 prompt 中用 图片1、图片2 等按顺序引用。仅当确实无任何参考图时才不传。"
                  + (capability != null && capability.maxReferenceImages() != null && capability.maxReferenceImages() > 0
                    ? "当前模型最多支持 " + capability.maxReferenceImages() + " 张参考图。" : "");

            return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                    .set("prompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "图片生成提示词（英文效果更佳）"))
                    .set("negativePrompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "反向提示词，描述不希望出现的内容"))
                    .set("width", JSONUtil.createObj()
                        .set("type", "number")
                        .set("description", "图片宽度（请勿传此参数，系统会自动从项目配置的 imageResolution 和 aspectRatio 解析正确尺寸）"))
                    .set("height", JSONUtil.createObj()
                        .set("type", "number")
                        .set("description", "图片高度（请勿传此参数，系统会自动从项目配置的 imageResolution 和 aspectRatio 解析正确尺寸）"))
                    .set("style", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "风格（如 realistic, anime, watercolor 等）"))
                    .set("imageUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", imageUrlDescription))
                    .set("requireReferenceImages", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "设为 true 时，如果未传 imageUrls 将直接报错。当 suggestedReferenceImages 非空时必须设为 true，确保参考图不被遗漏。"))
                    .set("storyboardItemId", JSONUtil.createObj()
                        .set("type", "number")
                        .set("description", "（可选）分镜条目ID。传入后生图成功会自动保存到该镜头的首/尾帧，无需再调用 update_storyboard_item_frame。"))
                    .set("frameType", JSONUtil.createObj()
                        .set("type", "string")
                        .set("enum", new String[]{"first", "last"})
                        .set("description", "（可选）帧类型，需与 storyboardItemId 配合使用。first-首帧，last-尾帧。"))
                    .set("framePrompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "（可选）生成首尾帧时使用的提示词，需与 storyboardItemId 配合使用，会保存到分镜条目的 framePrompt 字段。"))
                    .set("namingHint", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "（可选）图片文件命名提示，用于本地持久化时生成可读文件名。资产生图场景请传入资产名称（如 苏念常服、苏念_三视图、青川一中图书馆），分镜首尾帧场景无需传此参数。")))
                .set("required", JSONUtil.parseArray("[\"prompt\"]"))
                .toString();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            String prompt = params.getStr("prompt");
            if (StrUtil.isBlank(prompt)) {
                return errorResult("缺少 prompt");
            }

            Long storyboardItemId = params.getLong("storyboardItemId");
            Long paramProjectId = params.getLong("projectId");
            String frameType = params.getStr("frameType");
            String confirmedFramePrompt = params.getStr("framePrompt");

            int width = params.getInt("width", 0);
            int height = params.getInt("height", 0);
            String negativePrompt = params.getStr("negativePrompt");

            // 画面比例：项目配置有 aspectRatio 时强制使用项目配置解析尺寸，忽略 agent 传入的 width/height
            ProjectProjectConfig projectConfig = resolveProjectConfig(context, storyboardItemId, paramProjectId);
            String configuredAspectRatio = projectConfig != null ? projectConfig.aspectRatio() : null;
            if (StrUtil.isNotBlank(configuredAspectRatio)) {
                AiModel preModel = resolvePreferredModelOrNull();
                if (preModel != null) {
                    String resolution = StrUtil.blankToDefault(projectConfig.imageResolution(), "1K");
                    int[] resolved = generationModelCapabilityService.resolveImageSizeFromResolution(
                            preModel, resolution, configuredAspectRatio);
                    if (resolved == null) {
                        resolved = generationModelCapabilityService.resolveImageSizeFromResolution(
                                preModel, "standard", configuredAspectRatio);
                    }
                    if (resolved == null) {
                        resolved = generationModelCapabilityService.resolveImageSizeFromResolution(
                                preModel, "标准", configuredAspectRatio);
                    }
                    if (resolved == null) {
                        resolved = generationModelCapabilityService.resolveImageSizeFromResolution(
                                preModel, "2K", configuredAspectRatio);
                    }
                    if (resolved != null) {
                        int agentWidth = width;
                        int agentHeight = height;
                        if (agentWidth > 0 && agentHeight > 0
                                && (agentWidth != resolved[0] || agentHeight != resolved[1])) {
                            log.warn("[generate_image] Agent 传入尺寸 {}x{} 与项目配置 {} {} 不一致，强制使用项目配置 → {}x{}",
                                    agentWidth, agentHeight, configuredAspectRatio, resolution,
                                    resolved[0], resolved[1]);
                        }
                        width = resolved[0];
                        height = resolved[1];
                    }
                }
            } else if (width <= 0 || height <= 0) {
                // 项目未配置 aspectRatio 时，回退到原逻辑：未传宽高则尝试从项目配置解析
                if (projectConfig != null && StrUtil.isNotBlank(projectConfig.imageResolution())) {
                    AiModel preModel = resolvePreferredModelOrNull();
                    if (preModel != null) {
                        int[] resolved = generationModelCapabilityService.resolveImageSizeFromResolution(
                                preModel, projectConfig.imageResolution(), projectConfig.aspectRatio());
                        if (resolved != null) {
                            if (width <= 0) width = resolved[0];
                            if (height <= 0) height = resolved[1];
                            log.info("[generate_image] 从项目配置解析图片尺寸: resolution={}, aspectRatio={}, → {}x{}",
                                    projectConfig.imageResolution(), projectConfig.aspectRatio(), width, height);
                        }
                    }
                }
            }

            // 参考图片（图生图）
            String refImageUrls = null;
            boolean hasInputImageUrls = false;
            int inputImageUrlCount = 0;
            List<String> finalImageUrls = null;
            if (params.containsKey("imageUrls")) {
                List<String> imageUrls = params.getJSONArray("imageUrls").toList(String.class);
                inputImageUrlCount = imageUrls.size();
                if (!imageUrls.isEmpty()) {
                    hasInputImageUrls = true;
                    finalImageUrls = new ArrayList<>(imageUrls);
                }
            }

            // 分镜帧的参考图顺序由 suggestedReferenceImages 决定，服务端绝不能插图或重排。
            if (storyboardItemId != null && StrUtil.isNotBlank(frameType)) {
                prompt = sceneStatePromptCompiler.compileFramePrompt(storyboardItemId, frameType, prompt);
                negativePrompt = sceneStatePromptCompiler.compileFrameNegativePrompt(prompt, negativePrompt);
            }
            List<String> artStyleRefUrls;
            String artStyleImagePrompt = null;
            Long effectiveProjectId = projectConfig != null && projectConfig.projectId() != null
                    ? projectConfig.projectId() : (context != null ? context.getProjectId() : null);
            if (storyboardItemId != null) {
                artStyleRefUrls = resolveArtStyleRefImagesByItemId(storyboardItemId);
                artStyleImagePrompt = resolveArtStyleImagePromptByItemId(storyboardItemId);
            } else if (effectiveProjectId != null) {
                // 资产生图场景：无 storyboardItemId，通过 projectId 直接解析画风参考图
                artStyleRefUrls = resolveArtStyleRefImagesByProjectId(effectiveProjectId);
                artStyleImagePrompt = resolveArtStyleImagePromptByProjectId(effectiveProjectId);
            } else {
                artStyleRefUrls = List.of();
            }

            if (storyboardItemId != null && !isFirstFrameOfScene(storyboardItemId, frameType)
                    && finalImageUrls != null && finalImageUrls.stream().anyMatch(artStyleRefUrls::contains)) {
                return errorResult("当前不是场次第一镜的首帧，禁止传入画风参考图。"
                        + "请使用 get_storyboard_scene_items(frameType) 返回的参考图顺序重新生成，避免图片编号错位。");
            }

            // 服务端强制注入画风 imagePrompt：LLM 可能使用旧版、缓存版或自定义改写版的 imagePrompt，
            // 先清除 prompt 中任何已知的画风描述片段（包括前缀变体），再注入最新的
            if (StrUtil.isNotBlank(artStyleImagePrompt)) {
                // 1. 精确清除 prompt 中所有已知预设画风的 imagePrompt（处理完整匹配）
                for (ArtStylePresets.ArtStylePreset preset : ArtStylePresets.getAll()) {
                    String presetPrompt = preset.getImagePrompt();
                    if (StrUtil.isNotBlank(presetPrompt) && prompt.contains(presetPrompt)) {
                        prompt = prompt.replace(presetPrompt + "，", "");
                        prompt = prompt.replace(presetPrompt, "");
                    }
                }
                // 2. 精确清除当前项目的 imagePrompt
                if (prompt.contains(artStyleImagePrompt)) {
                    prompt = prompt.replace(artStyleImagePrompt + "，", "");
                    prompt = prompt.replace(artStyleImagePrompt, "");
                }
                // 3. 结构化剥离：匹配 prompt 开头由任一已知画风名称引导的风格段落
                //    （无论 LLM 缓存了哪种旧版/变体描述，均匹配至参考图声明或主体声明开始处）
                List<String> styleNames = ArtStylePresets.getAll().stream()
                        .map(preset -> preset.getImagePrompt().split("[（,，]")[0].trim())
                        .filter(StrUtil::isNotBlank)
                        .distinct()
                        .toList();
                for (String styleName : styleNames) {
                    // 如果 prompt 开头包含该风格名称，移除开头到参考图声明/主体声明前的风格描述
                    String leadingStyleRegex = "^" + Pattern.quote(styleName) + ".*?(?=(仅参考图片|参考图片|一个[角场道]色?|一张角色设定|【|$))";
                    prompt = prompt.replaceFirst(leadingStyleRegex, "");
                }
                // 4. 兜底清除旧版末尾带特征词的独立风格片段
                for (String styleName : styleNames) {
                    String regex = Pattern.quote(styleName) + ".*?(高质量精细画面|一目了然。)";
                    prompt = prompt.replaceAll(regex, "");
                }
                // 5. 清理多余标点后注入最新的 imagePrompt
                prompt = prompt.replaceAll("，，+", "，");
                prompt = StrUtil.removePrefix(prompt, "，");
                prompt = StrUtil.removePrefix(prompt, "。");
                prompt = StrUtil.removeSuffix(prompt, "，");
                prompt = StrUtil.removeSuffix(prompt, "。");
                prompt = artStyleImagePrompt + "，" + prompt;
                log.info("[generate_image] 服务端清除旧版并注入最新画风 imagePrompt: imagePromptLength={}", artStyleImagePrompt.length());
            }
            // 资产生图没有分镜参考图合同，保留原有画风图兜底；分镜首尾帧严格保留 Agent 传入顺序。
            if (storyboardItemId == null && !artStyleRefUrls.isEmpty()) {
                // 检查已传入的 imageUrls 中是否已包含画风参考图
                int existingStyleCount = 0;
                if (finalImageUrls != null) {
                    for (String url : finalImageUrls) {
                        if (artStyleRefUrls.contains(url)) {
                            existingStyleCount++;
                        }
                    }
                }
                if (existingStyleCount < artStyleRefUrls.size()) {
                    // 缺失画风参考图，强制在开头注入全部画风参考图
                    List<String> injected = new ArrayList<>(artStyleRefUrls);
                    if (finalImageUrls != null) {
                        // 保留非画风参考图（角色/道具/场景），移除已包含的画风图避免重复
                        for (String url : finalImageUrls) {
                            if (!artStyleRefUrls.contains(url)) {
                                injected.add(url);
                            }
                        }
                    }
                    log.info("[generate_image] 服务端强制注入画风参考图: inputCount={}, artStyleCount={}, injectedCount={}, existingStyleCount={}",
                            inputImageUrlCount, artStyleRefUrls.size(), injected.size(), existingStyleCount);
                    finalImageUrls = injected;
                    hasInputImageUrls = true;
                    inputImageUrlCount = finalImageUrls.size();
                }
            }

            if (hasInputImageUrls && finalImageUrls != null) {
                refImageUrls = JSONUtil.toJsonStr(finalImageUrls);
            }

            // requireReferenceImages=true 时，模型支持参考图但未传 imageUrls → 直接报错
            boolean requireReferenceImages = params.getBool("requireReferenceImages", false);
            if (requireReferenceImages && !hasInputImageUrls) {
                AiModel preModel = resolvePreferredModel();
                GenerationModelCapabilityService.ImageModelCapability preCap =
                        generationModelCapabilityService.resolveImageCapability(preModel);
                if (preCap.supportsReferenceImages()) {
                    return errorResult("当前任务要求传入参考图（requireReferenceImages=true），但未提供 imageUrls。"
                            + "请从 suggestedReferenceImages 或资产初始图中提取 URL 传入 imageUrls，确保画面一致性。");
                }
            }

                AiModel model = resolvePreferredModel();

            // 构建可读文件名前缀（用于本地存储时关联镜头信息）
            String namingHint = params.getStr("namingHint");
            if (StrUtil.isBlank(namingHint)) {
                namingHint = buildImageNamingHint(storyboardItemId, frameType);
            }

            // 构建生图任务
            ImageTask task = ImageTask.builder()
                    .prompt(prompt)
                    .negativePrompt(StrUtil.isNotBlank(negativePrompt) ? negativePrompt : null)
                    .width(width > 0 ? width : null)
                    .height(height > 0 ? height : null)
                    .ratio(configuredAspectRatio)
                    .aspectRatio(configuredAspectRatio)
                    .resolution(projectConfig != null ? projectConfig.imageResolution() : null)
                    .projectId(effectiveProjectId)
                    .refImageUrls(refImageUrls)
                    .modelId(model.getId())
                    .modelCode(model.getCode())
                    .textModelCode(context != null ? context.getTextModelCode() : null)
                    .count(1)
                    .userId(context.getUserId())
                    .category(namingHint)
                    .build();

                generationModelCapabilityService.trimImageTaskInputs(model, task);

                // 参考图被裁剪清空时，明确报错而非静默降级为纯文生图
                if (hasInputImageUrls && StrUtil.isBlank(task.getRefImageUrls())) {
                    GenerationModelCapabilityService.ImageModelCapability capability =
                            generationModelCapabilityService.resolveImageCapability(model);
                    String errorMsg = String.format(
                            "已传入 %d 张参考图，但当前图片模型 %s 不支持参考图输入，已自动清空。"
                            + "如需保证资产一致性（角色/场景/道具参考），请切换到支持参考图的图片模型后再重试。",
                            inputImageUrlCount,
                            StrUtil.blankToDefault(model.getName(), model.getCode()));
                    log.warn("[generate_image] 参考图被裁剪清空: model={}, inputCount={}, capability.supportsReferenceImages={}",
                            model.getCode(), inputImageUrlCount, capability.supportsReferenceImages());
                    return errorResult(errorMsg);
                }

                generationModelCapabilityService.validateImageTask(model, task);

                // 解析最终使用的参考图 URL 列表（trim 后）
                List<String> finalRefUrls = generationModelCapabilityService.parseImageRefUrls(task.getRefImageUrls());
                validatePromptReferenceIndexes(prompt, finalRefUrls);
                log.info("[generate_image] 提交生图任务: prompt={}, size={}x{}, modelId={}, modelCode={}, 参考图数量: {}, 参考图URLs: {}",
                    prompt, width, height, model.getId(), model.getCode(),
                    finalRefUrls.size(), finalRefUrls.isEmpty() ? "无" : finalRefUrls);

            // 提交到队列并同步等待结果
            ImageTask completed = imageGenerationConsumer.submitAndWait(task, WAIT_TIMEOUT_MS);

            // 从完成的任务中获取生成的图片 URL
            List<ImageItem> items = imageGenerationService.listItems(completed.getId());
            String imageUrl = items.stream()
                    .filter(item -> StrUtil.isNotBlank(item.getImageUrl()))
                    .map(ImageItem::getImageUrl)
                    .findFirst()
                    .orElse(null);

            if (imageUrl == null) {
                return errorResult("生成完成但未获取到图片 URL");
            }

            log.info("[generate_image] 生成成功: url={}", imageUrl);

            // 如果传入了 storyboardItemId 和 frameType，生图成功后自动保存到分镜条目，避免后续 LLM 调用失败导致图片丢失
            boolean autoSaved = false;
            if (storyboardItemId != null && StrUtil.isNotBlank(frameType)) {
                try {
                    String persistedPrompt = StrUtil.blankToDefault(confirmedFramePrompt, prompt);
                    storyboardService.updateGeneratedItemFrame(
                            storyboardItemId, frameType, imageUrl, persistedPrompt, prompt);
                    autoSaved = true;
                    log.info("[generate_image] 自动保存到分镜条目: itemId={}, frameType={}", storyboardItemId, frameType);
                } catch (Exception e) {
                    log.error("[generate_image] 自动保存分镜首尾帧失败: itemId={}, frameType={}", storyboardItemId, frameType, e);
                }
            }

            JSONObject result = JSONUtil.createObj()
                    .set("status", "success")
                    .set("imageUrl", imageUrl)
                    .set("prompt", prompt)
                    .set("modelCode", model.getCode())
                    .set("modelName", StrUtil.blankToDefault(model.getName(), model.getCode()))
                    .set("referenceImageCount", finalRefUrls.size())
                    .set("autoSavedToStoryboard", autoSaved);
            if (storyboardItemId != null) {
                result.set("storyboardItemId", storyboardItemId);
                result.set("frameType", frameType);
            }
            if (!finalRefUrls.isEmpty()) {
                result.set("referenceImageUrls", JSONUtil.toJsonStr(finalRefUrls));
            }
            return result.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("生成任务被中断");
        } catch (com.tonyhe.fusion.common.BusinessException e) {
            log.warn("[generate_image] 本地能力校验失败: {}", e.getMessage());
            return errorResult("[能力校验] " + e.getMessage());
        } catch (Exception e) {
            log.error("[generate_image] 生成图片失败（上游API或系统错误）", e);
            return errorResult("[生成失败] " + e.getMessage());
        }
    }

    /**
     * 获取默认图片生成模型的 ID
     */
    private AiModel resolvePreferredModel() {
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_IMAGE);
        if (defaultModel != null) {
            return defaultModel;
        }
        List<AiModel> imageModels = aiModelService.getListByType(MODEL_TYPE_IMAGE);
        if (!imageModels.isEmpty()) {
            return imageModels.get(0);
        }
        throw new IllegalStateException("未配置可用的图片生成模型");
    }

    private boolean isFirstFrameOfScene(Long storyboardItemId, String frameType) {
        if (!"first".equalsIgnoreCase(frameType)) {
            return false;
        }
        StoryboardItem item = storyboardService.getItemById(storyboardItemId);
        if (item.getStoryboardSceneId() == null) {
            return false;
        }
        return storyboardService.listItemsByScene(item.getStoryboardSceneId()).stream()
                .findFirst()
                .map(first -> storyboardItemId.equals(first.getId()))
                .orElse(false);
    }

    static void validatePromptReferenceIndexes(String prompt, List<String> finalRefUrls) {
        if (StrUtil.isBlank(prompt)) {
            return;
        }
        Matcher matcher = REFERENCE_IMAGE_INDEX_PATTERN.matcher(prompt);
        int highestReferenceIndex = 0;
        while (matcher.find()) {
            highestReferenceIndex = Math.max(highestReferenceIndex, Integer.parseInt(matcher.group(1)));
        }
        if (highestReferenceIndex > finalRefUrls.size()) {
            throw new com.tonyhe.fusion.common.BusinessException("生图提示词引用了图片" + highestReferenceIndex
                    + "，但最终仅传入 " + finalRefUrls.size() + " 张参考图。参考图不能在生成前被插入、重排或静默裁剪。");
        }
    }

    private AiModel resolvePreferredModelOrNull() {
        try {
            return resolvePreferredModel();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String describeCurrentModelCapability() {
        AiModel model = resolvePreferredModelOrNull();
        return generationModelCapabilityService.describeImageCapability(model);
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    private record ProjectProjectConfig(Long projectId, String imageResolution, String aspectRatio) {}

    private ProjectProjectConfig resolveProjectConfig(ToolExecutionContext context, Long storyboardItemId, Long paramProjectId) {
        Long projectId = context != null ? context.getProjectId() : null;
        if (projectId == null) {
            projectId = paramProjectId;
        }
        if (projectId == null && storyboardItemId != null) {
            try {
                StoryboardItem item = storyboardService.getItemById(storyboardItemId);
                if (item != null && item.getStoryboardId() != null) {
                    var storyboard = storyboardService.getById(item.getStoryboardId());
                    if (storyboard != null) {
                        projectId = storyboard.getProjectId();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (projectId == null) {
            return null;
        }
        try {
            Project project = projectService.getById(projectId);
            if (project == null || StrUtil.isBlank(project.getProperties())) {
                return new ProjectProjectConfig(projectId, null, null);
            }
            JSONObject props = JSONUtil.parseObj(project.getProperties());
            return new ProjectProjectConfig(
                    projectId,
                    props.getStr("imageResolution"),
                    props.getStr("aspectRatio"));
        } catch (Exception e) {
            log.debug("[generate_image] 读取项目配置失败: projectId={}", projectId, e);
            return new ProjectProjectConfig(projectId, null, null);
        }
    }

    /**
     * 通过 projectId 直接解析画风参考图 URL 列表（用于资产生图等无 storyboardItemId 的场景）
     */
    private List<String> resolveArtStyleRefImagesByProjectId(Long projectId) {
        if (projectId == null) return List.of();
        try {
            Project project = projectService.getById(projectId);
            if (project == null) return List.of();
            String artStyleKey = project.getArtStyle();
            if (StrUtil.isBlank(artStyleKey)) return List.of();

            List<String> refImagePaths = new ArrayList<>();
            if ("custom".equals(artStyleKey)) {
                if (StrUtil.isNotBlank(project.getArtStyleImageUrl())) {
                    refImagePaths.add(project.getArtStyleImageUrl());
                }
            } else {
                ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
                if (preset == null) return List.of();
                List<String> paths = preset.getReferenceImagePaths();
                if (paths != null) {
                    for (int i = 0; i < paths.size(); i++) {
                        String configKey = paths.size() == 1
                                ? "art_preset_url:" + artStyleKey
                                : "art_preset_url:" + artStyleKey + "_" + i;
                        String presetPublicUrl = systemConfigService.getValue(configKey);
                        refImagePaths.add(StrUtil.isNotBlank(presetPublicUrl) ? presetPublicUrl : paths.get(i));
                    }
                }
            }

            List<String> result = new ArrayList<>();
            for (String path : refImagePaths) {
                if (StrUtil.isNotBlank(path)) {
                    result.add(path);
                }
            }
            log.info("[generate_image] 通过 projectId 解析画风参考图: projectId={}, artStyleKey={}, refImages={}",
                    projectId, artStyleKey, result);
            return result;
        } catch (Exception e) {
            log.warn("[generate_image] 通过 projectId 解析画风参考图失败: projectId={}", projectId, e);
            return List.of();
        }
    }

    /**
     * 通过 storyboardItemId 反查项目画风参考图 URL 列表
     */
    private List<String> resolveArtStyleRefImagesByItemId(Long storyboardItemId) {
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item == null || item.getStoryboardId() == null) {
                return List.of();
            }
            return resolveArtStyleRefImages(item.getStoryboardId());
        } catch (Exception e) {
            log.warn("[generate_image] 通过 storyboardItemId 解析画风参考图失败: itemId={}", storyboardItemId, e);
            return List.of();
        }
    }

    /**
     * 通过 projectId 解析画风 imagePrompt
     */
    private String resolveArtStyleImagePromptByProjectId(Long projectId) {
        if (projectId == null) return null;
        try {
            Project project = projectService.getById(projectId);
            if (project == null) return null;
            return resolveArtStyleImagePrompt(project);
        } catch (Exception e) {
            log.warn("[generate_image] 通过 projectId 解析画风 imagePrompt 失败: projectId={}", projectId, e);
            return null;
        }
    }

    /**
     * 通过 storyboardItemId 反查项目画风 imagePrompt
     */
    private String resolveArtStyleImagePromptByItemId(Long storyboardItemId) {
        if (storyboardItemId == null) return null;
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item == null || item.getStoryboardId() == null) return null;
            var storyboard = storyboardService.getById(item.getStoryboardId());
            if (storyboard == null || storyboard.getProjectId() == null) return null;
            Project project = projectService.getById(storyboard.getProjectId());
            if (project == null) return null;
            return resolveArtStyleImagePrompt(project);
        } catch (Exception e) {
            log.warn("[generate_image] 通过 storyboardItemId 解析画风 imagePrompt 失败: itemId={}", storyboardItemId, e);
            return null;
        }
    }

    /**
     * 从 Project 解析画风 imagePrompt（预设画风返回 preset.imagePrompt，自定义画风返回 project.artStyleImagePrompt）
     */
    private String resolveArtStyleImagePrompt(Project project) {
        if (project == null) return null;
        String artStyleKey = project.getArtStyle();
        if (StrUtil.isBlank(artStyleKey)) return null;
        if ("custom".equals(artStyleKey)) {
            return StrUtil.blankToDefault(project.getArtStyleImagePrompt(), null);
        }
        ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
        return preset != null ? preset.getImagePrompt() : null;
    }

    /**
     * 检测 prompt 中是否已包含 imagePrompt 的核心片段。
     * 取 imagePrompt 前 30% 作为指纹进行子串匹配：
     * 画风描述开头（如"真人写实摄影风格"）是最具辨识度的部分，
     * 若 LLM 已包含此片段则认为已注入，避免完整 imagePrompt 被重复前置拼接。
     */
    static boolean isImagePromptAlreadyIncluded(String prompt, String imagePrompt) {
        if (StrUtil.isBlank(imagePrompt)) return true;
        int fingerprintEnd = Math.min(Math.max(imagePrompt.length() / 3, 20), imagePrompt.length());
        String fingerprint = imagePrompt.substring(0, fingerprintEnd);
        return prompt.contains(fingerprint);
    }

    /**
     * 构建可读文件名前缀，关联场次/镜号/帧类型信息
     * 示例：场次1-1-镜1-首帧
     */
    private String buildImageNamingHint(Long storyboardItemId, String frameType) {
        if (storyboardItemId == null) {
            return null;
        }
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);

            // 场次编号
            String sceneNumber = null;
            if (item.getStoryboardSceneId() != null) {
                try {
                    var scene = storyboardService.getSceneById(item.getStoryboardSceneId());
                    if (StrUtil.isNotBlank(scene.getSceneNumber())) {
                        sceneNumber = scene.getSceneNumber();
                    }
                } catch (Exception ignored) {
                }
            }

            // 镜号
            String shotNum = StrUtil.isNotBlank(item.getAutoShotNumber())
                    ? item.getAutoShotNumber()
                    : item.getShotNumber();
            if (StrUtil.isBlank(shotNum) && item.getSortOrder() != null) {
                shotNum = String.valueOf(item.getSortOrder());
            }

            StringBuilder sb = new StringBuilder();
            if (StrUtil.isNotBlank(sceneNumber)) {
                sb.append("场次").append(sceneNumber);
            }
            if (StrUtil.isNotBlank(shotNum)) {
                sb.append("-镜").append(shotNum);
            }
            if (StrUtil.isNotBlank(frameType)) {
                String frameLabel = "first".equalsIgnoreCase(frameType) ? "首帧"
                        : "last".equalsIgnoreCase(frameType) ? "尾帧" : frameType;
                sb.append("-").append(frameLabel);
            }

            String hint = sb.toString();
            return StrUtil.isBlank(hint) ? null : hint;
        } catch (Exception e) {
            log.warn("[generate_image] 构建命名提示失败: itemId={}", storyboardItemId, e);
            return null;
        }
    }

    /**
     * 解析项目画风参考图 URL 列表（支持多张）
     */
    private List<String> resolveArtStyleRefImages(Long storyboardId) {
        if (storyboardId == null) return List.of();
        try {
            var storyboard = storyboardService.getById(storyboardId);
            if (storyboard == null || storyboard.getProjectId() == null) return List.of();
            Project project = projectService.getById(storyboard.getProjectId());
            if (project == null) return List.of();
            String artStyleKey = project.getArtStyle();
            if (StrUtil.isBlank(artStyleKey)) return List.of();

            List<String> refImagePaths = new ArrayList<>();
            if ("custom".equals(artStyleKey)) {
                if (StrUtil.isNotBlank(project.getArtStyleImageUrl())) {
                    refImagePaths.add(project.getArtStyleImageUrl());
                }
            } else {
                ArtStylePresets.ArtStylePreset preset = ArtStylePresets.getByKey(artStyleKey);
                if (preset == null) return List.of();
                List<String> paths = preset.getReferenceImagePaths();
                if (paths != null) {
                    for (int i = 0; i < paths.size(); i++) {
                        String configKey = paths.size() == 1
                                ? "art_preset_url:" + artStyleKey
                                : "art_preset_url:" + artStyleKey + "_" + i;
                        String presetPublicUrl = systemConfigService.getValue(configKey);
                        refImagePaths.add(StrUtil.isNotBlank(presetPublicUrl) ? presetPublicUrl : paths.get(i));
                    }
                }
            }

            List<String> result = new ArrayList<>();
            for (String path : refImagePaths) {
                if (StrUtil.isNotBlank(path)) {
                    result.add(path);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[generate_image] 解析画风参考图失败: storyboardId={}", storyboardId, e);
            return List.of();
        }
    }
}
