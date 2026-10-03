package com.tonyhe.fusion.service.generation.voiceover;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.consumer.AudioGenerationConsumer;
import com.tonyhe.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配音编排服务
 * <p>
 * 负责扫描分镜集 → 解析台词 → 匹配角色音色 → 创建 AudioTask → 入队。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceoverService {

    private final StoryboardService storyboardService;
    private final AudioGenerationService audioGenerationService;
    private final AudioGenerationConsumer audioGenerationConsumer;
    private final DialogueParser dialogueParser;
    private final VoiceConfigService voiceConfigService;
    private final AssetService assetService;
    private final AiModelService aiModelService;

    private static final int MODEL_TYPE_TTS = 4;

    /**
     * 预览配音台词：解析指定范围镜头的对白，返回按镜头分组的台词列表
     *
     * @param storyboardId 分镜ID
     * @param episodeId    分镜集ID
     * @param sceneId      分镜场次ID（可选，指定则只返回该场次的镜头）
     * @return 按镜头分组的台词预览列表
     */
    public List<ItemDialoguePreview> previewDialogue(Long storyboardId, Long episodeId, Long sceneId) {
        List<StoryboardItem> allItems = storyboardService.listItems(storyboardId);
        List<StoryboardItem> filteredItems = allItems.stream()
                .filter(item -> episodeId.equals(item.getStoryboardEpisodeId()))
                .filter(item -> sceneId == null || sceneId.equals(item.getStoryboardSceneId()))
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .toList();

        // 预加载角色资产用于匹配展示
        Storyboard storyboard = storyboardService.getById(storyboardId);
        Map<String, Asset> characterNameMap = loadCharacterAssets(storyboard.getProjectId());

        List<ItemDialoguePreview> previews = new ArrayList<>();
        for (StoryboardItem item : filteredItems) {
            if (StrUtil.isBlank(item.getDialogue())) {
                continue;
            }
            List<DialogueParser.AudioLine> lines = dialogueParser.parse(item.getDialogue());
            if (lines.isEmpty()) {
                continue;
            }

            List<ItemDialoguePreview.LinePreview> linePreviews = new ArrayList<>();
            for (DialogueParser.AudioLine line : lines) {
                Asset matchedAsset = characterNameMap.get(line.getCharacterName());
                String voiceConfigJson = null;
                boolean hasVoiceConfig = false;
                if (matchedAsset != null) {
                    VoiceConfigService.VoiceConfig vc = voiceConfigService.extractVoiceConfig(matchedAsset);
                    if (vc != null) {
                        voiceConfigJson = JSONUtil.toJsonStr(vc);
                        hasVoiceConfig = StrUtil.isNotBlank(vc.getVoiceId());
                    }
                }
                linePreviews.add(new ItemDialoguePreview.LinePreview(
                        line.getSeq(),
                        line.getCharacterName(),
                        line.getText(),
                        line.getDialogueType(),
                        matchedAsset != null ? matchedAsset.getId() : null,
                        matchedAsset != null ? matchedAsset.getName() : null,
                        voiceConfigJson,
                        hasVoiceConfig,
                        item.getAudioStatus()));
            }
            previews.add(new ItemDialoguePreview(
                    item.getId(),
                    item.getStoryboardSceneId(),
                    item.getShotNumber() != null ? item.getShotNumber() : String.valueOf(item.getId()),
                    item.getAudioStatus(),
                    item.getAudioUrl(),
                    linePreviews));
        }
        return previews;
    }

    /**
     * 批量生成配音（按分镜集，可指定镜头列表）
     *
     * @param storyboardId 分镜ID
     * @param episodeId    分镜集ID
     * @param itemIds      指定生成的镜头ID列表（null 或空则生成全部有对白的镜头）
     * @return 任务总数
     */
    public int generateVoiceover(Long storyboardId, Long episodeId, List<Long> itemIds, Long userId) {
        Storyboard storyboard = storyboardService.getById(storyboardId);
        Long projectId = storyboard.getProjectId();

        // 获取该集所有镜头
        List<StoryboardItem> allItems = storyboardService.listItems(storyboardId);
        List<StoryboardItem> episodeItems = allItems.stream()
                .filter(item -> episodeId.equals(item.getStoryboardEpisodeId()))
                .toList();

        if (episodeItems.isEmpty()) {
            throw new BusinessException("该集没有分镜镜头");
        }

        // 如果指定了 itemIds，过滤
        Set<Long> itemIdSet = itemIds != null && !itemIds.isEmpty()
                ? Set.copyOf(itemIds) : null;
        if (itemIdSet != null) {
            episodeItems = episodeItems.stream()
                    .filter(item -> itemIdSet.contains(item.getId()))
                    .toList();
            if (episodeItems.isEmpty()) {
                throw new BusinessException("选中的镜头不在该集中");
            }
        }

        // 预加载项目中所有角色资产（用于角色名匹配）
        Map<String, Asset> characterNameMap = loadCharacterAssets(projectId);

        // 获取默认 TTS 模型
        com.tonyhe.fusion.entity.ai.AiModel ttsModel = resolveTtsModel();

        int taskCount = 0;
        for (StoryboardItem item : episodeItems) {
            if (StrUtil.isBlank(item.getDialogue())) {
                continue;
            }

            List<DialogueParser.AudioLine> lines = dialogueParser.parse(item.getDialogue());
            if (lines.isEmpty()) {
                continue;
            }

            // 更新镜头音频状态为生成中
            item.setAudioStatus(1);
            storyboardService.updateItem(item);

            for (DialogueParser.AudioLine line : lines) {
                AudioTask task = buildAudioTask(
                        projectId, storyboardId, episodeId, item.getId(), userId,
                        line, characterNameMap, ttsModel);
                audioGenerationConsumer.submitTask(task);
                taskCount++;
            }
        }

        log.info("[Voiceover] 批量配音已提交: storyboardId={}, episodeId={}, itemIds={}, taskCount={}",
                storyboardId, episodeId, itemIdSet != null ? itemIdSet : "ALL", taskCount);
        return taskCount;
    }

    /**
     * 单条重生成
     */
    public String regenerate(Long audioTaskId) {
        AudioTask existing = audioGenerationService.getById(audioTaskId);
        AudioTask newTask = AudioTask.builder()
                .projectId(existing.getProjectId())
                .userId(existing.getUserId())
                .storyboardId(existing.getStoryboardId())
                .storyboardEpisodeId(existing.getStoryboardEpisodeId())
                .storyboardItemId(existing.getStoryboardItemId())
                .seq(existing.getSeq())
                .text(existing.getText())
                .characterName(existing.getCharacterName())
                .characterAssetId(existing.getCharacterAssetId())
                .voiceConfig(existing.getVoiceConfig())
                .dialogueType(existing.getDialogueType())
                .modelId(existing.getModelId())
                .retryCount(existing.getRetryCount() + 1)
                .parentTaskId(existing.getTaskId())
                .build();
        return audioGenerationConsumer.submitTask(newTask);
    }

    /**
     * 查询配音列表（按集）
     */
    public List<AudioTask> listByEpisode(Long episodeId) {
        return audioGenerationService.listByEpisodeId(episodeId);
    }

    // ========== 内部方法 ==========

    private AudioTask buildAudioTask(Long projectId, Long storyboardId, Long episodeId,
                                     Long itemId, Long userId, DialogueParser.AudioLine line,
                                     Map<String, Asset> characterNameMap,
                                     com.tonyhe.fusion.entity.ai.AiModel ttsModel) {
        // 匹配角色
        Asset matchedAsset = characterNameMap.get(line.getCharacterName());
        String voiceConfigJson = null;

        if (matchedAsset != null) {
            VoiceConfigService.VoiceConfig vc = voiceConfigService.extractVoiceConfig(matchedAsset);
            if (vc != null) {
                voiceConfigJson = JSONUtil.toJsonStr(vc);
            }
        }

        return AudioTask.builder()
                .projectId(projectId)
                .userId(userId)
                .storyboardId(storyboardId)
                .storyboardEpisodeId(episodeId)
                .storyboardItemId(itemId)
                .seq(line.getSeq())
                .text(line.getText())
                .characterName(line.getCharacterName())
                .characterAssetId(matchedAsset != null ? matchedAsset.getId() : null)
                .voiceConfig(voiceConfigJson)
                .dialogueType(line.getDialogueType())
                .modelId(ttsModel.getId())
                .modelCode(ttsModel.getCode())
                .build();
    }

    private Map<String, Asset> loadCharacterAssets(Long projectId) {
        List<Asset> assets = assetService.listByProject(projectId, "character", null);
        Map<String, Asset> map = new HashMap<>();
        for (Asset asset : assets) {
            if (StrUtil.isNotBlank(asset.getName())) {
                map.put(asset.getName().trim(), asset);
            }
        }
        return map;
    }

    private com.tonyhe.fusion.entity.ai.AiModel resolveTtsModel() {
        com.tonyhe.fusion.entity.ai.AiModel model = aiModelService.getDefaultByType(MODEL_TYPE_TTS);
        if (model != null) {
            return model;
        }
        List<com.tonyhe.fusion.entity.ai.AiModel> ttsModels = aiModelService.getListByType(MODEL_TYPE_TTS);
        if (ttsModels.isEmpty()) {
            throw new BusinessException("未配置 TTS 模型，请先在 AI 服务管理中添加 TTS 模型");
        }
        return ttsModels.get(0);
    }

    /**
     * 镜头对白预览（用于前端展示和勾选）
     */
    public record ItemDialoguePreview(
            Long itemId,
            Long sceneId,
            String shotNumber,
            Integer audioStatus,
            String audioUrl,
            List<LinePreview> lines
    ) {
        public record LinePreview(
                int seq,
                String characterName,
                String text,
                String dialogueType,
                Long characterAssetId,
                String characterAssetName,
                String voiceConfig,
                boolean hasVoiceConfig,
                Integer itemAudioStatus
        ) {}
    }
}
