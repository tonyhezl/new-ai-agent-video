package com.tonyhe.fusion.service.pov;

import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.pov.PovAsset;
import com.tonyhe.fusion.entity.pov.PovStory;
import com.tonyhe.fusion.mapper.pov.PovAssetItemMapper;
import com.tonyhe.fusion.mapper.pov.PovAssetMapper;
import com.tonyhe.fusion.mapper.pov.PovShotAssetMapper;
import com.tonyhe.fusion.mapper.pov.PovShotMapper;
import com.tonyhe.fusion.mapper.pov.PovShotSpeakerMapper;
import com.tonyhe.fusion.mapper.pov.PovStoryMapper;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.consumer.AudioGenerationConsumer;
import com.tonyhe.fusion.service.generation.consumer.ImageGenerationConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PovAssetServiceTests {

    @Mock private PovStoryMapper storyMapper;
    @Mock private PovAssetMapper assetMapper;
    @Mock private PovAssetItemMapper itemMapper;
    @Mock private PovShotMapper shotMapper;
    @Mock private PovShotAssetMapper shotAssetMapper;
    @Mock private PovShotSpeakerMapper shotSpeakerMapper;
    @Mock private AiModelService aiModelService;
    @Mock private GenerationModelCapabilityService capabilityService;
    @Mock private ImageGenerationConsumer imageGenerationConsumer;
    @Mock private ImageGenerationService imageGenerationService;
    @Mock private AudioGenerationConsumer audioGenerationConsumer;
    @Mock private AudioGenerationService audioGenerationService;

    @InjectMocks private PovAssetService service;

    @Test
    void referenceAudioCanBeSubmittedWithoutStoryboardLineage() {
        PovStory story = PovStory.builder().id(10L).userId(7L).projectId(null).build();
        PovAsset asset = PovAsset.builder()
                .id(20L)
                .storyId(10L)
                .assetRole("POV_CHARACTER")
                .name("夜班火化工")
                .characterNo(1)
                .voiceSampleText("我按夜班守则没有回答。")
                .voiceConfig("{\"voiceId\":\"zh-CN-YunyangNeural\"}")
                .voiceRequired(true)
                .voiceStatus("PENDING")
                .build();
        AiModel model = new AiModel();
        model.setId(30L);
        model.setCode("tts-model");
        model.setModelType(4);

        when(storyMapper.selectById(10L)).thenReturn(story);
        when(assetMapper.selectById(20L)).thenReturn(asset);
        when(aiModelService.getDefaultByType(4)).thenReturn(model);
        when(audioGenerationConsumer.submitTask(any())).thenReturn("audio-task-1");
        when(shotSpeakerMapper.selectList(any())).thenReturn(List.of());

        String taskId = service.generateReferenceAudio(10L, 20L, null, 7L);

        ArgumentCaptor<AudioTask> taskCaptor = ArgumentCaptor.forClass(AudioTask.class);
        verify(audioGenerationConsumer).submitTask(taskCaptor.capture());
        AudioTask task = taskCaptor.getValue();
        assertThat(taskId).isEqualTo("audio-task-1");
        assertThat(task.getProjectId()).isNull();
        assertThat(task.getStoryboardId()).isNull();
        assertThat(task.getStoryboardEpisodeId()).isNull();
        assertThat(task.getStoryboardItemId()).isNull();
        assertThat(task.getDialogueType()).isEqualTo("POV_REFERENCE");
        assertThat(task.getText()).isEqualTo("我按夜班守则没有回答。");
        assertThat(task.getVoiceConfig()).contains("zh-CN-YunyangNeural");
        assertThat(asset.getReferenceAudioTaskId()).isEqualTo("audio-task-1");
        assertThat(asset.getVoiceStatus()).isEqualTo("GENERATING");
        verify(assetMapper).updateById(asset);
    }
}
