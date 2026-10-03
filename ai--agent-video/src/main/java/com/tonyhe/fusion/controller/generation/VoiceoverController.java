package com.tonyhe.fusion.controller.generation;

import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.service.generation.voiceover.VoiceConfigService;
import com.tonyhe.fusion.service.generation.voiceover.VoiceoverService;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 配音控制器
 */
@Tag(name = "AI 配音")
@RestController
@RequestMapping("/api/storyboard/voiceover")
@RequiredArgsConstructor
@Slf4j
public class VoiceoverController {

    private final VoiceoverService voiceoverService;
    private final VoiceConfigService voiceConfigService;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;

    @Operation(summary = "预览配音台词（按分镜集/场次）")
    @GetMapping("/{storyboardId}/preview")
    public CommonResult<List<VoiceoverService.ItemDialoguePreview>> previewDialogue(
            @PathVariable Long storyboardId,
            @RequestParam Long episodeId,
            @RequestParam(required = false) Long sceneId) {
        resourceAccessService.checkStoryboardAccess(storyboardId,
                com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId());
        return CommonResult.success(voiceoverService.previewDialogue(storyboardId, episodeId, sceneId));
    }

    @Operation(summary = "批量生成配音（按分镜集，可指定镜头）")
    @PostMapping("/{storyboardId}/generate")
    public CommonResult<String> generateVoiceover(
            @PathVariable Long storyboardId,
            @RequestBody VoiceoverGenerateReqVO req) {
        if (req.getEpisodeId() == null) {
            throw new BusinessException("episodeId 不能为空");
        }
        Long userId = com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId();
        resourceAccessService.checkStoryboardGenerateAccess(storyboardId, userId);
        int count = voiceoverService.generateVoiceover(storyboardId, req.getEpisodeId(), req.getItemIds(), userId);
        return CommonResult.success("已提交 " + count + " 条配音任务");
    }

    @Operation(summary = "单条重生成")
    @PostMapping("/{audioTaskId}/regenerate")
    public CommonResult<String> regenerate(@PathVariable Long audioTaskId) {
        resourceAccessService.checkAudioTaskAccess(audioTaskId,
                com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId(), AccessAction.EDIT);
        String taskId = voiceoverService.regenerate(audioTaskId);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "查询配音列表（按集）")
    @GetMapping("/list")
    public CommonResult<List<AudioTask>> list(@RequestParam Long episodeId) {
        resourceAccessService.checkStoryboardEpisodeAccess(episodeId,
                com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId());
        return CommonResult.success(voiceoverService.listByEpisode(episodeId));
    }

    @Operation(summary = "获取角色音色配置")
    @GetMapping("/voice-config/{assetId}")
    public CommonResult<VoiceConfigService.VoiceConfig> getVoiceConfig(@PathVariable Long assetId) {
        resourceAccessService.checkAssetAccess(assetId,
                com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId());
        return CommonResult.success(voiceConfigService.getVoiceConfig(assetId));
    }

    @Operation(summary = "设置角色音色配置")
    @PostMapping("/voice-config/{assetId}")
    public CommonResult<VoiceConfigService.VoiceConfig> setVoiceConfig(
            @PathVariable Long assetId,
            @RequestBody VoiceConfigService.VoiceConfig config) {
        if (StrUtil.isBlank(config.getVoiceId())) {
            throw new BusinessException("voiceId 不能为空");
        }
        resourceAccessService.checkAssetAccess(assetId,
                com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId(), AccessAction.EDIT);
        return CommonResult.success(voiceConfigService.setVoiceConfig(assetId, config));
    }

    @Operation(summary = "生成参考音频样本（用当前音色合成一段样本音频）")
    @PostMapping("/voice-config/{assetId}/generate-sample")
    public CommonResult<VoiceConfigService.VoiceConfig> generateSampleAudio(
            @PathVariable Long assetId) {
        Long userId = com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId();
        resourceAccessService.checkAssetAccess(assetId, userId, AccessAction.EDIT);
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        return CommonResult.success(voiceConfigService.generateSampleAudio(assetId));
    }

    @Data
    public static class VoiceoverGenerateReqVO {
        private Long episodeId;
        private List<Long> itemIds;
    }
}
