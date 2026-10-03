package com.tonyhe.fusion.service.pov;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.controller.pov.vo.*;
import com.tonyhe.fusion.convert.pov.PovConvert;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.generation.VideoItem;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.entity.pov.*;
import com.tonyhe.fusion.mapper.pov.*;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.generation.consumer.VideoGenerationConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 第一人称猎奇故事独立业务服务。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PovStoryService {

    private static final int MODEL_TYPE_VIDEO = 3;
    private static final String DEFAULT_NEGATIVE = "narrator face, selfie, third-person perspective, identity drift, costume change, object redesign, extra hands, distorted fingers, extra limbs, watermark, subtitles, cartoon";

    private final PovStoryMapper storyMapper;
    private final PovShotMapper shotMapper;
    private final PovAssetMapper assetMapper;
    private final PovAssetItemMapper assetItemMapper;
    private final PovShotAssetMapper shotAssetMapper;
    private final PovShotSpeakerMapper shotSpeakerMapper;
    private final PovAssetService povAssetService;
    private final PovVideoComposeService povVideoComposeService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final VideoGenerationService videoGenerationService;
    private final AudioGenerationService audioGenerationService;
    private final AiModelService aiModelService;
    private final AiModelMetadataResolver modelMetadataResolver;
    private final GenerationModelCapabilityService capabilityService;
    private final ProjectService projectService;
    private final UserService userService;
    private final TeamService teamService;

    public PageResult<PovStoryRespVO> pageStories(PovStoryPageReqVO reqVO, Long userId) {
        Page<PovStory> page = new Page<>(reqVO.getPageNo(), reqVO.getPageSize());
        LambdaQueryWrapper<PovStory> wrapper = new LambdaQueryWrapper<>();
        if (!userService.isAdmin(userId)) {
            List<com.tonyhe.fusion.entity.project.Project> projects = projectService.listAccessibleByUser(userId);
            List<Long> accessibleProjectIds = projects.stream().map(com.tonyhe.fusion.entity.project.Project::getId).toList();
            Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
            List<Long> teamProjectIds = projects.stream()
                    .filter(p -> Integer.valueOf(2).equals(p.getOwnerType())
                            && currentTeamId != null && currentTeamId.equals(p.getOwnerId()))
                    .map(com.tonyhe.fusion.entity.project.Project::getId).toList();
            wrapper.and(scope -> {
                scope.eq(PovStory::getUserId, userId);
                if (!accessibleProjectIds.isEmpty()) {
                    scope.or(project -> project.eq(PovStory::getVisibility, "PROJECT")
                            .in(PovStory::getProjectId, accessibleProjectIds));
                }
                if (!teamProjectIds.isEmpty()) {
                    scope.or(team -> team.eq(PovStory::getVisibility, "TEAM")
                            .in(PovStory::getProjectId, teamProjectIds));
                }
            });
        }
        wrapper
                .eq(StrUtil.isNotBlank(reqVO.getTopicGenre()), PovStory::getTopicGenre, reqVO.getTopicGenre())
                .eq(StrUtil.isNotBlank(reqVO.getStatus()), PovStory::getStatus, reqVO.getStatus())
                .eq(reqVO.getProjectId() != null, PovStory::getProjectId, reqVO.getProjectId())
                .and(StrUtil.isNotBlank(reqVO.getKeyword()), w -> w.like(PovStory::getTitle, reqVO.getKeyword())
                        .or().like(PovStory::getFullScript, reqVO.getKeyword()))
                .orderByDesc(PovStory::getId);
        Page<PovStory> result = storyMapper.selectPage(page, wrapper);
        return new PageResult<>(PovConvert.INSTANCE.convertList(result.getRecords()), result.getTotal());
    }

    public PovStoryRespVO getStoryDetail(Long storyId, Long userId) {
        PovStory story = getStoryEntity(storyId, userId);
        List<PovShot> shots = listShots(storyId);
        PovStoryRespVO vo = PovConvert.INSTANCE.convert(story);
        vo.setAssets(povAssetService.listAssets(storyId));
        vo.setShots(buildShotResponses(storyId, shots));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public PovStoryRespVO saveStoryFromAiJson(String aiOutputText, Long userId, Long projectId,
                                               String voiceId, String bgmStyle, String visibility) {
        JSONObject json = extractJson(aiOutputText);
        if (json == null) throw new BusinessException("AI 生成的内容未能解析为合法的 JSON 故事结构");
        PovStory story = PovStory.builder()
                .userId(userId).projectId(projectId)
                .title(json.getStr("title", "未命名第一人称猎奇故事"))
                .hookText(json.getStr("hookText", ""))
                .topicGenre(json.getStr("topicGenre", "URBAN_MYSTERY"))
                .fullScript(json.getStr("fullScript", ""))
                .totalDurationEstimate(json.getInt("totalDurationEstimate", 50))
                .voiceId(StrUtil.blankToDefault(voiceId, "longanlingxin"))
                .bgmStyle(StrUtil.blankToDefault(bgmStyle, "SUSPENSE_HEARTBEAT"))
                .visibility(normalizeVisibility(visibility, projectId))
                .status("DRAFT").build();
        storyMapper.insert(story);
        AssetIndex assetIndex = saveAssets(story, json.getJSONArray("assets"));
        saveShots(story, json.getJSONArray("shots"), assetIndex);
        return getStoryDetail(story.getId(), userId);
    }

    public void updateStory(Long id, PovStoryUpdateReqVO reqVO, Long userId) {
        PovStory story = getStoryEntity(id, userId);
        if (StrUtil.isNotBlank(reqVO.getTitle())) story.setTitle(reqVO.getTitle());
        if (StrUtil.isNotBlank(reqVO.getHookText())) story.setHookText(reqVO.getHookText());
        if (StrUtil.isNotBlank(reqVO.getFullScript())) story.setFullScript(reqVO.getFullScript());
        if (StrUtil.isNotBlank(reqVO.getVoiceId())) story.setVoiceId(reqVO.getVoiceId());
        if (StrUtil.isNotBlank(reqVO.getBgmStyle())) story.setBgmStyle(reqVO.getBgmStyle());
        if (StrUtil.isNotBlank(reqVO.getVisibility())) {
            story.setVisibility(normalizeVisibility(reqVO.getVisibility(), story.getProjectId()));
        }
        storyMapper.updateById(story);
    }

    public void updateShot(Long storyId, Long shotId, PovShotUpdateReqVO reqVO, Long userId) {
        getStoryEntity(storyId, userId);
        PovShot shot = requireShot(storyId, shotId);
        boolean affectsVideo = false;
        if (StrUtil.isNotBlank(reqVO.getVoiceText())) shot.setVoiceText(reqVO.getVoiceText());
        if (StrUtil.isNotBlank(reqVO.getVisualPrompt())) { shot.setVisualPrompt(reqVO.getVisualPrompt()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getVideoPrompt())) { shot.setVideoPrompt(reqVO.getVideoPrompt()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getNegativePrompt())) { shot.setNegativePrompt(reqVO.getNegativePrompt()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getCameraView())) { shot.setCameraView(reqVO.getCameraView()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getCameraMovement())) { shot.setCameraMovement(reqVO.getCameraMovement()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getSfxCue())) shot.setSfxCue(reqVO.getSfxCue());
        if (reqVO.getDurationSec() != null) { shot.setDurationSec(reqVO.getDurationSec()); affectsVideo = true; }
        if (StrUtil.isNotBlank(reqVO.getVideoUrl())) shot.setVideoUrl(reqVO.getVideoUrl());
        if (StrUtil.isNotBlank(reqVO.getAudioUrl())) shot.setAudioUrl(reqVO.getAudioUrl());
        if (affectsVideo && StrUtil.isNotBlank(shot.getVideoUrl())) shot.setVideoStatus("STALE");
        shotMapper.updateById(shot);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStory(Long storyId, Long userId) {
        PovStory story = getStoryEntity(storyId, userId);
        List<PovAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<PovAsset>().eq(PovAsset::getStoryId, storyId));
        shotAssetMapper.delete(new LambdaQueryWrapper<PovShotAsset>().eq(PovShotAsset::getStoryId, storyId));
        shotSpeakerMapper.delete(new LambdaQueryWrapper<PovShotSpeaker>().eq(PovShotSpeaker::getStoryId, storyId));
        if (!assets.isEmpty()) assetItemMapper.delete(new LambdaQueryWrapper<PovAssetItem>()
                .in(PovAssetItem::getAssetId, assets.stream().map(PovAsset::getId).toList()));
        assetMapper.delete(new LambdaQueryWrapper<PovAsset>().eq(PovAsset::getStoryId, storyId));
        shotMapper.delete(new LambdaQueryWrapper<PovShot>().eq(PovShot::getStoryId, storyId));
        storyMapper.deleteById(story.getId());
    }

    /** V1 路由兼容：POV 新链路只允许直接生成视频。 */
    public String generateShotMedia(Long storyId, Long shotId, String mediaType, Long modelId, Long userId) {
        if (!"VIDEO".equalsIgnoreCase(mediaType)) throw new BusinessException("POV 镜头不再生成首尾帧或镜头图片，请直接生成视频");
        return generateShotVideo(storyId, shotId, modelId, userId);
    }

    public String generateShotVideo(Long storyId, Long shotId, Long modelId, Long userId) {
        PovStory story = getStoryEntity(storyId, userId);
        PovShot shot = requireShot(storyId, shotId);
        AiModel model = resolveVideoModel(modelId);
        return submitVideoTask(shot, prepareVideoTask(story, shot, model));
    }

    /** 已弃用：角色声音直接随视频任务按镜头映射，不再生成镜头级重复配音。 */
    public String generateShotAudio(Long storyId, Long shotId, Long ttsModelId, Long userId) {
        getStoryEntity(storyId, userId);
        requireShot(storyId, shotId);
        throw new BusinessException("POV 镜头不再单独生成配音，请在故事资产区为每个角色生成参考音频");
    }

    public int generateAllShotsMedia(Long storyId, String mediaType, Long modelId, Long ttsModelId, Long userId) {
        if (StrUtil.isNotBlank(mediaType) && !"VIDEO".equalsIgnoreCase(mediaType)) throw new BusinessException("POV 批量生成只支持直接生成视频");
        PovStory story = getStoryEntity(storyId, userId);
        List<PovShot> shots = listShots(storyId);
        if (shots.isEmpty()) throw new BusinessException("故事没有分镜");
        AiModel model = resolveVideoModel(modelId);
        List<PreparedShotTask> prepared = new ArrayList<>();
        for (PovShot shot : shots) prepared.add(new PreparedShotTask(shot, prepareVideoTask(story, shot, model)));
        story.setStatus("GENERATING"); story.setErrorMessage(null); storyMapper.updateById(story);
        for (PreparedShotTask item : prepared) submitVideoTask(item.shot(), item.task());
        return prepared.size();
    }

    @Scheduled(fixedDelay = 3000)
    public void pollGeneratingShots() {
        List<PovShot> generating = shotMapper.selectList(new LambdaQueryWrapper<PovShot>()
                .eq(PovShot::getVideoStatus, "GENERATING").isNotNull(PovShot::getVideoTaskId));
        for (PovShot shot : generating) {
            try {
                VideoTask task = videoGenerationService.getByTaskId(shot.getVideoTaskId());
                if (task == null || task.getStatus() == null || task.getStatus() < 2) continue;
                if (task.getStatus() == 2) {
                    List<VideoItem> items = videoGenerationService.listItems(task.getId());
                    String url = items.stream().map(VideoItem::getVideoUrl).filter(StrUtil::isNotBlank).findFirst().orElse(null);
                    if (url != null) {
                        shot.setVideoUrl(url); shot.setVideoStatus("COMPLETED"); shot.setStatus("COMPLETED"); shot.setErrorMessage(null);
                    } else failVideo(shot, "视频任务完成但未返回视频 URL");
                } else failVideo(shot, StrUtil.blankToDefault(task.getErrorMsg(), "视频生成失败"));
                shot.setVideoTaskId(null); shotMapper.updateById(shot); refreshStoryStatus(shot.getStoryId());
            } catch (Exception e) {
                log.warn("[POV] 轮询镜头视频失败: shotId={}, error={}", shot.getId(), e.getMessage());
            }
        }
        pollLegacyShotAudios();
    }

    public String composeStoryVideo(Long storyId, Long userId) {
        PovStory story = getStoryEntity(storyId, userId);
        List<PovShot> shots = listShots(storyId);
        List<Integer> missing = shots.stream().filter(s -> StrUtil.isBlank(s.getVideoUrl())).map(PovShot::getShotOrder).toList();
        if (!missing.isEmpty()) throw new BusinessException("以下镜头尚未生成视频，无法合成: " + missing);
        story.setStatus("GENERATING"); storyMapper.updateById(story);
        try {
            String finalUrl = povVideoComposeService.composeStoryVideo(story, shots);
            story.setFinalVideoUrl(finalUrl); story.setStatus("COMPLETED"); story.setErrorMessage(null); storyMapper.updateById(story);
            return finalUrl;
        } catch (Exception e) {
            story.setStatus("FAILED"); story.setErrorMessage(e.getMessage()); storyMapper.updateById(story); throw e;
        }
    }

    private AssetIndex saveAssets(PovStory story, JSONArray assetsJson) {
        Map<String, PovAsset> assetsByKey = new LinkedHashMap<>();
        Map<String, PovAssetItem> itemsByKey = new LinkedHashMap<>();
        Map<Long, String> pendingParents = new LinkedHashMap<>();
        if (assetsJson == null) return new AssetIndex(assetsByKey, itemsByKey);
        for (int i = 0; i < assetsJson.size(); i++) {
            JSONObject obj = assetsJson.getJSONObject(i);
            String assetKey = StrUtil.blankToDefault(obj.getStr("assetKey"), "asset_" + (i + 1));
            Integer characterNo = obj.getInt("characterNo");
            boolean voiceRequired = obj.getBool("voiceRequired", characterNo != null);
            String defaultVoice = characterNo != null
                    ? JSONUtil.createObj().set("voiceId", story.getVoiceId()).set("voiceName", story.getVoiceId()).toString() : null;
            PovAsset asset = PovAsset.builder().storyId(story.getId()).assetKey(assetKey)
                    .assetRole(obj.getStr("assetRole", "KEY_PROP")).name(obj.getStr("name", assetKey))
                    .description(obj.getStr("description", "")).characterNo(characterNo).voiceRequired(voiceRequired)
                    .voiceDescription(obj.getStr("voiceDescription")).voiceSampleText(obj.getStr("voiceSampleText"))
                    .voiceConfig(defaultVoice).voiceStatus(voiceRequired ? "PENDING" : "NOT_REQUIRED")
                    .sortOrder(obj.getInt("sortOrder", i)).build();
            assetMapper.insert(asset); assetsByKey.put(assetKey, asset);
            JSONArray itemsJson = obj.getJSONArray("items");
            if (itemsJson == null || itemsJson.isEmpty()) {
                itemsJson = JSONUtil.createArray();
                itemsJson.add(JSONUtil.createObj()
                        .set("itemKey", "initial").set("itemType", "initial").set("stateDescription", asset.getDescription())
                        .set("imagePrompt", StrUtil.blankToDefault(obj.getStr("imagePrompt"), asset.getDescription())));
            }
            for (int j = 0; j < itemsJson.size(); j++) {
                JSONObject itemObj = itemsJson.getJSONObject(j);
                String itemKey = StrUtil.blankToDefault(itemObj.getStr("itemKey"), j == 0 ? "initial" : "variant_" + j);
                PovAssetItem item = PovAssetItem.builder().assetId(asset.getId()).itemKey(itemKey)
                        .itemType(itemObj.getStr("itemType", j == 0 ? "initial" : "variant"))
                        .stateDescription(itemObj.getStr("stateDescription", ""))
                        .imagePrompt(StrUtil.blankToDefault(itemObj.getStr("imagePrompt"), asset.getDescription()))
                        .negativePrompt(StrUtil.blankToDefault(itemObj.getStr("negativePrompt"), DEFAULT_NEGATIVE))
                        .status("PENDING").sortOrder(itemObj.getInt("sortOrder", j)).build();
                assetItemMapper.insert(item); itemsByKey.put(assetKey + ":" + itemKey, item);
                String parentKey = itemObj.getStr("basedOnItemKey");
                if (StrUtil.isNotBlank(parentKey)) pendingParents.put(item.getId(), assetKey + ":" + parentKey);
            }
        }
        for (Map.Entry<Long, String> entry : pendingParents.entrySet()) {
            PovAssetItem parent = itemsByKey.get(entry.getValue());
            if (parent == null) throw new BusinessException("资产变体依赖不存在: " + entry.getValue());
            PovAssetItem item = assetItemMapper.selectById(entry.getKey()); item.setBasedOnItemId(parent.getId()); assetItemMapper.updateById(item);
        }
        return new AssetIndex(assetsByKey, itemsByKey);
    }

    private void saveShots(PovStory story, JSONArray shotsJson, AssetIndex index) {
        if (shotsJson == null) return;
        for (int i = 0; i < shotsJson.size(); i++) {
            JSONObject obj = shotsJson.getJSONObject(i);
            String videoDescription = firstNonBlank(obj.getStr("videoDescription"), obj.getStr("visualDescription"), "");
            String cameraView = obj.getStr("cameraView", "POV_HANDHELD");
            PovShot shot = PovShot.builder().storyId(story.getId()).shotOrder(obj.getInt("shotOrder", i + 1))
                    .narrativeStage(obj.getStr("narrativeStage", "EXPLORE")).voiceText(obj.getStr("voiceText", ""))
                    .visualPrompt(videoDescription).videoPrompt(buildPovVideoPrompt(videoDescription, cameraView))
                    .negativePrompt(StrUtil.blankToDefault(obj.getStr("negativePrompt"), DEFAULT_NEGATIVE))
                    .cameraView(cameraView).cameraMovement(obj.getStr("cameraMovement", "SUBTLE_HANDHELD_SHAKE"))
                    .sfxCue(obj.getStr("sfxCue", "")).durationSec(obj.getBigDecimal("durationSec", BigDecimal.valueOf(5)))
                    .videoStatus("PENDING").audioStatus("NOT_REQUIRED").status("PENDING").build();
            shotMapper.insert(shot);
            JSONArray refs = obj.getJSONArray("assetRefs");
            if (refs != null) for (int j = 0; j < refs.size(); j++) {
                JSONObject ref = refs.getJSONObject(j);
                String assetKey = ref.getStr("assetKey"); String itemKey = ref.getStr("itemKey", "initial");
                PovAssetItem assetItem = index.itemsByKey().get(assetKey + ":" + itemKey);
                if (assetItem == null) throw new BusinessException("镜头引用的资产不存在: " + assetKey + ":" + itemKey);
                shotAssetMapper.insert(PovShotAsset.builder().storyId(story.getId()).shotId(shot.getId())
                        .assetItemId(assetItem.getId()).referenceOrder(ref.getInt("referenceOrder", j + 1))
                        .required(ref.getBool("required", true)).build());
            }
            JSONArray dialogues = obj.getJSONArray("dialogues");
            if (dialogues != null) for (int j = 0; j < dialogues.size(); j++) {
                JSONObject dialogue = dialogues.getJSONObject(j); Integer characterNo = dialogue.getInt("characterNo");
                PovAsset character = index.assetsByKey().values().stream().filter(a -> Objects.equals(a.getCharacterNo(), characterNo))
                        .findFirst().orElseThrow(() -> new BusinessException("镜头台词引用的角色编号不存在: " + characterNo));
                shotSpeakerMapper.insert(PovShotSpeaker.builder().storyId(story.getId()).shotId(shot.getId())
                        .assetId(character.getId()).characterNo(characterNo)
                        .dialogueText(firstNonBlank(dialogue.getStr("dialogueText"), dialogue.getStr("text"), ""))
                        .delivery(dialogue.getStr("delivery")).sortOrder(dialogue.getInt("sortOrder", j + 1)).build());
            }
        }
    }

    private VideoTask prepareVideoTask(PovStory story, PovShot shot, AiModel model) {
        List<PovShotAsset> bindings = shotAssetMapper.selectList(new LambdaQueryWrapper<PovShotAsset>()
                .eq(PovShotAsset::getShotId, shot.getId()).orderByAsc(PovShotAsset::getReferenceOrder));
        if (bindings.isEmpty()) throw new BusinessException("镜头 " + shot.getShotOrder() + " 未绑定任何 POV 故事资产");
        List<ReferenceInput> refs = new ArrayList<>();
        for (PovShotAsset binding : bindings) {
            PovAssetItem item = assetItemMapper.selectById(binding.getAssetItemId());
            PovAsset asset = item != null ? assetMapper.selectById(item.getAssetId()) : null;
            if (asset == null || StrUtil.isBlank(item.getImageUrl()) || !"READY".equals(item.getStatus())) {
                if (Boolean.TRUE.equals(binding.getRequired())) throw new BusinessException("镜头 " + shot.getShotOrder()
                        + " 的必要资产尚未生成: " + (asset != null ? asset.getName() : binding.getAssetItemId()));
                continue;
            }
            refs.add(new ReferenceInput(asset, item, item.getImageUrl()));
        }
        if (refs.isEmpty()) throw new BusinessException("镜头 " + shot.getShotOrder() + " 没有可用资产参考图");
        List<PovShotSpeaker> speakers = shotSpeakerMapper.selectList(new LambdaQueryWrapper<PovShotSpeaker>()
                .eq(PovShotSpeaker::getShotId, shot.getId()).orderByAsc(PovShotSpeaker::getSortOrder));
        Map<Long, PovAsset> speakerAssets = new LinkedHashMap<>();
        for (PovShotSpeaker speaker : speakers) {
            PovAsset character = assetMapper.selectById(speaker.getAssetId());
            if (character == null || StrUtil.isBlank(character.getReferenceAudioUrl()) || !"READY".equals(character.getVoiceStatus()))
                throw new BusinessException("镜头 " + shot.getShotOrder() + " 的发声角色尚未生成参考音频: 角色" + speaker.getCharacterNo());
            if (refs.stream().noneMatch(ref -> ref.asset().getId().equals(character.getId())))
                throw new BusinessException("镜头 " + shot.getShotOrder() + " 的发声角色未绑定角色资产图: 角色" + speaker.getCharacterNo());
            speakerAssets.putIfAbsent(character.getId(), character);
        }
        String prompt = compileVideoPrompt(shot, refs, speakers, speakerAssets);
        String platform = modelMetadataResolver.normalizePlatform(modelMetadataResolver.resolvePlatform(model));
        GenerationModelCapabilityService.VideoModelCapability capability = capabilityService.resolveVideoCapability(model, platform);
        if (!capability.supportsReferenceImages()) {
            throw new BusinessException("当前视频模型不支持参考图，不能用于 POV 故事资产一致性链路");
        }
        if (!speakerAssets.isEmpty() && !capability.supportsReferenceAudios()) {
            throw new BusinessException("当前视频模型不支持参考音频，不能锁定 POV 角色声音");
        }
        if (capability.maxReferenceImages() != null && refs.size() > capability.maxReferenceImages()) {
            throw new BusinessException("镜头 " + shot.getShotOrder() + " 需要 " + refs.size()
                    + " 张资产参考图，但当前模型最多支持 " + capability.maxReferenceImages() + " 张");
        }
        if (capability.maxReferenceAudios() != null && speakerAssets.size() > capability.maxReferenceAudios()) {
            throw new BusinessException("镜头 " + shot.getShotOrder() + " 需要 " + speakerAssets.size()
                    + " 个角色参考音频，但当前模型最多支持 " + capability.maxReferenceAudios() + " 个");
        }
        VideoTask task = VideoTask.builder().userId(story.getUserId()).projectId(story.getProjectId())
                .modelId(model.getId()).modelCode(model.getCode()).prompt(prompt).generateMode("text2video")
                .firstFrameImageUrl(null).lastFrameImageUrl(null)
                .referenceImageUrls(JSONUtil.toJsonStr(refs.stream().map(ReferenceInput::url).toList()))
                .referenceAudioUrls(buildReferenceAudioJson(platform, refs, speakerAssets.values()))
                .generateAudio(!speakers.isEmpty()).ratio("9:16")
                .duration(Math.max(1, shot.getDurationSec() != null ? shot.getDurationSec().intValue() : 5))
                .cameraFixed(false).category("POV_DIRECT_VIDEO").status(0).build();
        capabilityService.validateVideoTask(model, task, platform);
        return task;
    }

    private String submitVideoTask(PovShot shot, VideoTask task) {
        String taskId = videoGenerationConsumer.submitTask(task);
        shot.setVideoTaskId(taskId); shot.setVideoStatus("GENERATING"); shot.setAudioStatus("NOT_REQUIRED");
        shot.setStatus("GENERATING"); shot.setErrorMessage(null); shotMapper.updateById(shot); return taskId;
    }

    private String compileVideoPrompt(PovShot shot, List<ReferenceInput> refs, List<PovShotSpeaker> speakers,
                                      Map<Long, PovAsset> speakerAssets) {
        StringBuilder prompt = new StringBuilder(StrUtil.blankToDefault(shot.getVideoPrompt(), shot.getVisualPrompt()));
        prompt.append("\n【POV 故事资产一致性】本镜头直接生成视频，不把任何参考图当作首帧构图。")
                .append("只参考资产的身份、外观、材质和空间设定；保持第一人称主观视角，禁止出现叙述者正脸。\n");
        for (int i = 0; i < refs.size(); i++) {
            ReferenceInput ref = refs.get(i);
            prompt.append("参考图片").append(i + 1).append("是").append(ref.asset().getName()).append("（状态：")
                    .append(StrUtil.blankToDefault(ref.item().getStateDescription(), ref.item().getItemKey())).append("），镜头中必须保持一致；\n");
        }
        int audioNo = 1;
        for (PovAsset character : speakerAssets.values()) {
            prompt.append("角色").append(character.getCharacterNo()).append("是参考图片")
                    .append(findCharacterImageNo(refs, character.getId())).append("中的").append(character.getName())
                    .append("，角色").append(character.getCharacterNo()).append("的声音严格参考音频").append(audioNo++).append("；\n");
        }
        for (PovShotSpeaker speaker : speakers) {
            prompt.append("本镜头由角色").append(speaker.getCharacterNo()).append("说：\"").append(speaker.getDialogueText()).append("\"");
            if (StrUtil.isNotBlank(speaker.getDelivery())) prompt.append("，语气：").append(speaker.getDelivery());
            prompt.append("。\n");
        }
        if (!speakers.isEmpty()) prompt.append("未列为发声者的角色保持沉默，禁止交换角色声音或台词。\n");
        prompt.append("运镜：").append(StrUtil.blankToDefault(shot.getCameraMovement(), "SUBTLE_HANDHELD_SHAKE"))
                .append("。负向约束：").append(StrUtil.blankToDefault(shot.getNegativePrompt(), DEFAULT_NEGATIVE));
        return prompt.toString();
    }

    private String buildReferenceAudioJson(String platform, List<ReferenceInput> refs, Collection<PovAsset> speakers) {
        if (speakers.isEmpty()) return null;
        JSONArray array = JSONUtil.createArray();
        for (PovAsset speaker : speakers) {
            if ("dashscope".equals(platform)) {
                String imageUrl = refs.get(findCharacterImageNo(refs, speaker.getId()) - 1).url();
                array.add(JSONUtil.createObj().set("imageUrl", imageUrl).set("audioUrl", speaker.getReferenceAudioUrl()));
            } else array.add(speaker.getReferenceAudioUrl());
        }
        return array.toString();
    }

    private int findCharacterImageNo(List<ReferenceInput> refs, Long assetId) {
        for (int i = 0; i < refs.size(); i++) if (refs.get(i).asset().getId().equals(assetId)) return i + 1;
        throw new BusinessException("发声角色缺少对应参考图");
    }

    private List<PovShotRespVO> buildShotResponses(Long storyId, List<PovShot> shots) {
        if (shots.isEmpty()) return List.of();
        List<PovShotRespVO> result = PovConvert.INSTANCE.convertShotList(shots);
        Map<Long, PovShotRespVO> voById = new HashMap<>();
        result.forEach(vo -> { vo.setAssetRefs(new ArrayList<>()); vo.setSpeakers(new ArrayList<>()); voById.put(vo.getId(), vo); });
        List<PovShotAsset> bindings = shotAssetMapper.selectList(new LambdaQueryWrapper<PovShotAsset>()
                .eq(PovShotAsset::getStoryId, storyId).orderByAsc(PovShotAsset::getReferenceOrder));
        List<PovShotSpeaker> speakers = shotSpeakerMapper.selectList(new LambdaQueryWrapper<PovShotSpeaker>()
                .eq(PovShotSpeaker::getStoryId, storyId).orderByAsc(PovShotSpeaker::getSortOrder));
        Set<Long> itemIds = new HashSet<>();
        bindings.forEach(binding -> itemIds.add(binding.getAssetItemId()));
        Map<Long, PovAssetItem> itemById = new HashMap<>();
        if (!itemIds.isEmpty()) {
            assetItemMapper.selectBatchIds(itemIds).forEach(item -> itemById.put(item.getId(), item));
        }
        Set<Long> assetIds = new HashSet<>();
        itemById.values().forEach(item -> assetIds.add(item.getAssetId()));
        speakers.forEach(speaker -> assetIds.add(speaker.getAssetId()));
        Map<Long, PovAsset> assetById = new HashMap<>();
        if (!assetIds.isEmpty()) {
            assetMapper.selectBatchIds(assetIds).forEach(asset -> assetById.put(asset.getId(), asset));
        }
        for (PovShotAsset binding : bindings) {
            PovAssetItem item = itemById.get(binding.getAssetItemId());
            PovAsset asset = item != null ? assetById.get(item.getAssetId()) : null;
            PovShotRespVO shotVO = voById.get(binding.getShotId());
            if (asset == null || shotVO == null) continue;
            PovShotAssetRefRespVO refVO = new PovShotAssetRefRespVO();
            refVO.setAssetId(asset.getId()); refVO.setAssetItemId(item.getId()); refVO.setAssetName(asset.getName());
            refVO.setAssetRole(asset.getAssetRole()); refVO.setItemKey(item.getItemKey()); refVO.setImageUrl(item.getImageUrl());
            refVO.setReferenceOrder(binding.getReferenceOrder()); refVO.setRequired(binding.getRequired()); shotVO.getAssetRefs().add(refVO);
        }
        for (PovShotSpeaker speaker : speakers) {
            PovAsset asset = assetById.get(speaker.getAssetId()); PovShotRespVO shotVO = voById.get(speaker.getShotId());
            if (asset == null || shotVO == null) continue;
            PovShotSpeakerRespVO speakerVO = new PovShotSpeakerRespVO();
            speakerVO.setAssetId(asset.getId()); speakerVO.setCharacterNo(speaker.getCharacterNo()); speakerVO.setCharacterName(asset.getName());
            speakerVO.setDialogueText(speaker.getDialogueText()); speakerVO.setDelivery(speaker.getDelivery());
            speakerVO.setReferenceAudioUrl(asset.getReferenceAudioUrl()); speakerVO.setSortOrder(speaker.getSortOrder()); shotVO.getSpeakers().add(speakerVO);
        }
        return result;
    }

    private void pollLegacyShotAudios() {
        List<PovShot> shots = shotMapper.selectList(new LambdaQueryWrapper<PovShot>()
                .eq(PovShot::getAudioStatus, "GENERATING").isNotNull(PovShot::getAudioTaskId));
        for (PovShot shot : shots) {
            AudioTask task = audioGenerationService.getByTaskId(shot.getAudioTaskId());
            if (task == null || task.getStatus() == null || task.getStatus() < 2) continue;
            if (task.getStatus() == 2) { shot.setAudioUrl(task.getAudioUrl()); shot.setAudioStatus("COMPLETED"); }
            else { shot.setAudioStatus("FAILED"); shot.setErrorMessage(task.getErrorMessage()); }
            shot.setAudioTaskId(null); shotMapper.updateById(shot);
        }
    }

    private void refreshStoryStatus(Long storyId) {
        List<PovShot> shots = listShots(storyId); PovStory story = storyMapper.selectById(storyId);
        if (story == null || shots.isEmpty()) return;
        if (shots.stream().anyMatch(s -> "FAILED".equals(s.getVideoStatus()))) story.setStatus("FAILED");
        else if (shots.stream().allMatch(s -> "COMPLETED".equals(s.getVideoStatus()))) story.setStatus("COMPLETED");
        else story.setStatus("GENERATING");
        storyMapper.updateById(story);
    }

    private void failVideo(PovShot shot, String message) { shot.setVideoStatus("FAILED"); shot.setStatus("FAILED"); shot.setErrorMessage(message); }

    private AiModel resolveVideoModel(Long modelId) {
        AiModel model = modelId != null ? aiModelService.getById(modelId) : aiModelService.getDefaultByType(MODEL_TYPE_VIDEO);
        if (model == null || !Objects.equals(model.getModelType(), MODEL_TYPE_VIDEO)) throw new BusinessException("未配置可用的视频生成模型");
        return model;
    }

    private PovStory getStoryEntity(Long storyId, Long userId) {
        PovStory story = storyMapper.selectById(storyId);
        if (story == null) throw new BusinessException("故事不存在: " + storyId);
        return story;
    }

    private String normalizeVisibility(String visibility, Long projectId) {
        String value = StrUtil.blankToDefault(visibility, "PRIVATE").toUpperCase();
        if (!List.of("PRIVATE", "PROJECT", "TEAM").contains(value)) {
            throw new BusinessException("visibility 仅支持 PRIVATE、PROJECT、TEAM");
        }
        if (!"PRIVATE".equals(value) && projectId == null) {
            throw new BusinessException(value + " 可见性必须绑定项目");
        }
        return value;
    }

    private PovShot requireShot(Long storyId, Long shotId) {
        PovShot shot = shotMapper.selectById(shotId);
        if (shot == null || !storyId.equals(shot.getStoryId())) throw new BusinessException("分镜不存在: " + shotId);
        return shot;
    }

    private List<PovShot> listShots(Long storyId) {
        return shotMapper.selectList(new LambdaQueryWrapper<PovShot>().eq(PovShot::getStoryId, storyId).orderByAsc(PovShot::getShotOrder));
    }

    private String buildPovVideoPrompt(String description, String cameraView) {
        String camera = switch (StrUtil.blankToDefault(cameraView, "POV_HANDHELD")) {
            case "POV_BODYCAM" -> "第一人称执法仪广角纪实镜头";
            case "POV_NIGHT_VISION" -> "第一人称红外夜视镜头";
            case "POV_MACRO" -> "第一人称手部交互微距镜头";
            case "POV_CCTV", "POV_GLITCH" -> "第一人称老式录像机取景镜头";
            default -> "第一人称主观手持镜头";
        };
        return camera + "。" + description + "。写实电影质感，动作在一个连续镜头内自然完成，空间与故事资产参考图保持一致。";
    }

    private JSONObject extractJson(String text) {
        if (StrUtil.isBlank(text)) return null;
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) return JSONUtil.parseObj(trimmed);
            Matcher matcher = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE).matcher(text);
            if (matcher.find()) return JSONUtil.parseObj(matcher.group(1).trim());
            int start = text.indexOf('{'); int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) return JSONUtil.parseObj(text.substring(start, end + 1));
        } catch (Exception e) { log.warn("[POV] JSON 提取失败", e); }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StrUtil.isNotBlank(value)) return value;
        return "";
    }

    private record AssetIndex(Map<String, PovAsset> assetsByKey, Map<String, PovAssetItem> itemsByKey) {}
    private record ReferenceInput(PovAsset asset, PovAssetItem item, String url) {}
    private record PreparedShotTask(PovShot shot, VideoTask task) {}
}
