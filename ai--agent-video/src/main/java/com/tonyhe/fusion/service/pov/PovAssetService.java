package com.tonyhe.fusion.service.pov;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.controller.pov.vo.PovAssetItemRespVO;
import com.tonyhe.fusion.controller.pov.vo.PovAssetRespVO;
import com.tonyhe.fusion.controller.pov.vo.PovAssetVoiceUpdateReqVO;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.entity.pov.PovAsset;
import com.tonyhe.fusion.entity.pov.PovAssetItem;
import com.tonyhe.fusion.entity.pov.PovStory;
import com.tonyhe.fusion.entity.pov.PovShot;
import com.tonyhe.fusion.entity.pov.PovShotAsset;
import com.tonyhe.fusion.entity.pov.PovShotSpeaker;
import com.tonyhe.fusion.mapper.pov.PovAssetItemMapper;
import com.tonyhe.fusion.mapper.pov.PovAssetMapper;
import com.tonyhe.fusion.mapper.pov.PovStoryMapper;
import com.tonyhe.fusion.mapper.pov.PovShotMapper;
import com.tonyhe.fusion.mapper.pov.PovShotAssetMapper;
import com.tonyhe.fusion.mapper.pov.PovShotSpeakerMapper;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.consumer.AudioGenerationConsumer;
import com.tonyhe.fusion.service.generation.consumer.ImageGenerationConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** POV 故事专属资产服务，不读写普通剧本资产表。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PovAssetService {

    private static final int MODEL_TYPE_IMAGE = 2;
    private static final int MODEL_TYPE_TTS = 4;
    private static final String DIALOGUE_TYPE_POV_REFERENCE = "POV_REFERENCE";

    private final PovStoryMapper storyMapper;
    private final PovAssetMapper assetMapper;
    private final PovAssetItemMapper itemMapper;
    private final PovShotMapper shotMapper;
    private final PovShotAssetMapper shotAssetMapper;
    private final PovShotSpeakerMapper shotSpeakerMapper;
    private final AiModelService aiModelService;
    private final GenerationModelCapabilityService capabilityService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final ImageGenerationService imageGenerationService;
    private final AudioGenerationConsumer audioGenerationConsumer;
    private final AudioGenerationService audioGenerationService;

    public List<PovAssetRespVO> listAssets(Long storyId, Long userId) {
        requireStory(storyId, userId);
        return listAssets(storyId);
    }

    public List<PovAssetRespVO> listAssets(Long storyId) {
        List<PovAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<PovAsset>()
                .eq(PovAsset::getStoryId, storyId)
                .orderByAsc(PovAsset::getSortOrder).orderByAsc(PovAsset::getId));
        if (assets.isEmpty()) {
            return List.of();
        }
        List<Long> assetIds = assets.stream().map(PovAsset::getId).toList();
        List<PovAssetItem> items = itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                .in(PovAssetItem::getAssetId, assetIds)
                .orderByAsc(PovAssetItem::getSortOrder).orderByAsc(PovAssetItem::getId));
        Map<Long, List<PovAssetItemRespVO>> itemsByAsset = new LinkedHashMap<>();
        for (PovAssetItem item : items) {
            itemsByAsset.computeIfAbsent(item.getAssetId(), ignored -> new ArrayList<>()).add(toItemVO(item));
        }
        return assets.stream().map(asset -> toAssetVO(asset, itemsByAsset.getOrDefault(asset.getId(), List.of()))).toList();
    }

    public String generateAssetItem(Long storyId, Long itemId, Long modelId, Long userId) {
        PovStory story = requireStory(storyId, userId);
        PovAssetItem item = requireItem(storyId, itemId);
        AiModel model = resolveModel(modelId, MODEL_TYPE_IMAGE, "图片");
        return submitImage(story, item, model, true);
    }

    public int generateAllAssets(Long storyId, Long modelId, Long userId) {
        PovStory story = requireStory(storyId, userId);
        AiModel model = resolveModel(modelId, MODEL_TYPE_IMAGE, "图片");
        List<PovAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<PovAsset>()
                .eq(PovAsset::getStoryId, storyId));
        if (assets.isEmpty()) {
            throw new BusinessException("该故事没有资产，请重新生成故事结构");
        }
        List<PovAssetItem> items = itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                .in(PovAssetItem::getAssetId, assets.stream().map(PovAsset::getId).toList())
                .orderByAsc(PovAssetItem::getSortOrder));
        for (PovAssetItem item : items) {
            item.setImageModelId(model.getId());
            if (item.getBasedOnItemId() != null) {
                item.setImageTaskId(null);
                item.setImageUrl(null);
                item.setStatus("PENDING");
                item.setErrorMessage(null);
            }
            itemMapper.updateById(item);
        }
        int submitted = 0;
        for (PovAssetItem item : items) {
            if (item.getBasedOnItemId() == null && !"GENERATING".equals(item.getStatus())) {
                submitImage(story, item, model, false);
                submitted++;
            }
        }
        return submitted;
    }

    public PovAssetRespVO updateVoice(Long storyId, Long assetId, PovAssetVoiceUpdateReqVO req, Long userId) {
        requireStory(storyId, userId);
        PovAsset asset = requireAsset(storyId, assetId);
        if (!isCharacter(asset)) {
            throw new BusinessException("只有角色资产可以配置参考音色");
        }
        JSONObject config = JSONUtil.createObj()
                .set("voiceId", req.getVoiceId())
                .set("voiceName", req.getVoiceName())
                .set("speed", req.getSpeed())
                .set("pitch", req.getPitch())
                .set("emotion", req.getEmotion())
                .set("customVoiceId", req.getCustomVoiceId());
        asset.setVoiceConfig(config.toString());
        if (StrUtil.isNotBlank(req.getSampleText())) {
            asset.setVoiceSampleText(req.getSampleText().trim());
        }
        asset.setReferenceAudioTaskId(null);
        asset.setVoiceErrorMessage(null);
        if (StrUtil.isNotBlank(req.getReferenceAudioUrl())) {
            asset.setReferenceAudioUrl(req.getReferenceAudioUrl().trim());
            asset.setVoiceStatus("READY");
        } else {
            asset.setReferenceAudioUrl(null);
            asset.setVoiceStatus(Boolean.TRUE.equals(asset.getVoiceRequired()) ? "PENDING" : "NOT_REQUIRED");
        }
        assetMapper.updateById(asset);
        markSpeakerShotsStale(asset.getId());
        return toAssetVO(asset, loadItemVOs(assetId));
    }

    public String generateReferenceAudio(Long storyId, Long assetId, Long ttsModelId, Long userId) {
        PovStory story = requireStory(storyId, userId);
        PovAsset asset = requireAsset(storyId, assetId);
        if (!isCharacter(asset)) {
            throw new BusinessException("只有角色资产可以生成参考音频");
        }
        if (StrUtil.isBlank(asset.getVoiceConfig())) {
            throw new BusinessException("请先为角色配置音色");
        }
        String sampleText = StrUtil.blankToDefault(asset.getVoiceSampleText(),
                "这是角色" + asset.getCharacterNo() + "的声音样本，请保持音色、语速和情绪稳定。");
        AiModel model = resolveModel(ttsModelId, MODEL_TYPE_TTS, "TTS");
        AudioTask task = AudioTask.builder()
                .userId(userId)
                .projectId(story.getProjectId())
                .text(sampleText)
                .characterName(asset.getName())
                .voiceConfig(asset.getVoiceConfig())
                .modelId(model.getId())
                .modelCode(model.getCode())
                .dialogueType(DIALOGUE_TYPE_POV_REFERENCE)
                .status(0)
                .build();
        String taskId = audioGenerationConsumer.submitTask(task);
        asset.setReferenceAudioTaskId(taskId);
        asset.setReferenceAudioUrl(null);
        asset.setVoiceStatus("GENERATING");
        asset.setVoiceErrorMessage(null);
        assetMapper.updateById(asset);
        markSpeakerShotsStale(asset.getId());
        return taskId;
    }

    @Scheduled(fixedDelay = 3000)
    public void pollGeneratingAssets() {
        pollImages();
        pollReferenceAudios();
    }

    private void pollImages() {
        List<PovAssetItem> generating = itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                .eq(PovAssetItem::getStatus, "GENERATING")
                .isNotNull(PovAssetItem::getImageTaskId));
        for (PovAssetItem item : generating) {
            try {
                ImageTask task = imageGenerationService.getByTaskId(item.getImageTaskId());
                if (task == null || task.getStatus() == null || task.getStatus() < 2) {
                    continue;
                }
                if (task.getStatus() == 2) {
                    List<ImageItem> results = imageGenerationService.listItems(task.getId());
                    String url = results.stream().map(ImageItem::getImageUrl).filter(StrUtil::isNotBlank).findFirst().orElse(null);
                    if (url != null) {
                        item.setImageUrl(url);
                        item.setStatus("READY");
                        item.setErrorMessage(null);
                    } else {
                        item.setStatus("FAILED");
                        item.setErrorMessage("生图完成但未返回图片 URL");
                    }
                } else {
                    item.setStatus("FAILED");
                    item.setErrorMessage(task.getErrorMsg());
                }
                item.setImageTaskId(null);
                itemMapper.updateById(item);
                if ("READY".equals(item.getStatus())) {
                    submitReadyVariants(item);
                }
            } catch (Exception e) {
                log.warn("[POV Asset] 轮询图片失败: itemId={}, error={}", item.getId(), e.getMessage());
            }
        }
    }

    private void submitReadyVariants(PovAssetItem parent) {
        List<PovAssetItem> variants = itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                .eq(PovAssetItem::getBasedOnItemId, parent.getId())
                .eq(PovAssetItem::getStatus, "PENDING")
                .isNotNull(PovAssetItem::getImageModelId));
        if (variants.isEmpty()) {
            return;
        }
        PovAsset asset = assetMapper.selectById(parent.getAssetId());
        PovStory story = asset != null ? storyMapper.selectById(asset.getStoryId()) : null;
        if (story == null) {
            return;
        }
        for (PovAssetItem variant : variants) {
            try {
                AiModel model = resolveModel(variant.getImageModelId(), MODEL_TYPE_IMAGE, "图片");
                submitImage(story, variant, model, false);
            } catch (Exception e) {
                variant.setStatus("FAILED");
                variant.setErrorMessage(e.getMessage());
                itemMapper.updateById(variant);
            }
        }
    }

    private void pollReferenceAudios() {
        List<PovAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<PovAsset>()
                .eq(PovAsset::getVoiceStatus, "GENERATING")
                .isNotNull(PovAsset::getReferenceAudioTaskId));
        for (PovAsset asset : assets) {
            try {
                AudioTask task = audioGenerationService.getByTaskId(asset.getReferenceAudioTaskId());
                if (task == null || task.getStatus() == null || task.getStatus() < 2) {
                    continue;
                }
                if (task.getStatus() == 2 && StrUtil.isNotBlank(task.getAudioUrl())) {
                    asset.setReferenceAudioUrl(task.getAudioUrl());
                    asset.setVoiceStatus("READY");
                    asset.setVoiceErrorMessage(null);
                } else {
                    asset.setVoiceStatus("FAILED");
                    asset.setVoiceErrorMessage(StrUtil.blankToDefault(task.getErrorMessage(), "参考音频生成失败"));
                }
                asset.setReferenceAudioTaskId(null);
                assetMapper.updateById(asset);
            } catch (Exception e) {
                log.warn("[POV Asset] 轮询参考音频失败: assetId={}, error={}", asset.getId(), e.getMessage());
            }
        }
    }

    private String submitImage(PovStory story, PovAssetItem item, AiModel model, boolean failIfParentPending) {
        String refImageUrls = null;
        String prompt = item.getImagePrompt();
        if (item.getBasedOnItemId() == null) {
            invalidateDescendants(item.getId());
        }
        if (item.getBasedOnItemId() != null) {
            PovAssetItem parent = itemMapper.selectById(item.getBasedOnItemId());
            if (parent == null || !"READY".equals(parent.getStatus()) || StrUtil.isBlank(parent.getImageUrl())) {
                if (failIfParentPending) {
                    throw new BusinessException("请先生成该资产的初始图，再生成状态变体");
                }
                item.setImageModelId(model.getId());
                item.setStatus("PENDING");
                itemMapper.updateById(item);
                return null;
            }
            refImageUrls = JSONUtil.toJsonStr(List.of(parent.getImageUrl()));
            prompt = prompt + "\n保持参考图中资产的身份、外观、材质、比例和结构完全一致，只改变已声明的状态。";
        }
        ImageTask task = ImageTask.builder()
                .userId(story.getUserId())
                .projectId(story.getProjectId())
                .modelId(model.getId())
                .modelCode(model.getCode())
                .prompt(prompt)
                .negativePrompt(item.getNegativePrompt())
                .refImageUrls(refImageUrls)
                .aspectRatio("9:16")
                .width(720)
                .height(1280)
                .category("POV_ASSET")
                .status(0)
                .build();
        capabilityService.validateImageTask(model, task);
        String taskId = imageGenerationConsumer.submitTask(task);
        item.setImageModelId(model.getId());
        item.setImageTaskId(taskId);
        item.setImageUrl(null);
        item.setStatus("GENERATING");
        item.setErrorMessage(null);
        itemMapper.updateById(item);
        markAssetItemShotsStale(item.getId());
        return taskId;
    }

    private void invalidateDescendants(Long parentId) {
        List<PovAssetItem> children = itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                .eq(PovAssetItem::getBasedOnItemId, parentId));
        for (PovAssetItem child : children) {
            child.setImageTaskId(null);
            child.setImageUrl(null);
            child.setStatus("PENDING");
            child.setErrorMessage(null);
            itemMapper.updateById(child);
            markAssetItemShotsStale(child.getId());
            invalidateDescendants(child.getId());
        }
    }

    private void markAssetItemShotsStale(Long itemId) {
        List<Long> shotIds = shotAssetMapper.selectList(new LambdaQueryWrapper<PovShotAsset>()
                        .eq(PovShotAsset::getAssetItemId, itemId))
                .stream().map(PovShotAsset::getShotId).distinct().toList();
        markShotsStale(shotIds);
    }

    private void markSpeakerShotsStale(Long assetId) {
        List<Long> shotIds = shotSpeakerMapper.selectList(new LambdaQueryWrapper<PovShotSpeaker>()
                        .eq(PovShotSpeaker::getAssetId, assetId))
                .stream().map(PovShotSpeaker::getShotId).distinct().toList();
        markShotsStale(shotIds);
    }

    private void markShotsStale(List<Long> shotIds) {
        if (shotIds.isEmpty()) {
            return;
        }
        List<PovShot> shots = shotMapper.selectBatchIds(shotIds);
        for (PovShot shot : shots) {
            if (StrUtil.isNotBlank(shot.getVideoUrl())) {
                shot.setVideoStatus("STALE");
                shotMapper.updateById(shot);
            }
        }
    }

    private PovStory requireStory(Long storyId, Long userId) {
        PovStory story = storyMapper.selectById(storyId);
        if (story == null) {
            throw new BusinessException("故事不存在: " + storyId);
        }
        if (!story.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此故事");
        }
        return story;
    }

    private PovAsset requireAsset(Long storyId, Long assetId) {
        PovAsset asset = assetMapper.selectById(assetId);
        if (asset == null || !storyId.equals(asset.getStoryId())) {
            throw new BusinessException("POV 资产不存在: " + assetId);
        }
        return asset;
    }

    private PovAssetItem requireItem(Long storyId, Long itemId) {
        PovAssetItem item = itemMapper.selectById(itemId);
        PovAsset asset = item != null ? assetMapper.selectById(item.getAssetId()) : null;
        if (asset == null || !storyId.equals(asset.getStoryId())) {
            throw new BusinessException("POV 资产子项不存在: " + itemId);
        }
        return item;
    }

    private AiModel resolveModel(Long modelId, int modelType, String label) {
        AiModel model = modelId != null ? aiModelService.getById(modelId) : aiModelService.getDefaultByType(modelType);
        if (model == null || model.getModelType() == null || model.getModelType() != modelType) {
            throw new BusinessException("未配置可用的" + label + "模型");
        }
        return model;
    }

    private boolean isCharacter(PovAsset asset) {
        return asset.getCharacterNo() != null
                || "POV_CHARACTER".equals(asset.getAssetRole())
                || "NPC_ENTITY".equals(asset.getAssetRole());
    }

    private List<PovAssetItemRespVO> loadItemVOs(Long assetId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PovAssetItem>()
                        .eq(PovAssetItem::getAssetId, assetId)
                        .orderByAsc(PovAssetItem::getSortOrder))
                .stream().map(this::toItemVO).toList();
    }

    private PovAssetRespVO toAssetVO(PovAsset asset, List<PovAssetItemRespVO> items) {
        PovAssetRespVO vo = new PovAssetRespVO();
        vo.setId(asset.getId());
        vo.setStoryId(asset.getStoryId());
        vo.setAssetKey(asset.getAssetKey());
        vo.setAssetRole(asset.getAssetRole());
        vo.setName(asset.getName());
        vo.setDescription(asset.getDescription());
        vo.setCharacterNo(asset.getCharacterNo());
        vo.setVoiceRequired(asset.getVoiceRequired());
        vo.setVoiceDescription(asset.getVoiceDescription());
        vo.setVoiceSampleText(asset.getVoiceSampleText());
        vo.setVoiceConfig(asset.getVoiceConfig());
        vo.setReferenceAudioUrl(asset.getReferenceAudioUrl());
        vo.setVoiceStatus(asset.getVoiceStatus());
        vo.setVoiceErrorMessage(asset.getVoiceErrorMessage());
        vo.setSortOrder(asset.getSortOrder());
        vo.setItems(items);
        return vo;
    }

    private PovAssetItemRespVO toItemVO(PovAssetItem item) {
        PovAssetItemRespVO vo = new PovAssetItemRespVO();
        vo.setId(item.getId());
        vo.setAssetId(item.getAssetId());
        vo.setItemKey(item.getItemKey());
        vo.setItemType(item.getItemType());
        vo.setBasedOnItemId(item.getBasedOnItemId());
        vo.setStateDescription(item.getStateDescription());
        vo.setImagePrompt(item.getImagePrompt());
        vo.setNegativePrompt(item.getNegativePrompt());
        vo.setImageUrl(item.getImageUrl());
        vo.setStatus(item.getStatus());
        vo.setErrorMessage(item.getErrorMessage());
        vo.setSortOrder(item.getSortOrder());
        return vo;
    }
}
