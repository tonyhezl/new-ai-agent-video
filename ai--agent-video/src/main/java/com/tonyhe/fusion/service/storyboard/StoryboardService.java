package com.tonyhe.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.script.ScriptEpisode;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.mapper.script.ScriptEpisodeMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardItemMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分镜脚本服务（含分镜集、分镜场次、分镜条目管理）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private final StoryboardMapper storyboardMapper;
    private final StoryboardEpisodeMapper episodeMapper;
    private final StoryboardSceneMapper sceneMapper;
    private final StoryboardItemMapper itemMapper;
    private final ScriptEpisodeMapper scriptEpisodeMapper;
    private final TeamService teamService;
    private final SceneStateTimelineService sceneStateTimelineService;

    // ========== 分镜脚本 ==========

    @Cacheable(value = "storyboard", key = "#id")
    public Storyboard getById(Long id) {
        Storyboard sb = storyboardMapper.selectById(id);
        if (sb == null) throw new BusinessException("分镜脚本不存在: " + id);
        return sb;
    }

    @Cacheable(value = "storyboard", key = "'project:' + #projectId")
    public List<Storyboard> listByProject(Long projectId) {
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, projectId)
                .orderByDesc(Storyboard::getCreateTime));
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public Storyboard create(Storyboard storyboard) {
        applyCurrentTeamOwnership(storyboard);
        storyboardMapper.insert(storyboard);
        return storyboard;
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public Storyboard update(Storyboard storyboard) {
        getById(storyboard.getId());
        storyboardMapper.updateById(storyboard);
        return storyboardMapper.selectById(storyboard.getId());
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public void delete(Long id) {
        storyboardMapper.deleteById(id);
    }

    // ========== 分镜集 ==========

    @Cacheable(value = "storyboardEpisode", key = "#id")
    public StoryboardEpisode getEpisodeById(Long id) {
        StoryboardEpisode ep = episodeMapper.selectById(id);
        if (ep == null) throw new BusinessException("分镜集不存在: " + id);
        return ep;
    }

    @Cacheable(value = "storyboardEpisode", key = "'storyboard:' + #storyboardId")
    public List<StoryboardEpisode> listEpisodes(Long storyboardId) {
        return episodeMapper.selectList(new LambdaQueryWrapper<StoryboardEpisode>()
                .eq(StoryboardEpisode::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardEpisode::getSortOrder)
                .orderByAsc(StoryboardEpisode::getEpisodeNumber));
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode createEpisode(StoryboardEpisode episode) {
        if (episode.getScriptEpisodeId() != null) {
            validateScriptEpisodeBinding(episode.getStoryboardId(), episode.getScriptEpisodeId(), null);
        }
        if (episode.getDeletedId() == null) {
            episode.setDeletedId(0L);
        }
        episodeMapper.insert(episode);
        return episode;
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode updateEpisode(StoryboardEpisode episode) {
        StoryboardEpisode existing = getEpisodeById(episode.getId());
        if (episode.getScriptEpisodeId() != null
                && !episode.getScriptEpisodeId().equals(existing.getScriptEpisodeId())) {
            validateScriptEpisodeBinding(existing.getStoryboardId(), episode.getScriptEpisodeId(), episode.getId());
        }
        episodeMapper.updateById(episode);
        return episodeMapper.selectById(episode.getId());
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public void deleteEpisode(Long id) {
        StoryboardEpisode episode = getEpisodeById(id);
        if (episode.getDeletedId() == null || episode.getDeletedId() == 0L) {
            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(id);
            update.setDeletedId(id);
            episodeMapper.updateById(update);
        }
        // 级联删除分镜集下的所有场次 and 条目
        clearEpisodeContent(id);

        episodeMapper.deleteById(id);
    }

    /**
     * 根据剧本分集查找已绑定的分镜集。
     *
     * @param storyboardId    分镜脚本ID
     * @param scriptEpisodeId 剧本分集ID
     * @return 已绑定的分镜集，不存在则返回 null
     */
    public StoryboardEpisode getEpisodeByScriptEpisode(Long storyboardId, Long scriptEpisodeId) {
        if (storyboardId == null || scriptEpisodeId == null) {
            return null;
        }
        return episodeMapper.selectOne(new LambdaQueryWrapper<StoryboardEpisode>()
                .eq(StoryboardEpisode::getStoryboardId, storyboardId)
                .eq(StoryboardEpisode::getScriptEpisodeId, scriptEpisodeId));
    }

    /**
     * 绑定分镜集和剧本分集。
     *
     * @param episodeId       分镜集ID
     * @param scriptEpisodeId 剧本分集ID
     * @return 绑定后的分镜集
     */
    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode bindScriptEpisode(Long episodeId, Long scriptEpisodeId) {
        StoryboardEpisode episode = getEpisodeById(episodeId);
        validateScriptEpisodeBinding(episode.getStoryboardId(), scriptEpisodeId, episodeId);

        StoryboardEpisode update = new StoryboardEpisode();
        update.setId(episodeId);
        update.setScriptEpisodeId(scriptEpisodeId);
        episodeMapper.updateById(update);
        return episodeMapper.selectById(episodeId);
    }

    /**
     * 保存或复用剧本分集对应的分镜集。
     *
     * @param storyboardId     分镜脚本ID
     * @param scriptEpisodeId  剧本分集ID
     * @param episodeNumber    集号
     * @param title            集标题
     * @param synopsis         集梗概
     * @return 已存在或新创建的分镜集
     */
    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode saveEpisodeForScript(Long storyboardId,
                                                  Long scriptEpisodeId,
                                                  Integer episodeNumber,
                                                  String title,
                                                  String synopsis) {
        ScriptEpisode scriptEpisode = validateScriptEpisodeBelongsToStoryboard(storyboardId, scriptEpisodeId);
        StoryboardEpisode existing = getEpisodeByScriptEpisode(storyboardId, scriptEpisodeId);
        Integer resolvedEpisodeNumber = episodeNumber != null ? episodeNumber : scriptEpisode.getEpisodeNumber();

        if (existing != null) {
            // G3: 重新生成分镜时先清理旧数据（场次+镜头），防止叠加
            clearEpisodeContent(existing.getId());

            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(existing.getId());
            update.setEpisodeNumber(resolvedEpisodeNumber);
            update.setTitle(title != null ? title : scriptEpisode.getTitle());
            update.setSynopsis(synopsis != null ? synopsis : scriptEpisode.getSynopsis());
            update.setSortOrder(resolvedEpisodeNumber != null ? resolvedEpisodeNumber - 1 : existing.getSortOrder());
            update.setStatus(1);
            episodeMapper.updateById(update);
            return episodeMapper.selectById(existing.getId());
        }

        StoryboardEpisode episode = StoryboardEpisode.builder()
                .storyboardId(storyboardId)
                .scriptEpisodeId(scriptEpisodeId)
                .episodeNumber(resolvedEpisodeNumber)
                .title(title != null ? title : scriptEpisode.getTitle())
                .synopsis(synopsis != null ? synopsis : scriptEpisode.getSynopsis())
                .sortOrder(resolvedEpisodeNumber != null ? resolvedEpisodeNumber - 1 : 0)
                .status(1)
                .deletedId(0L)
                .build();
        episodeMapper.insert(episode);
        return episode;
    }

    /**
     * 清空指定分镜集下的场次和镜头，保留分镜集及其剧本分集绑定关系。
     *
     * @param episodeId 分镜集ID
     */
    @CacheEvict(value = { "storyboardScene", "storyboardItem" }, allEntries = true)
    @Transactional
    public void clearEpisodeContent(Long episodeId) {
        getEpisodeById(episodeId);
        List<Long> sceneIds = listScenesByEpisode(episodeId).stream()
                .map(StoryboardScene::getId)
                .toList();

        LambdaQueryWrapper<StoryboardItem> itemWrapper = new LambdaQueryWrapper<StoryboardItem>()
                .and(wrapper -> {
                    wrapper.eq(StoryboardItem::getStoryboardEpisodeId, episodeId);
                    if (!sceneIds.isEmpty()) {
                        wrapper.or().in(StoryboardItem::getStoryboardSceneId, sceneIds);
                    }
                });
        itemMapper.delete(itemWrapper);
        sceneMapper.delete(new LambdaQueryWrapper<StoryboardScene>()
                .eq(StoryboardScene::getEpisodeId, episodeId));
    }

    /**
     * 校验剧本分集是否可绑定到指定分镜脚本。
     *
     * @param storyboardId       分镜脚本ID
     * @param scriptEpisodeId    剧本分集ID
     * @param excludedEpisodeId  更新当前分镜集时需要排除的分镜集ID，新建时为 null
     * @return 校验通过后的剧本分集
     */
    private ScriptEpisode validateScriptEpisodeBinding(Long storyboardId,
                                                       Long scriptEpisodeId,
                                                       Long excludedEpisodeId) {
        ScriptEpisode scriptEpisode = validateScriptEpisodeBelongsToStoryboard(storyboardId, scriptEpisodeId);

        LambdaQueryWrapper<StoryboardEpisode> wrapper = new LambdaQueryWrapper<StoryboardEpisode>()
                .eq(StoryboardEpisode::getStoryboardId, storyboardId)
                .eq(StoryboardEpisode::getScriptEpisodeId, scriptEpisodeId);
        if (excludedEpisodeId != null) {
            wrapper.ne(StoryboardEpisode::getId, excludedEpisodeId);
        }
        StoryboardEpisode duplicated = episodeMapper.selectOne(wrapper);
        if (duplicated != null) {
            throw new BusinessException("该剧本分集已绑定到分镜集: " + duplicated.getId());
        }
        return scriptEpisode;
    }

    /**
     * 校验剧本分集是否属于分镜脚本关联的剧本。
     *
     * @param storyboardId    分镜脚本ID
     * @param scriptEpisodeId 剧本分集ID
     * @return 校验通过后的剧本分集
     */
    private ScriptEpisode validateScriptEpisodeBelongsToStoryboard(Long storyboardId, Long scriptEpisodeId) {
        if (storyboardId == null) {
            throw new BusinessException("分镜ID不能为空，无法绑定剧本分集");
        }
        if (scriptEpisodeId == null) {
            throw new BusinessException("剧本分集ID不能为空");
        }

        Storyboard storyboard = getById(storyboardId);
        if (storyboard.getScriptId() == null) {
            throw new BusinessException("分镜未关联剧本，无法绑定剧本分集");
        }

        ScriptEpisode scriptEpisode = scriptEpisodeMapper.selectById(scriptEpisodeId);
        if (scriptEpisode == null) {
            throw new BusinessException("剧本分集不存在: " + scriptEpisodeId);
        }
        if (!storyboard.getScriptId().equals(scriptEpisode.getScriptId())) {
            throw new BusinessException("剧本分集不属于当前分镜关联的剧本");
        }
        return scriptEpisode;
    }

    // ========== 分镜场次 ==========

    @Cacheable(value = "storyboardScene", key = "#id")
    public StoryboardScene getSceneById(Long id) {
        StoryboardScene scene = sceneMapper.selectById(id);
        if (scene == null) throw new BusinessException("分镜场次不存在: " + id);
        return scene;
    }

    @Cacheable(value = "storyboardScene", key = "'episode:' + #episodeId")
    public List<StoryboardScene> listScenesByEpisode(Long episodeId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<StoryboardScene>()
                .eq(StoryboardScene::getEpisodeId, episodeId)
                .orderByAsc(StoryboardScene::getSortOrder));
    }

    public List<StoryboardScene> listScenesByStoryboard(Long storyboardId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<StoryboardScene>()
                .eq(StoryboardScene::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardScene::getSortOrder));
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public StoryboardScene createScene(StoryboardScene scene) {
        if (scene.getStateTimelineVersion() == null) {
            scene.setStateTimelineVersion(0);
        }
        sceneMapper.insert(scene);
        return scene;
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public StoryboardScene updateScene(StoryboardScene scene) {
        getSceneById(scene.getId());
        sceneMapper.updateById(scene);
        return sceneMapper.selectById(scene.getId());
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public void deleteScene(Long id) {
        itemMapper.delete(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardSceneId, id));
        sceneMapper.deleteById(id);
    }

    // ========== 分镜条目 ==========

    @Cacheable(value = "storyboardItem", key = "#id")
    public StoryboardItem getItemById(Long id) {
        StoryboardItem item = itemMapper.selectById(id);
        if (item == null) throw new BusinessException("分镜条目不存在: " + id);
        return item;
    }

    @Cacheable(value = "storyboardItem", key = "'storyboard:' + #storyboardId")
    public List<StoryboardItem> listItems(Long storyboardId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardItem::getSortOrder));
    }

    public List<StoryboardItem> listItemsByScene(Long sceneId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardSceneId, sceneId)
                .orderByAsc(StoryboardItem::getSortOrder));
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem createItem(StoryboardItem item) {
        itemMapper.insert(item);
        if (item.getStoryboardSceneId() != null) {
            sceneStateTimelineService.rebuild(item.getStoryboardSceneId());
        }
        return itemMapper.selectById(item.getId());
    }

    private void applyCurrentTeamOwnership(Storyboard storyboard) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            return;
        }
        TeamService.OwnerScope ownerScope = teamService.getRequiredCurrentOwnerScopeByUser(currentUserId);
        storyboard.setOwnerType(ownerScope.getOwnerType());
        storyboard.setOwnerId(ownerScope.getOwnerId());
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem updateItem(StoryboardItem item) {
        StoryboardItem existing = getItemById(item.getId());
        // updateById 默认跳过 null 字段，需要用 UpdateWrapper 显式置空
        UpdateWrapper<StoryboardItem> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", item.getId());
        // 前端按字段局部更新（仅传被修改的字段），所有字段均需 null 守卫，避免误清空
        if (item.getCharacterIds() != null) {
            wrapper.set("character_ids", item.getCharacterIds());
        }
        if (item.getBeatIds() != null) {
            wrapper.set("beat_ids", item.getBeatIds());
        }
        if (item.getSceneAssetItemId() != null) {
            wrapper.set("scene_asset_item_id", item.getSceneAssetItemId());
        }
        if (item.getPropIds() != null) {
            wrapper.set("prop_ids", item.getPropIds());
        }
        if (item.getCostumeIds() != null) {
            wrapper.set("costume_ids", item.getCostumeIds());
        }
        if (item.getTransition() != null) {
            wrapper.set("transition", item.getTransition());
        }
        if (item.getRemark() != null) {
            wrapper.set("remark", item.getRemark());
        }
        if (item.getGeneratedVideoUrl() != null) {
            wrapper.set("generated_video_url", item.getGeneratedVideoUrl());
        }
        if (item.getVideoPrompt() != null) {
            wrapper.set("video_prompt", item.getVideoPrompt());
        }
        if (item.getGeneratedImageUrl() != null) {
            wrapper.set("generated_image_url", item.getGeneratedImageUrl());
        }
        if (item.getDuration() != null) {
            wrapper.set("duration", item.getDuration());
        }
        if (item.getShotNumber() != null) {
            wrapper.set("shot_number", item.getShotNumber());
        }
        if (item.getShotType() != null) {
            wrapper.set("shot_type", item.getShotType());
        }
        if (item.getCameraAngle() != null) {
            wrapper.set("camera_angle", item.getCameraAngle());
        }
        if (item.getCameraMovement() != null) {
            wrapper.set("camera_movement", item.getCameraMovement());
        }
        if (item.getContent() != null) {
            wrapper.set("content", item.getContent());
        }
        if (item.getDialogue() != null) {
            wrapper.set("dialogue", item.getDialogue());
        }
        if (item.getSound() != null) {
            wrapper.set("sound", item.getSound());
        }
        if (item.getSortOrder() != null) {
            wrapper.set("sort_order", item.getSortOrder());
        }
        if (item.getSceneStateDelta() != null) {
            wrapper.set("scene_state_delta", item.getSceneStateDelta());
        }
        if (item.getContinuityMode() != null) {
            wrapper.set("continuity_mode", item.getContinuityMode());
        }
        if (item.getFirstFrameStateHash() != null) {
            wrapper.set("first_frame_state_hash", item.getFirstFrameStateHash());
        }
        if (item.getLastFrameStateHash() != null) {
            wrapper.set("last_frame_state_hash", item.getLastFrameStateHash());
        }
        if (item.getGeneratedVideoStateHash() != null) {
            wrapper.set("generated_video_state_hash", item.getGeneratedVideoStateHash());
        }
        itemMapper.update(null, wrapper);
        boolean stateRelevantChange = item.getSceneAssetItemId() != null
                && !Objects.equals(item.getSceneAssetItemId(), existing.getSceneAssetItemId());
        stateRelevantChange = stateRelevantChange
                || (item.getSceneStateDelta() != null
                && !Objects.equals(item.getSceneStateDelta(), existing.getSceneStateDelta()));
        stateRelevantChange = stateRelevantChange
                || (item.getContinuityMode() != null
                && !Objects.equals(item.getContinuityMode(), existing.getContinuityMode()));
        stateRelevantChange = stateRelevantChange
                || (item.getSortOrder() != null
                && !Objects.equals(item.getSortOrder(), existing.getSortOrder()));
        if (stateRelevantChange && existing.getStoryboardSceneId() != null) {
            sceneStateTimelineService.rebuild(existing.getStoryboardSceneId());
        }
        return itemMapper.selectById(item.getId());
    }

    /**
     * 更新分镜条目的首帧或尾帧字段。
     *
     * @param itemId    分镜条目ID
     * @param frameType 帧类型：first-首帧，last-尾帧
     * @param imageUrl  图片URL，允许为空，空值表示清空对应帧
     * @param prompt    AI生成提示词，允许为空，空值表示清空对应提示词
     * @return 更新后的分镜条目
     */
    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem updateItemFrame(Long itemId, String frameType, String imageUrl, String prompt) {
        return updateItemFrame(itemId, frameType, imageUrl, prompt, null);
    }

    /**
     * 保存图片生成结果，同时保留用户确认的基础提示词和实际提交给图片模型的最终提示词。
     */
    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem updateGeneratedItemFrame(
            Long itemId, String frameType, String imageUrl, String prompt, String effectivePrompt) {
        return updateItemFrame(itemId, frameType, imageUrl, prompt, effectivePrompt);
    }

    private StoryboardItem updateItemFrame(
            Long itemId, String frameType, String imageUrl, String prompt, String effectivePrompt) {
        StoryboardItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException("分镜条目不存在: " + itemId);
        }
        String normalizedFrameType = normalizeFrameType(frameType);
        String normalizedImageUrl = StringUtils.hasText(imageUrl) ? imageUrl.trim() : null;
        String normalizedPrompt = StringUtils.hasText(prompt) ? prompt.trim() : null;
        String normalizedEffectivePrompt = StringUtils.hasText(effectivePrompt) ? effectivePrompt.trim() : null;
        UpdateWrapper<StoryboardItem> wrapper = new UpdateWrapper<StoryboardItem>()
                .eq("id", itemId);
        if ("first".equals(normalizedFrameType)) {
            wrapper.set("first_frame_image_url", normalizedImageUrl)
                    .set("first_frame_prompt", normalizedPrompt)
                    .set("first_frame_effective_prompt", normalizedEffectivePrompt)
                    .set("first_frame_state_hash", normalizedImageUrl != null ? item.getStateBeforeHash() : null);
        } else {
            wrapper.set("last_frame_image_url", normalizedImageUrl)
                    .set("last_frame_prompt", normalizedPrompt)
                    .set("last_frame_effective_prompt", normalizedEffectivePrompt)
                    .set("last_frame_state_hash", normalizedImageUrl != null ? item.getStateAfterHash() : null);
        }
        itemMapper.update(null, wrapper);
        return itemMapper.selectById(itemId);
    }

    /**
     * 规范化帧类型。
     *
     * @param frameType 原始帧类型
     * @return 规范化后的帧类型
     */
    public String normalizeFrameType(String frameType) {
        String normalizedFrameType = StringUtils.hasText(frameType) ? frameType.trim() : "";
        if ("first".equals(normalizedFrameType) || "last".equals(normalizedFrameType)) {
            return normalizedFrameType;
        }
        throw new BusinessException("帧类型仅支持 first 或 last");
    }

    /**
     * 复用同场次前序镜头的尾帧作为当前镜头的首帧（像素级连贯，跳过 LLM 生图）。
     *
     * @param itemId 当前镜头（需要设置首帧）的分镜条目 ID
     * @return 更新后的分镜条目
     * @throws BusinessException 如果当前镜头是场次首镜、上镜无尾帧图等
     */
    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem reusePrevLastFrameAsFirstFrame(Long itemId) {
        StoryboardItem current = getItemById(itemId);
        if (current.getStoryboardSceneId() == null) {
            throw new BusinessException("当前镜头未关联场次，无法复用上镜尾帧");
        }
        if (!"SEAMLESS".equalsIgnoreCase(current.getContinuityMode())) {
            throw new BusinessException("当前镜头为 CUT 模式，只有 SEAMLESS 模式允许像素级复用上镜尾帧");
        }

        List<StoryboardItem> sceneItems = listItemsByScene(current.getStoryboardSceneId());
        int currentIdx = -1;
        for (int i = 0; i < sceneItems.size(); i++) {
            if (sceneItems.get(i).getId().equals(itemId)) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx <= 0) {
            throw new BusinessException("当前镜头是场次首个镜头，无上镜尾帧可复用");
        }

        StoryboardItem prevItem = sceneItems.get(currentIdx - 1);
        if (prevItem.getLastFrameImageUrl() == null || prevItem.getLastFrameImageUrl().isBlank()) {
            throw new BusinessException("上镜尚未生成尾帧图，无法复用");
        }
        if (!StringUtils.hasText(current.getStateBeforeHash())
                || !StringUtils.hasText(prevItem.getStateAfterHash())
                || !current.getStateBeforeHash().equals(prevItem.getStateAfterHash())) {
            throw new BusinessException("当前镜头开始状态与上镜结束状态不一致，不能复用尾帧");
        }
        if (!StringUtils.hasText(prevItem.getLastFrameStateHash())
                || !prevItem.getLastFrameStateHash().equals(prevItem.getStateAfterHash())) {
            throw new BusinessException("上镜尾帧对应的场景状态已过期，请先重新生成尾帧");
        }

        String prevLastFrameUrl = prevItem.getLastFrameImageUrl().trim();
        String prevLastFramePrompt = prevItem.getLastFramePrompt() != null
                ? prevItem.getLastFramePrompt().trim() : "";
        String prevLastFrameEffectivePrompt = prevItem.getLastFrameEffectivePrompt() != null
                ? prevItem.getLastFrameEffectivePrompt().trim() : null;

        UpdateWrapper<StoryboardItem> wrapper = new UpdateWrapper<StoryboardItem>()
                .eq("id", itemId)
                .set("first_frame_image_url", prevLastFrameUrl)
                .set("first_frame_prompt", prevLastFramePrompt)
                .set("first_frame_effective_prompt", prevLastFrameEffectivePrompt)
                .set("first_frame_state_hash", current.getStateBeforeHash());
        itemMapper.update(null, wrapper);

        log.info("[reusePrevLastFrameAsFirstFrame] 已复用上镜尾帧: itemId={}, prevItemId={}, imageUrl={}",
                itemId, prevItem.getId(), prevLastFrameUrl);

        return itemMapper.selectById(itemId);
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void deleteItem(Long id) {
        StoryboardItem existing = itemMapper.selectById(id);
        itemMapper.deleteById(id);
        if (existing != null && existing.getStoryboardSceneId() != null) {
            sceneStateTimelineService.rebuild(existing.getStoryboardSceneId());
        }
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void batchCreateItems(List<StoryboardItem> items) {
        for (StoryboardItem item : items) {
            itemMapper.insert(item);
        }
        items.stream()
                .map(StoryboardItem::getStoryboardSceneId)
                .filter(id -> id != null)
                .distinct()
                .forEach(sceneStateTimelineService::rebuild);
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void batchUpdateItemSort(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> affectedSceneIds = itemMapper.selectBatchIds(ids).stream()
                .map(StoryboardItem::getStoryboardSceneId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        for (int i = 0; i < ids.size(); i++) {
            StoryboardItem item = new StoryboardItem();
            item.setId(ids.get(i));
            item.setSortOrder(i);
            itemMapper.updateById(item);
        }
        affectedSceneIds.forEach(sceneStateTimelineService::rebuild);
    }
}
