package com.tonyhe.fusion.controller.pov;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.controller.ai.vo.AiChatReqVO;
import com.tonyhe.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.tonyhe.fusion.controller.pov.vo.*;
import com.tonyhe.fusion.service.ai.agentscope.AgentScopeAssistantService;
import com.tonyhe.fusion.service.pov.PovStoryService;
import com.tonyhe.fusion.service.pov.PovAssetService;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 第一人称（POV）猎奇故事控制器
 * <p>
 * 独立模块：提供猎奇故事流式生成、分镜管理、单镜头重生成与 FFmpeg 沉浸式视效合成导出。
 */
@Tag(name = "第一人称猎奇故事 (POV)")
@RestController
@RequestMapping("/api/pov/stories")
@RequiredArgsConstructor
@Slf4j
public class PovStoryController {

    private final PovStoryService povStoryService;
    private final PovAssetService povAssetService;
    private final AgentScopeAssistantService aiAssistantService;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;

    @Operation(summary = "启动 POV 故事流式生成（SSE）")
    @PostMapping(value = "/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> generateStream(@Valid @RequestBody PovStoryGenerateReqVO reqVO) {
        Long userId = requireCurrentUserId();
        if (reqVO.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), userId,
                    AccessAction.EDIT, PermissionCodes.POV_STORY_EDIT);
            resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), userId,
                    AccessAction.EDIT, PermissionCodes.GENERATION_CREATE);
        } else {
            authorizationService.requirePermission(userId, PermissionCodes.POV_STORY_EDIT);
            authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        }

        String topicDesc = cn.hutool.core.util.StrUtil.isNotBlank(reqVO.getTopic())
                ? reqVO.getTopic().trim()
                : "请根据【" + reqVO.getTopicGenre() + "】题材，由 AI 自主构思一个最具体验感、反常悬疑、极具窥探欲与反转冲击力的爆款第一人称故事主题";

        String userPrompt = String.format("""
                请为我创作一段第一人称（POV）猎奇悬疑故事：
                【主题/灵感】：%s
                【题材类型】：%s
                【配音音色】：%s
                【氛围风格】：%s
                请严格按照 JSON 格式输出故事标题、前3秒钩子文案、完整独白脚本、故事独立资产包，
                以及 6~8 个可直接生成视频的第一人称分镜。每个镜头必须包含资产引用和逐角色对白映射。
                """, topicDesc, reqVO.getTopicGenre(), reqVO.getVoiceId(), reqVO.getBgmStyle());

        AiChatReqVO chatReqVO = new AiChatReqVO()
                .setMessage(userPrompt)
                .setAgentType("pov_story_architect")
                .setCategory("POV_STORY")
                .setModelId(reqVO.getModelId())
                .setProjectId(reqVO.getProjectId());

        return aiAssistantService.stream(chatReqVO, userId);
    }

    @Operation(summary = "从 AI 输出内容解析并保存为故事")
    @PostMapping("/save-from-ai")
    public CommonResult<PovStoryRespVO> saveFromAi(@Valid @RequestBody PovSaveFromAiReqVO reqVO) {
        Long userId = requireCurrentUserId();
        if (reqVO.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), userId,
                    AccessAction.EDIT, PermissionCodes.POV_STORY_EDIT);
        } else {
            authorizationService.requirePermission(userId, PermissionCodes.POV_STORY_EDIT);
        }
        PovStoryRespVO respVO = povStoryService.saveStoryFromAiJson(
                reqVO.getAiOutputText(),
                userId,
                reqVO.getProjectId(),
                reqVO.getVoiceId(),
                reqVO.getBgmStyle(),
                reqVO.getVisibility()
        );
        return CommonResult.success(respVO);
    }

    @Operation(summary = "分页查询故事列表")
    @GetMapping("/page")
    public CommonResult<PageResult<PovStoryRespVO>> page(@Valid PovStoryPageReqVO reqVO) {
        Long userId = requireCurrentUserId();
        if (reqVO.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(reqVO.getProjectId(), userId,
                    AccessAction.VIEW, PermissionCodes.POV_STORY_VIEW);
        } else {
            authorizationService.requirePermission(userId, PermissionCodes.POV_STORY_VIEW);
        }
        return CommonResult.success(povStoryService.pageStories(reqVO, userId));
    }

    @Operation(summary = "获取故事详情（含分镜列表）")
    @GetMapping("/{id}")
    public CommonResult<PovStoryRespVO> getDetail(@PathVariable Long id) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(id, userId);
        return CommonResult.success(povStoryService.getStoryDetail(id, userId));
    }

    @Operation(summary = "更新故事基础信息")
    @PutMapping("/{id}")
    public CommonResult<Boolean> updateStory(@PathVariable Long id, @RequestBody PovStoryUpdateReqVO reqVO) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(id, userId, AccessAction.EDIT);
        povStoryService.updateStory(id, reqVO, userId);
        return CommonResult.success(true);
    }

    @Operation(summary = "删除故事")
    @DeleteMapping("/{id}")
    public CommonResult<Boolean> deleteStory(@PathVariable Long id) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(id, userId, AccessAction.DELETE);
        povStoryService.deleteStory(id, userId);
        return CommonResult.success(true);
    }

    @Operation(summary = "更新单个分镜")
    @PutMapping("/{storyId}/shots/{shotId}")
    public CommonResult<Boolean> updateShot(
            @PathVariable Long storyId,
            @PathVariable Long shotId,
            @RequestBody PovShotUpdateReqVO reqVO) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        povStoryService.updateShot(storyId, shotId, reqVO, userId);
        return CommonResult.success(true);
    }

    @Operation(summary = "兼容接口：POV 单镜直接生成视频")
    @PostMapping("/{storyId}/shots/{shotId}/generate-media")
    public CommonResult<String> generateShotMedia(
            @PathVariable Long storyId,
            @PathVariable Long shotId,
            @RequestParam(defaultValue = "VIDEO") String mediaType,
            @RequestParam(required = false) Long modelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        String taskId = povStoryService.generateShotMedia(storyId, shotId, mediaType, modelId, userId);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "单镜直接生成视频（使用 POV 故事资产与角色参考音频）")
    @PostMapping("/{storyId}/shots/{shotId}/generate-video")
    public CommonResult<String> generateShotVideo(
            @PathVariable Long storyId,
            @PathVariable Long shotId,
            @RequestParam(required = false) Long modelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        return CommonResult.success(povStoryService.generateShotVideo(storyId, shotId, modelId, userId));
    }

    @Operation(summary = "已弃用：POV 镜头不再单独生成配音")
    @PostMapping("/{storyId}/shots/{shotId}/generate-audio")
    public CommonResult<String> generateShotAudio(
            @PathVariable Long storyId,
            @PathVariable Long shotId,
            @RequestParam(required = false) Long ttsModelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        String taskId = povStoryService.generateShotAudio(storyId, shotId, ttsModelId, userId);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "批量直接生成所有镜头视频")
    @PostMapping("/{storyId}/generate-all")
    public CommonResult<Integer> generateAllShots(
            @PathVariable Long storyId,
            @RequestParam(defaultValue = "VIDEO") String mediaType,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) Long ttsModelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        int count = povStoryService.generateAllShotsMedia(storyId, mediaType, modelId, ttsModelId, userId);
        return CommonResult.success(count);
    }

    @Operation(summary = "查询 POV 故事独立资产")
    @GetMapping("/{storyId}/assets")
    public CommonResult<java.util.List<PovAssetRespVO>> listAssets(@PathVariable Long storyId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId);
        return CommonResult.success(povAssetService.listAssets(storyId, userId));
    }

    @Operation(summary = "生成单个 POV 资产图片")
    @PostMapping("/{storyId}/assets/items/{itemId}/generate-image")
    public CommonResult<String> generateAssetImage(
            @PathVariable Long storyId,
            @PathVariable Long itemId,
            @RequestParam(required = false) Long modelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        return CommonResult.success(povAssetService.generateAssetItem(storyId, itemId, modelId, userId));
    }

    @Operation(summary = "批量生成 POV 故事资产图片")
    @PostMapping("/{storyId}/assets/generate-all")
    public CommonResult<Integer> generateAllAssets(
            @PathVariable Long storyId,
            @RequestParam(required = false) Long modelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        return CommonResult.success(povAssetService.generateAllAssets(storyId, modelId, userId));
    }

    @Operation(summary = "更新 POV 角色音色快照")
    @PutMapping("/{storyId}/assets/{assetId}/voice")
    public CommonResult<PovAssetRespVO> updateAssetVoice(
            @PathVariable Long storyId,
            @PathVariable Long assetId,
            @Valid @RequestBody PovAssetVoiceUpdateReqVO reqVO) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        return CommonResult.success(povAssetService.updateVoice(storyId, assetId, reqVO, userId));
    }

    @Operation(summary = "为单个 POV 角色生成参考音频")
    @PostMapping("/{storyId}/assets/{assetId}/generate-reference-audio")
    public CommonResult<String> generateReferenceAudio(
            @PathVariable Long storyId,
            @PathVariable Long assetId,
            @RequestParam(required = false) Long ttsModelId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.POV_VOICE_GENERATE);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        return CommonResult.success(povAssetService.generateReferenceAudio(
                storyId, assetId, ttsModelId, userId));
    }

    @Operation(summary = "一键合成 POV 沉浸式完整视频")
    @PostMapping("/{storyId}/compose")
    public CommonResult<String> composeStoryVideo(@PathVariable Long storyId) {
        Long userId = requireCurrentUserId();
        resourceAccessService.checkPovStoryAccess(storyId, userId, AccessAction.EDIT);
        String videoUrl = povStoryService.composeStoryVideo(storyId, userId);
        return CommonResult.success(videoUrl);
    }
}
