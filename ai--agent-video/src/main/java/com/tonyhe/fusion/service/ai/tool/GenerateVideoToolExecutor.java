package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.VideoItem;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import com.tonyhe.fusion.service.storyboard.SceneStatePromptCompiler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 生视频工具（generate_video）
 * <p>
 * 职责：解析参数 → 构建 VideoTask → 提交到队列并同步等待结果。
 * <p>
 * 排队、并发控制、策略路由等全部由 {@link VideoGenerationConsumer} 统一处理，
 * 本工具通过 {@link VideoGenerationConsumer#submitAndWait} 复用其完整流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerateVideoToolExecutor implements ToolExecutor {

    /** 模型类型常量：视频生成 */
    private static final int MODEL_TYPE_VIDEO = 3;

    /** 同步等待超时时间（10 分钟，视频生成耗时较长） */
    private static final long WAIT_TIMEOUT_MS = 10 * 60 * 1000L;

    private final AiModelService aiModelService;
    private final VideoGenerationService videoGenerationService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final ProjectService projectService;
    private final StoryboardService storyboardService;
    private final SceneStatePromptCompiler sceneStatePromptCompiler;

    @Override
    public String getToolName() {
        return "generate_video";
    }

    @Override
    public String getDisplayName() {
        return "AI 生成视频";
    }

    @Override
    public String getToolDescription() {
        return """
                生成AI视频。根据提示词和可选的参考图片生成视频片段。

                适用场景：
                1. 为分镜镜头生成视频：根据画面描述、运镜指令和参考首帧图生成视频
                2. 文本生成视频：纯文字描述生成视频
                3. 多模态参考生视频：传入角色/道具/场景的参考图片、参考视频、参考音频，提升画面中人物和物品的一致性

                重要提示：
                - 提示词应详细描述画面内容、运镜方式、环境氛围
                - 提供 firstFrameImageUrl 可大幅提升画面一致性
                - 提供 referenceImageUrls 可锁定角色形象、道具外观等（Seedance 2.0 多模态参考特性）
                - 提供 referenceVideoUrls 可参考视频的动作、运镜、特效等
                - 提供 referenceAudioUrls 可参考音色、音乐旋律、对话内容等
                - 使用多模态参考时，提示词中用`图片1`/`视频1`/`音频1`等指代对应的参考素材
                - 生成耗时较长（通常 1-5 分钟），请耐心等待
                - 如果你打算传首帧、尾帧、参考图、参考视频或参考音频，或不确定当前默认模型是否支持这些字段，请先调用 get_generation_model_capabilities
                
                %s
                """.formatted(describeCurrentModelCapability());
    }

    @Override
    public String getParametersSchema() {
            AiModel model = resolvePreferredModelOrNull();
            GenerationModelCapabilityService.VideoModelCapability capability = model != null
                ? generationModelCapabilityService.resolveVideoCapability(model)
                : null;

            String firstFrameDescription = capability != null && !capability.supportsFirstFrame()
                ? "当前默认模型不支持 firstFrameImageUrl，请不要传该字段"
                : "首帧参考图片URL（图生视频模式，强烈建议提供）";
            String lastFrameDescription = capability != null && !capability.supportsLastFrame()
                ? "当前默认模型不支持 lastFrameImageUrl，请不要传该字段"
                : "尾帧参考图片URL（可选）";
            String referenceImageDescription = capability != null && !capability.supportsReferenceImages()
                ? "当前默认模型不支持 referenceImageUrls，请不要传该字段"
                : "多模态参考图片URL列表，用于锁定角色形象、道具外观、场景参考等";
            String referenceVideoDescription = capability != null && !capability.supportsReferenceVideos()
                ? "当前默认模型不支持 referenceVideoUrls，请不要传该字段"
                : "参考视频URL列表，用于参考动作表现、运镜方式、特效风格等";
            String referenceAudioDescription = capability != null && !capability.supportsReferenceAudios()
                ? "当前默认模型不支持 referenceAudioUrls，请不要传该字段"
                : "参考音频列表，用于音色克隆。推荐使用结构化格式：[{\"imageUrl\": \"角色参考图URL\", \"audioUrl\": \"音频URL\"}]，imageUrl 必须与 referenceImageUrls 中对应角色的 URL 完全一致，以便系统精确匹配音色与角色形象。也兼容扁平格式 [\"音频URL\"]（按位置顺序匹配，不推荐）";

            return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                    .set("prompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "视频生成提示词，描述画面内容和运镜方式"))
                    .set("firstFrameImageUrl", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", firstFrameDescription))
                    .set("lastFrameImageUrl", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", lastFrameDescription))
                    .set("referenceImageUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", referenceImageDescription))
                    .set("referenceVideoUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", referenceVideoDescription))
                    .set("referenceAudioUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj()
                            .set("type", "object")
                            .set("properties", JSONUtil.createObj()
                                .set("imageUrl", JSONUtil.createObj().set("type", "string").set("description", "角色参考图URL，必须与 referenceImageUrls 中对应角色的 URL 完全一致"))
                                .set("audioUrl", JSONUtil.createObj().set("type", "string").set("description", "该角色的音色参考音频URL"))))
                        .set("description", referenceAudioDescription))
                    .set("ratio", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "画面比例（请勿传此参数，系统会自动从项目配置读取 aspectRatio，强制使用项目配置的比例）"))
                    .set("resolution", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "视频分辨率，如 720p、1080p、4K（默认从项目配置读取，无则使用模型默认）"))
                    .set("duration", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "视频时长（秒）。不要直接复制分镜的 duration 字段，应根据实际内容复杂度调整：纯空镜/静态场景 3-5秒，单人物微动作 4-5秒，单人物中等动作 5-6秒，多人物交互/复杂动作 6-8秒。宁短勿长。"))
                    .set("storyboardItemId", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "分镜条目ID（可选），传入则生成成功后自动回写视频URL到分镜，无需再调用 update_storyboard_item_video"))
                    .set("cameraFixed", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "是否固定镜头（不做运动），默认 false"))
                    .set("namingHint", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "（可选）视频文件命名提示，用于本地持久化时生成可读文件名。分镜视频场景无需传此参数，系统会自动生成场次镜号。")))
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

            String firstFrameImageUrl = params.getStr("firstFrameImageUrl");
            String lastFrameImageUrl = params.getStr("lastFrameImageUrl");
            Long storyboardItemId = params.getLong("storyboardItemId");
            Long paramProjectId = params.getLong("projectId");

            // 从项目配置读取默认的画面比例和视频分辨率
            ProjectVideoConfig projectConfig = resolveProjectConfig(context, storyboardItemId, paramProjectId);
            String defaultRatio = projectConfig != null && StrUtil.isNotBlank(projectConfig.aspectRatio())
                    ? projectConfig.aspectRatio() : "16:9";
            if (projectConfig == null) {
                log.warn("[generate_video] 未读取到项目配置，使用默认比例: projectId={}, defaultRatio={}",
                        context != null ? context.getProjectId() : null, defaultRatio);
            }
            String defaultResolution = projectConfig != null ? projectConfig.videoResolution() : null;

            // 画面比例：项目配置有 aspectRatio 时强制使用，忽略 agent 传入的值，防止比例不一致
            String ratio;
            if (projectConfig != null && StrUtil.isNotBlank(projectConfig.aspectRatio())) {
                ratio = projectConfig.aspectRatio();
                String agentRatio = params.getStr("ratio");
                if (StrUtil.isNotBlank(agentRatio) && !agentRatio.equals(ratio)) {
                    log.warn("[generate_video] Agent 传入比例 {} 与项目配置 {} 不一致，强制使用项目配置",
                            agentRatio, ratio);
                }
            } else {
                ratio = params.getStr("ratio", "16:9");
            }
            String resolution = params.getStr("resolution", defaultResolution);
            Integer duration = params.getInt("duration", 5);
            Boolean cameraFixed = params.getBool("cameraFixed", false);

            // 解析多模态参考图片列表
            List<String> referenceImageUrlList = new ArrayList<>();
            cn.hutool.json.JSONArray refImagesArr = params.getJSONArray("referenceImageUrls");
            if (refImagesArr != null) {
                for (int i = 0; i < refImagesArr.size(); i++) {
                    String url = refImagesArr.getStr(i);
                    if (StrUtil.isNotBlank(url)) {
                        referenceImageUrlList.add(url);
                    }
                }
            }
            String referenceImageUrls = CollUtil.isEmpty(referenceImageUrlList)
                    ? null : JSONUtil.toJsonStr(referenceImageUrlList);

            // 解析参考视频列表
            String referenceVideoUrls = parseUrlArray(params, "referenceVideoUrls");

            // 解析参考音频列表（支持两种格式：扁平 ["url"] 和结构化 [{"imageUrl":"...","audioUrl":"..."}]）
            String referenceAudioUrls = parseAudioUrlArray(params, "referenceAudioUrls");

            // 确定生成模式
            String generateMode = StrUtil.isNotBlank(firstFrameImageUrl) ? "image2video" : "text2video";

            AiModel model = resolvePreferredModel();

            // 构建可读文件名前缀（用于本地存储时关联镜头信息）
            String confirmedVideoPrompt = prompt;
            if (storyboardItemId != null) {
                prompt = sceneStatePromptCompiler.compileVideoPrompt(storyboardItemId, prompt);
            }
            String namingHint = params.getStr("namingHint");
            if (StrUtil.isBlank(namingHint)) {
                namingHint = buildVideoNamingHint(storyboardItemId);
            }

            Long effectiveProjectId = projectConfig != null && projectConfig.projectId() != null
                    ? projectConfig.projectId() : (context != null ? context.getProjectId() : null);

            // 构建生视频任务
            VideoTask task = VideoTask.builder()
                    .prompt(prompt)
                    .generateMode(generateMode)
                    .firstFrameImageUrl(firstFrameImageUrl)
                    .lastFrameImageUrl(lastFrameImageUrl)
                    .referenceImageUrls(referenceImageUrls)
                    .referenceVideoUrls(referenceVideoUrls)
                    .referenceAudioUrls(referenceAudioUrls)
                    .ratio(ratio)
                    .resolution(resolution)
                    .duration(duration)
                    .cameraFixed(cameraFixed)
                    .projectId(effectiveProjectId)
                    .modelId(model.getId())
                    .modelCode(model.getCode())
                    .textModelCode(context != null ? context.getTextModelCode() : null)
                    .count(1)
                    .userId(context != null ? context.getUserId() : null)
                    .category(namingHint)
                    .build();

                    generationModelCapabilityService.trimVideoTaskInputs(model, task);
                    generationModelCapabilityService.validateVideoTask(model, task);

                    log.info("[generate_video] 提交生视频任务: prompt={}, mode={}, ratio={}, resolution={}, duration={}s, modelId={}, modelCode={}, 首帧: {}, 参考图: {}张",
                        StrUtil.sub(prompt, 0, 80), generateMode, ratio, resolution, duration, model.getId(), model.getCode(),
                    firstFrameImageUrl != null ? "有" : "无",
                    referenceImageUrlList.size());

            // 提交到队列并同步等待结果
            VideoTask completed = videoGenerationConsumer.submitAndWait(task, WAIT_TIMEOUT_MS);

            // 从完成的任务中获取生成的视频 URL
            List<VideoItem> items = videoGenerationService.listItems(completed.getId());
            VideoItem videoItem = items.stream()
                    .filter(item -> StrUtil.isNotBlank(item.getVideoUrl()))
                    .findFirst()
                    .orElse(null);

            if (videoItem == null) {
                return errorResult("生成完成但未获取到视频 URL");
            }

            log.info("[generate_video] 生成成功: videoUrl={}, coverUrl={}",
                    videoItem.getVideoUrl(), videoItem.getCoverUrl());

            // 自动回写分镜
            if (storyboardItemId != null) {
                try {
                    StoryboardItem sbItem = storyboardService.getItemById(storyboardItemId);
                    sbItem.setGeneratedVideoUrl(videoItem.getVideoUrl());
                    // 只持久化用户确认的业务提示词，服务端状态约束在每次生成时重新编译。
                    sbItem.setVideoPrompt(confirmedVideoPrompt);
                    sbItem.setGeneratedVideoStateHash(sbItem.getSceneStateHash());
                    if (StrUtil.isNotBlank(videoItem.getCoverUrl())) {
                        sbItem.setGeneratedImageUrl(videoItem.getCoverUrl());
                    }
                    // 视频最后一帧作为尾帧回写：当镜头没有单独生成尾帧时，用视频返回的最后一帧补充
                    if (StrUtil.isBlank(sbItem.getLastFrameImageUrl())
                            && StrUtil.isNotBlank(videoItem.getLastFrameUrl())) {
                        sbItem.setLastFrameImageUrl(videoItem.getLastFrameUrl());
                        sbItem.setLastFrameStateHash(sbItem.getStateAfterHash());
                        log.info("[generate_video] 视频末帧补充为尾帧: itemId={}, lastFrameUrl={}",
                                storyboardItemId, videoItem.getLastFrameUrl());
                    }
                    storyboardService.updateItem(sbItem);
                    log.info("[generate_video] 自动回写分镜: itemId={}, videoUrl={}", storyboardItemId, videoItem.getVideoUrl());
                } catch (Exception e) {
                    log.warn("[generate_video] 自动回写分镜失败: itemId={}, error={}", storyboardItemId, e.getMessage());
                }
            }

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("videoUrl", videoItem.getVideoUrl())
                    .set("coverUrl", videoItem.getCoverUrl())
                    .set("duration", videoItem.getDuration())
                    .set("prompt", prompt)
                    .set("storyboardItemId", storyboardItemId)
                    .toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("生成任务被中断");
        } catch (com.tonyhe.fusion.common.BusinessException e) {
            log.warn("[generate_video] 本地能力校验失败: {}", e.getMessage());
            return errorResult("[能力校验] " + e.getMessage());
        } catch (Exception e) {
            log.error("[generate_video] 生成视频失败（上游API或系统错误）", e);
            return errorResult("[生成失败] " + e.getMessage());
        }
    }

    /**
     * 获取默认视频生成模型的 ID
     */
    private AiModel resolvePreferredModel() {
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_VIDEO);
        if (defaultModel != null) {
            return defaultModel;
        }
        List<AiModel> videoModels = aiModelService.getListByType(MODEL_TYPE_VIDEO);
        if (!videoModels.isEmpty()) {
            return videoModels.get(0);
        }
        throw new IllegalStateException("未配置可用的视频生成模型");
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
        return generationModelCapabilityService.describeVideoCapability(model);
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    private record ProjectVideoConfig(Long projectId, String aspectRatio, String videoResolution) {}

    private ProjectVideoConfig resolveProjectConfig(ToolExecutionContext context, Long storyboardItemId, Long paramProjectId) {
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
                return new ProjectVideoConfig(projectId, null, null);
            }
            JSONObject props = JSONUtil.parseObj(project.getProperties());
            return new ProjectVideoConfig(
                    projectId,
                    props.getStr("aspectRatio"),
                    props.getStr("videoResolution"));
        } catch (Exception e) {
            log.debug("[generate_video] 读取项目配置失败: projectId={}", projectId, e);
            return new ProjectVideoConfig(projectId, null, null);
        }
    }

    /**
     * 从参数中解析 URL 数组字段，返回 JSON 字符串或 null
     */
    private String parseUrlArray(JSONObject params, String fieldName) {
        cn.hutool.json.JSONArray arr = params.getJSONArray(fieldName);
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String url = arr.getStr(i);
            if (StrUtil.isNotBlank(url)) {
                urls.add(url);
            }
        }
        return CollUtil.isEmpty(urls) ? null : JSONUtil.toJsonStr(urls);
    }

    /**
     * 解析参考音频数组，支持两种格式：
     * - 扁平格式：["audio1.mp3", "audio2.mp3"]（向后兼容，无 imageUrl 映射）
     * - 结构化格式：[{"imageUrl": "角色参考图URL", "audioUrl": "音频URL"}]（推荐，精确匹配角色与音色）
     * 统一输出为结构化 JSON 数组。
     */
    private String parseAudioUrlArray(JSONObject params, String fieldName) {
        cn.hutool.json.JSONArray arr = params.getJSONArray(fieldName);
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<JSONObject> audioEntries = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (element instanceof JSONObject audioObj) {
                String imageUrl = audioObj.getStr("imageUrl");
                String audioUrl = audioObj.getStr("audioUrl");
                if (StrUtil.isNotBlank(audioUrl)) {
                    JSONObject entry = JSONUtil.createObj().set("audioUrl", audioUrl.trim());
                    if (StrUtil.isNotBlank(imageUrl)) {
                        entry.set("imageUrl", imageUrl.trim());
                    }
                    audioEntries.add(entry);
                }
            } else if (element instanceof String audioUrl && StrUtil.isNotBlank(audioUrl)) {
                JSONObject entry = JSONUtil.createObj().set("audioUrl", audioUrl.trim());
                audioEntries.add(entry);
            }
        }
        return CollUtil.isEmpty(audioEntries) ? null : JSONUtil.toJsonStr(audioEntries);
    }

    /**
     * 构建可读文件名前缀，关联场次/镜号信息
     * 示例：场次1-1-镜1
     */
    private String buildVideoNamingHint(Long storyboardItemId) {
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

            String hint = sb.toString();
            return StrUtil.isBlank(hint) ? null : hint;
        } catch (Exception e) {
            log.warn("[generate_video] 构建命名提示失败: itemId={}", storyboardItemId, e);
            return null;
        }
    }
}
