package com.tonyhe.fusion.service.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.mapper.generation.AudioTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 配音任务服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationService {

    private final AudioTaskMapper audioTaskMapper;

    @Cacheable(value = "audioTask", key = "#id")
    public AudioTask getById(Long id) {
        AudioTask task = audioTaskMapper.selectById(id);
        if (task == null) throw new BusinessException("配音任务不存在: " + id);
        return task;
    }

    @Cacheable(value = "audioTask", key = "'taskId:' + #taskId")
    public AudioTask getByTaskId(String taskId) {
        AudioTask task = audioTaskMapper.selectOne(
                new LambdaQueryWrapper<AudioTask>().eq(AudioTask::getTaskId, taskId));
        if (task == null) throw new BusinessException("配音任务不存在: " + taskId);
        return task;
    }

    public List<AudioTask> listByItemId(Long storyboardItemId) {
        return audioTaskMapper.selectList(
                new LambdaQueryWrapper<AudioTask>()
                        .eq(AudioTask::getStoryboardItemId, storyboardItemId)
                        .orderByAsc(AudioTask::getSeq));
    }

    public List<AudioTask> listByEpisodeId(Long episodeId) {
        return audioTaskMapper.selectList(
                new LambdaQueryWrapper<AudioTask>()
                        .eq(AudioTask::getStoryboardEpisodeId, episodeId)
                        .orderByAsc(AudioTask::getStoryboardItemId)
                        .orderByAsc(AudioTask::getSeq));
    }

    @CacheEvict(value = "audioTask", allEntries = true)
    @Transactional
    public AudioTask create(AudioTask task) {
        audioTaskMapper.insert(task);
        return task;
    }

    @CacheEvict(value = "audioTask", allEntries = true)
    @Transactional
    public AudioTask update(AudioTask task) {
        audioTaskMapper.updateById(task);
        return task;
    }

    @CacheEvict(value = "audioTask", allEntries = true)
    @Transactional
    public void updateStatus(Long id, Integer status, String errorMessage) {
        AudioTask task = getById(id);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        audioTaskMapper.updateById(task);
    }

    @CacheEvict(value = "audioTask", allEntries = true)
    @Transactional
    public void delete(Long id) {
        audioTaskMapper.deleteById(id);
    }
}
