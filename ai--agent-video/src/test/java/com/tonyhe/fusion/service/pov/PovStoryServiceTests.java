package com.tonyhe.fusion.service.pov;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.entity.pov.*;
import com.tonyhe.fusion.mapper.pov.*;
import com.tonyhe.fusion.service.ai.AiModelService;
import com.tonyhe.fusion.service.ai.model.AiModelMetadataResolver;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.generation.GenerationModelCapabilityService;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.UserService;
import com.tonyhe.fusion.service.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PovStoryServiceTests {

    @Mock private PovStoryMapper storyMapper;
    @Mock private PovShotMapper shotMapper;
    @Mock private PovAssetMapper assetMapper;
    @Mock private PovAssetItemMapper assetItemMapper;
    @Mock private PovShotAssetMapper shotAssetMapper;
    @Mock private PovShotSpeakerMapper shotSpeakerMapper;
    @Mock private PovAssetService povAssetService;
    @Mock private PovVideoComposeService composeService;
    @Mock private VideoGenerationConsumer videoConsumer;
    @Mock private VideoGenerationService videoService;
    @Mock private AudioGenerationService audioService;
    @Mock private AiModelService aiModelService;
    @Mock private AiModelMetadataResolver metadataResolver;
    @Mock private GenerationModelCapabilityService capabilityService;
    @Mock private ProjectService projectService;
    @Mock private UserService userService;
    @Mock private TeamService teamService;

    private PovStoryService service;

    @BeforeEach
    void setUp() {
        service = new PovStoryService(
                storyMapper, shotMapper, assetMapper, assetItemMapper, shotAssetMapper, shotSpeakerMapper,
                povAssetService, composeService, videoConsumer, videoService, audioService,
                aiModelService, metadataResolver, capabilityService, projectService, userService, teamService);
    }

    @Test
    void directVideoUsesStoryAssetsAndStructuredCharacterAudioWithoutFrames() {
        PovStory story = PovStory.builder().id(10L).userId(7L).projectId(20L).build();
        PovShot shot = PovShot.builder().id(30L).storyId(10L).shotOrder(1)
                .videoPrompt("第一人称抬头看向管理员").negativePrompt("third-person")
                .cameraMovement("SLOW_TILT_UP").durationSec(BigDecimal.valueOf(5)).build();
        PovAsset character = PovAsset.builder().id(40L).storyId(10L).name("管理员")
                .characterNo(2).voiceStatus("READY").referenceAudioUrl("https://media/voice.mp3").build();
        PovAssetItem item = PovAssetItem.builder().id(50L).assetId(40L).itemKey("initial")
                .stateDescription("管理员常态").status("READY").imageUrl("https://media/character.png").build();
        PovShotAsset binding = PovShotAsset.builder().shotId(30L).assetItemId(50L).referenceOrder(1).required(true).build();
        PovShotSpeaker speaker = PovShotSpeaker.builder().shotId(30L).assetId(40L).characterNo(2)
                .dialogueText("这里从来没有地下室。").sortOrder(1).build();
        AiModel model = new AiModel();
        model.setId(60L);
        model.setCode("wan-video");
        model.setModelType(3);

        when(storyMapper.selectById(10L)).thenReturn(story);
        when(shotMapper.selectById(30L)).thenReturn(shot);
        when(shotAssetMapper.selectList(any())).thenReturn(List.of(binding));
        when(assetItemMapper.selectById(50L)).thenReturn(item);
        when(assetMapper.selectById(40L)).thenReturn(character);
        when(shotSpeakerMapper.selectList(any())).thenReturn(List.of(speaker));
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(metadataResolver.resolvePlatform(model)).thenReturn("dashscope");
        when(metadataResolver.normalizePlatform("dashscope")).thenReturn("dashscope");
        when(capabilityService.resolveVideoCapability(model, "dashscope")).thenReturn(videoCapability(true, true));
        when(videoConsumer.submitTask(any())).thenReturn("video-task-1");

        String taskId = service.generateShotVideo(10L, 30L, null, 7L);

        ArgumentCaptor<VideoTask> captor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoConsumer).submitTask(captor.capture());
        VideoTask task = captor.getValue();
        assertThat(taskId).isEqualTo("video-task-1");
        assertThat(task.getGenerateMode()).isEqualTo("text2video");
        assertThat(task.getFirstFrameImageUrl()).isNull();
        assertThat(task.getLastFrameImageUrl()).isNull();
        assertThat(task.getReferenceImageUrls()).isEqualTo("[\"https://media/character.png\"]");
        assertThat(task.getReferenceAudioUrls()).contains("imageUrl", "audioUrl", "https://media/voice.mp3");
        assertThat(task.getPrompt()).contains("角色2是参考图片1", "声音严格参考音频1", "这里从来没有地下室");
        assertThat(task.getCategory()).isEqualTo("POV_DIRECT_VIDEO");
        verify(capabilityService).validateVideoTask(model, task, "dashscope");
    }

    @Test
    void imageGenerationIsRejectedByCompatibilityRoute() {
        assertThatThrownBy(() -> service.generateShotMedia(10L, 30L, "IMAGE", null, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再生成首尾帧或镜头图片");
        verifyNoInteractions(storyMapper, shotMapper, videoConsumer);
    }

    @Test
    void speakingCharacterWithoutReferenceAudioBlocksVideoSubmission() {
        PovStory story = PovStory.builder().id(10L).userId(7L).build();
        PovShot shot = PovShot.builder().id(30L).storyId(10L).shotOrder(1).durationSec(BigDecimal.valueOf(5)).build();
        PovAsset character = PovAsset.builder().id(40L).storyId(10L).name("管理员")
                .characterNo(2).voiceStatus("PENDING").build();
        PovAssetItem item = PovAssetItem.builder().id(50L).assetId(40L).status("READY").imageUrl("https://media/character.png").build();
        AiModel model = new AiModel();
        model.setId(60L);
        model.setModelType(3);

        when(storyMapper.selectById(10L)).thenReturn(story);
        when(shotMapper.selectById(30L)).thenReturn(shot);
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(shotAssetMapper.selectList(any())).thenReturn(List.of(
                PovShotAsset.builder().shotId(30L).assetItemId(50L).required(true).build()));
        when(assetItemMapper.selectById(50L)).thenReturn(item);
        when(assetMapper.selectById(40L)).thenReturn(character);
        when(shotSpeakerMapper.selectList(any())).thenReturn(List.of(
                PovShotSpeaker.builder().shotId(30L).assetId(40L).characterNo(2).dialogueText("台词").build()));

        assertThatThrownBy(() -> service.generateShotVideo(10L, 30L, null, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发声角色尚未生成参考音频");
        verifyNoInteractions(videoConsumer);
    }

    private GenerationModelCapabilityService.VideoModelCapability videoCapability(
            boolean referenceImages, boolean referenceAudios) {
        return new GenerationModelCapabilityService.VideoModelCapability(
                false, false, referenceImages, false, referenceAudios,
                false, 0, 10, 10, 0, 10);
    }
}
