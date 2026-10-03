package com.tonyhe.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.controller.storyboard.vo.ComposeSegmentReqVO;
import com.tonyhe.fusion.entity.storage.StorageConfig;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardEpisode;
import com.tonyhe.fusion.entity.generation.AudioTask;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.entity.storyboard.StoryboardScene;
import com.tonyhe.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.tonyhe.fusion.service.generation.AudioGenerationService;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import com.tonyhe.fusion.service.storage.StorageConfigService;
import com.tonyhe.fusion.service.task.TaskStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 按集合成视频服务。
 * <p>
 * 流程：取该集所有场次的所有镜头视频（按 sortOrder 排序），下载到临时目录，
 * 用 ffmpeg concat 拼接（首选 filter_complex 归一化重编码，避免镜头衔接处掉帧卡顿；
 * 失败则 fallback 到 demuxer 零转码流拷贝），
 * 然后通过 MediaStorageService 持久化，更新 episode 的合成状态与URL。
 */
@Service
@Slf4j
public class VideoComposeService {

    private static final String LOCAL_MEDIA_PUBLIC_PREFIX = "/media/";
    private static final String TASK_TYPE = "storyboard_episode_compose";
    private static final String TASK_CONTEXT_TYPE = "storyboard_episode";
    private static final String TASK_INITIAL_MESSAGE = "已提交合成任务，正在拼接镜头视频…";

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;

    /** 合成段：视频URL + 可选裁剪参数 + 可选转场参数 + 镜头ID（用于配音混入） */
    private static class ComposeSegment {
        final String url;
        final Long itemId;
        final Double startSec;
        final Double endSec;
        final String transition;
        final Double transitionDuration;

        ComposeSegment(String url, Long itemId, Double startSec, Double endSec, String transition, Double transitionDuration) {
            this.url = url;
            this.itemId = itemId;
            this.startSec = startSec;
            this.endSec = endSec;
            this.transition = transition;
            this.transitionDuration = transitionDuration;
        }

        boolean needsTrim() {
            return startSec != null || endSec != null;
        }
    }

    private final StoryboardService storyboardService;
    private final StoryboardEpisodeMapper episodeMapper;
    private final MediaStorageService mediaStorageService;
    private final StorageConfigService storageConfigService;
    private final TaskStreamService taskStreamService;
    private final AudioGenerationService audioGenerationService;
    private final Executor videoComposeExecutor;

    @Value("${app.storage.local-base-path:./data/media}")
    private String mediaLocalPath;

    @Value("${video.compose.allowed-hosts:}")
    private String allowedHostsConfig;

    @Value("${video.compose.max-redirects:3}")
    private int maxRedirects;

    @Value("${video.compose.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${video.compose.ffprobe-path:ffprobe}")
    private String ffprobePath;

    public VideoComposeService(StoryboardService storyboardService,
                               StoryboardEpisodeMapper episodeMapper,
                               MediaStorageService mediaStorageService,
                               StorageConfigService storageConfigService,
                               TaskStreamService taskStreamService,
                               AudioGenerationService audioGenerationService,
                               @Qualifier("videoComposeExecutor") Executor videoComposeExecutor) {
        this.storyboardService = storyboardService;
        this.episodeMapper = episodeMapper;
        this.mediaStorageService = mediaStorageService;
        this.storageConfigService = storageConfigService;
        this.taskStreamService = taskStreamService;
        this.audioGenerationService = audioGenerationService;
        this.videoComposeExecutor = videoComposeExecutor;
    }

    @jakarta.annotation.PostConstruct
    public void cleanupStaleComposeStatus() {
        int cleaned = episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
                .eq("compose_status", STATUS_RUNNING)
                .set("compose_status", STATUS_FAILED)
                .set("compose_error_msg", "合成被中断（服务重启）"));
        if (cleaned > 0) {
            log.warn("[VideoCompose] 启动时清理了 {} 个中断的合成任务状态", cleaned);
        }
        // 任务中心读的是 agent_conversation.status，需一并清理
        int taskCleaned = taskStreamService.failRunningByAgentType(TASK_TYPE);
        if (taskCleaned > 0) {
            log.warn("[VideoCompose] 启动时清理了 {} 个任务中心残留合成任务", taskCleaned);
        }
    }

    /**
     * 提交合成任务。同步标记状态为 RUNNING，异步执行实际合成。
     * 若已在合成中则抛出异常。
     */
    public String submitCompose(Long episodeId, Long userId) {
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException(404, "分镜集不存在: " + episodeId);
        }

        Storyboard storyboard = storyboardService.getById(episode.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + episode.getStoryboardId());
        }

        String taskId = taskStreamService.createTask(
                userId,
                storyboard.getProjectId(),
                TASK_TYPE,
                buildTaskTitle(episode),
                TASK_CONTEXT_TYPE,
                episodeId,
                TASK_INITIAL_MESSAGE
        );

        List<VideoSource> videoSources = collectVideoSources(episodeId);
        if (videoSources.isEmpty()) {
            String message = "本集没有可合成的视频，请先生成镜头视频";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
            return taskId;
        }

        int updated = episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
            .eq("id", episodeId)
            .ne("compose_status", STATUS_RUNNING)
            .set("compose_status", STATUS_RUNNING)
            .set("compose_error_msg", null)
            .set("composed_video_url", null)
            .set("composed_at", null));
        if (updated == 0) {
            taskStreamService.fail(taskId, "本集已在合成中，请稍候");
            return taskId;
        }

        try {
            videoComposeExecutor.execute(() -> {
                try {
                    doCompose(episodeId, taskId, videoSources);
                } catch (Throwable t) {
                    String errorMessage = resolveErrorMessage(t);
                    log.error("[VideoCompose] 合成失败: episodeId={}", episodeId, t);
                    markFailed(episodeId, errorMessage);
                    taskStreamService.fail(taskId, errorMessage);
                }
            });
        } catch (RejectedExecutionException e) {
            String message = "合成队列繁忙，请稍后重试";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
        }
        return taskId;
    }

    /**
     * 提交合成任务（带选配参数）。只合成指定的视频段，支持按时间段裁剪。
     */
    public String submitCompose(Long episodeId, Long userId, List<ComposeSegmentReqVO> segments,
                                 String transition, Double transitionDuration) {
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException(404, "分镜集不存在: " + episodeId);
        }

        Storyboard storyboard = storyboardService.getById(episode.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + episode.getStoryboardId());
        }

        String taskId = taskStreamService.createTask(
                userId,
                storyboard.getProjectId(),
                TASK_TYPE,
                buildTaskTitle(episode),
                TASK_CONTEXT_TYPE,
                episodeId,
                TASK_INITIAL_MESSAGE
        );

        List<ComposeSegment> composeSegments = collectSegments(episodeId, segments);
        if (composeSegments.isEmpty()) {
            String message = "选配的视频段为空，请至少选择一个视频";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
            return taskId;
        }

        int updated = episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
            .eq("id", episodeId)
            .ne("compose_status", STATUS_RUNNING)
            .set("compose_status", STATUS_RUNNING)
            .set("compose_error_msg", null)
            .set("composed_video_url", null)
            .set("composed_at", null));
        if (updated == 0) {
            taskStreamService.fail(taskId, "本集已在合成中，请稍候");
            return taskId;
        }

        try {
            videoComposeExecutor.execute(() -> {
                try {
                    doComposeSegments(episodeId, taskId, composeSegments, transition, transitionDuration);
                } catch (Throwable t) {
                    String errorMessage = resolveErrorMessage(t);
                    log.error("[VideoCompose] 合成失败: episodeId={}", episodeId, t);
                    markFailed(episodeId, errorMessage);
                    taskStreamService.fail(taskId, errorMessage);
                }
            });
        } catch (RejectedExecutionException e) {
            String message = "合成队列繁忙，请稍后重试";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
        }
        return taskId;
    }

    private void doCompose(Long episodeId, String taskId, List<VideoSource> videoSources) throws Exception {
        log.info("[VideoCompose] 开始合成 episodeId={}, taskId={}", episodeId, taskId);
        long startMs = System.currentTimeMillis();
        log.info("[VideoCompose] episodeId={}, 待合成视频数={}", episodeId, videoSources.size());

        Path workDir = Files.createTempDirectory("compose_ep_" + episodeId + "_");
        try {
            List<Path> localFiles = new ArrayList<>();
            for (int i = 0; i < videoSources.size(); i++) {
                VideoSource src = videoSources.get(i);
                Path downloaded = workDir.resolve(String.format("src_%04d.mp4", i));
                downloadToFile(src.url(), downloaded);

                // 尝试混入配音音频
                Path withAudio = mixVoiceoverForItem(downloaded, src.itemId(), i, workDir);
                localFiles.add(withAudio != null ? withAudio : downloaded);
            }

            Path listFile = workDir.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : localFiles) {
                String s = f.toAbsolutePath().toString().replace("'", "'\\''");
                sb.append("file '").append(s).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            Path output = workDir.resolve("output.mp4");
            // xfade 过渡需要所有视频同时解码进内存，段数过多时内存暴涨易 OOM（exit 137）
            // 段数 <=4 时使用 xfade 交叉淡入淡出；>4 时用两两合并策略（每次只处理2个视频，结果再两两合并）
            // 原始模式（无选配）默认全部用 none（硬切），保持场次内连续性
            List<String> defaultTransitions = new ArrayList<>();
            List<Double> defaultDurations = new ArrayList<>();
            for (int i = 0; i < localFiles.size() - 1; i++) {
                defaultTransitions.add("none");
                defaultDurations.add(XFADE_DURATION);
            }
            boolean ok = false;
            if (localFiles.size() <= 4) {
                ok = runFfmpegXfadeConcat(localFiles, output, defaultTransitions, defaultDurations);
                if (!ok) {
                    log.warn("[VideoCompose] xfade 过渡重编码失败，回退到普通 concat 重编码 episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegFilterConcat(localFiles, output);
                }
            } else {
                log.info("[VideoCompose] 视频段数={}，使用两两合并策略 episodeId={}", localFiles.size(), episodeId);
                ok = runFfmpegPairwiseMerge(localFiles, workDir, output, defaultTransitions, defaultDurations);
                if (!ok) {
                    log.warn("[VideoCompose] 两两合并失败，回退到两步法 episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegTwoStepConcat(localFiles, workDir, output);
                }
            }
            if (!ok) {
                log.warn("[VideoCompose] 普通 concat 重编码也失败，尝试两步法归一化 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                ok = runFfmpegTwoStepConcat(localFiles, workDir, output);
            }
            if (!ok) {
                log.warn("[VideoCompose] 两步法也失败，尝试 concat demuxer 重编码 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                VideoStreamInfo target = probeVideoInfo(localFiles.get(0));
                ok = runFfmpegConcatDemuxerReencode(listFile, output, target);
            }
            if (!ok) {
                log.warn("[VideoCompose] 重编码也失败，最后尝试 concat demuxer 流拷贝 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                ok = runFfmpegConcatDemuxer(listFile, output);
            }
            if (!ok || !Files.exists(output) || Files.size(output) == 0) {
                throw new RuntimeException("ffmpeg 合成失败（xfade 与 concat 与 filter 均失败）");
            }

            String storedUrl = mediaStorageService.storeFile(output, "videos/composed", "mp4");
            log.info("[VideoCompose] 已保存到存储: {}", storedUrl);

            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposedVideoUrl(storedUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            episodeMapper.updateById(update);

                taskStreamService.complete(taskId, "✓ 合成完成 · 视频地址：" + storedUrl);

            log.info("[VideoCompose] 完成 episodeId={}, 耗时={}ms, 视频数={}",
                    episodeId, System.currentTimeMillis() - startMs, videoSources.size());
        } finally {
            try {
                FileSystemUtils.deleteRecursively(workDir.toFile());
            } catch (Exception e) {
                log.warn("[VideoCompose] 临时目录清理失败 {}", workDir, e);
            }
        }
    }

    private String buildTaskTitle(StoryboardEpisode episode) {
        String episodeLabel = StringUtils.hasText(episode.getTitle())
                ? episode.getTitle().trim()
                : (episode.getEpisodeNumber() != null
                ? "第 " + episode.getEpisodeNumber() + " 集"
                : "集 " + episode.getId());
        return "合成本集视频：" + episodeLabel;
    }

    private String resolveErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "合成失败";
        }
        if (isExecutableMissing(throwable)) {
            return buildMissingExecutableMessage("ffmpeg", "video.compose.ffmpeg-path", getFfmpegExecutable());
        }
        if (StringUtils.hasText(throwable.getMessage())) {
            return throwable.getMessage();
        }
        return "合成失败";
    }

    /** 视频源：URL + 镜头ID（用于配音混入） */
    private record VideoSource(String url, Long itemId) {}

    private List<VideoSource> collectVideoSources(Long episodeId) {
        List<StoryboardScene> scenes = new ArrayList<>(storyboardService.listScenesByEpisode(episodeId));
        scenes.sort(Comparator.comparing(s -> Optional.ofNullable(s.getSortOrder()).orElse(0)));

        List<VideoSource> sources = new ArrayList<>();
        for (StoryboardScene scene : scenes) {
            List<StoryboardItem> items = new ArrayList<>(storyboardService.listItemsByScene(scene.getId()));
            items.sort(Comparator.comparing(i -> Optional.ofNullable(i.getSortOrder()).orElse(0)));
            for (StoryboardItem item : items) {
                String url = StringUtils.hasText(item.getVideoUrl())
                        ? item.getVideoUrl()
                        : item.getGeneratedVideoUrl();
                if (StringUtils.hasText(url)) {
                    sources.add(new VideoSource(url, item.getId()));
                }
            }
        }
        return sources;
    }

    /**
     * 按选配参数收集视频段。只取指定的 itemId，按分镜 sortOrder 排序，附带裁剪参数。
     */
    private List<ComposeSegment> collectSegments(Long episodeId, List<ComposeSegmentReqVO> segments) {
        // 构建 itemId -> 裁剪参数 的映射
        Map<Long, ComposeSegmentReqVO> segmentMap = new LinkedHashMap<>();
        for (ComposeSegmentReqVO seg : segments) {
            if (seg.getItemId() != null) {
                segmentMap.put(seg.getItemId(), seg);
            }
        }

        // 按场次/镜头排序收集所有 item
        List<StoryboardScene> scenes = new ArrayList<>(storyboardService.listScenesByEpisode(episodeId));
        scenes.sort(Comparator.comparing(s -> Optional.ofNullable(s.getSortOrder()).orElse(0)));

        List<ComposeSegment> result = new ArrayList<>();
        for (StoryboardScene scene : scenes) {
            List<StoryboardItem> items = new ArrayList<>(storyboardService.listItemsByScene(scene.getId()));
            items.sort(Comparator.comparing(i -> Optional.ofNullable(i.getSortOrder()).orElse(0)));
            for (StoryboardItem item : items) {
                if (!segmentMap.containsKey(item.getId())) {
                    continue;
                }
                String url = StringUtils.hasText(item.getVideoUrl())
                        ? item.getVideoUrl()
                        : item.getGeneratedVideoUrl();
                if (StringUtils.hasText(url)) {
                    ComposeSegmentReqVO seg = segmentMap.get(item.getId());
                    result.add(new ComposeSegment(url, item.getId(), seg.getStartSec(), seg.getEndSec(),
                            seg.getTransition(), seg.getTransitionDuration()));
                }
            }
        }
        return result;
    }

    /**
     * 合成（带选配参数）。下载视频后，如有裁剪参数先裁剪，再走两两合并/xfade 流程。
     */
    private void doComposeSegments(Long episodeId, String taskId, List<ComposeSegment> segments,
                                   String globalTransition, Double globalTransitionDuration) throws Exception {
        log.info("[VideoCompose] 开始合成（选配）episodeId={}, taskId={}, 段数={}", episodeId, taskId, segments.size());
        long startMs = System.currentTimeMillis();

        // 构建逐段转场数组：transitions[i] = 从视频 i 到视频 i+1 的转场
        // 优先用 segment[i+1].transition，为空则回退到 globalTransition
        int n = segments.size();
        List<String> transitions = new ArrayList<>();
        List<Double> transitionDurations = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            String t = segments.get(i + 1).transition;
            Double d = segments.get(i + 1).transitionDuration;
            if (t == null || t.isBlank()) {
                t = globalTransition != null ? globalTransition : "none";
            }
            if (d == null || d <= 0) {
                d = (globalTransitionDuration != null && globalTransitionDuration > 0) ? globalTransitionDuration : XFADE_DURATION;
            }
            transitions.add(t);
            transitionDurations.add(d);
        }

        Path workDir = Files.createTempDirectory("compose_ep_" + episodeId + "_");
        try {
            List<Path> localFiles = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                ComposeSegment seg = segments.get(i);
                Path downloaded = workDir.resolve(String.format("src_%04d.mp4", i));
                downloadToFile(seg.url, downloaded);

                Path sourceFile = downloaded;
                if (seg.needsTrim()) {
                    Path trimmed = workDir.resolve(String.format("v%04d.mp4", i));
                    boolean ok = runFfmpegTrim(downloaded, trimmed, seg.startSec, seg.endSec);
                    if (ok && Files.exists(trimmed) && Files.size(trimmed) > 0) {
                        try { Files.deleteIfExists(downloaded); } catch (Exception ignored) {}
                        sourceFile = trimmed;
                    } else {
                        log.warn("[VideoCompose] 裁剪失败，使用原始视频: 段 {}", i + 1);
                        try { Files.deleteIfExists(trimmed); } catch (Exception ignored) {}
                    }
                }

                // 尝试混入配音音频
                Path withAudio = mixVoiceoverForItem(sourceFile, seg.itemId, i, workDir);
                localFiles.add(withAudio != null ? withAudio : sourceFile);
            }

            Path listFile = workDir.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : localFiles) {
                String s = f.toAbsolutePath().toString().replace("'", "'\\''");
                sb.append("file '").append(s).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            Path output = workDir.resolve("output.mp4");
            boolean ok = false;
            // 判断是否有任何转场不是 none/jcut/lcut（这些是硬切+音频偏移，不是 xfade）
            boolean anyXfade = transitions.stream().anyMatch(t ->
                    !"none".equalsIgnoreCase(t) && !"jcut".equalsIgnoreCase(t) && !"lcut".equalsIgnoreCase(t));
            boolean anyAudioCut = transitions.stream().anyMatch(t ->
                    "jcut".equalsIgnoreCase(t) || "lcut".equalsIgnoreCase(t));
            if (anyXfade && localFiles.size() >= 2 && localFiles.size() <= 4) {
                ok = runFfmpegXfadeConcat(localFiles, output, transitions, transitionDurations);
                if (!ok) {
                    log.warn("[VideoCompose] xfade 过渡重编码失败，回退到普通 concat 重编码 episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegFilterConcat(localFiles, output, transitions, transitionDurations);
                }
            } else if (anyXfade && localFiles.size() > 4) {
                log.info("[VideoCompose] 视频段数={}，使用两两合并策略 episodeId={}", localFiles.size(), episodeId);
                ok = runFfmpegPairwiseMerge(localFiles, workDir, output, transitions, transitionDurations);
                if (!ok) {
                    log.warn("[VideoCompose] 两两合并失败，回退到两步法 episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegTwoStepConcat(localFiles, workDir, output);
                }
            } else if (anyAudioCut) {
                // J-Cut/L-Cut：视频硬切，音频偏移
                ok = runFfmpegFilterConcat(localFiles, output, transitions, transitionDurations);
                if (!ok) {
                    log.warn("[VideoCompose] J-Cut/L-Cut 合成失败，回退到普通 concat episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegFilterConcat(localFiles, output, null, null);
                }
            } else {
                ok = runFfmpegFilterConcat(localFiles, output, null, null);
                if (!ok) {
                    log.warn("[VideoCompose] concat 重编码失败，尝试 concat demuxer 流拷贝 episodeId={}", episodeId);
                    try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                    ok = runFfmpegConcatDemuxer(listFile, output);
                }
            }
            if (!ok) {
                log.warn("[VideoCompose] 重编码失败，尝试两步法归一化 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                ok = runFfmpegTwoStepConcat(localFiles, workDir, output);
            }
            if (!ok) {
                log.warn("[VideoCompose] 两步法也失败，尝试 concat demuxer 重编码 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                VideoStreamInfo target = probeVideoInfo(localFiles.get(0));
                ok = runFfmpegConcatDemuxerReencode(listFile, output, target);
            }
            if (!ok) {
                log.warn("[VideoCompose] 重编码也失败，最后尝试 concat demuxer 流拷贝 episodeId={}", episodeId);
                try { Files.deleteIfExists(output); } catch (Exception ignored) {}
                ok = runFfmpegConcatDemuxer(listFile, output);
            }
            if (!ok || !Files.exists(output) || Files.size(output) == 0) {
                throw new RuntimeException("ffmpeg 合成失败（选配模式）");
            }

            String storedUrl = mediaStorageService.storeFile(output, "videos/composed", "mp4");
            log.info("[VideoCompose] 已保存到存储: {}", storedUrl);

            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposedVideoUrl(storedUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            episodeMapper.updateById(update);

            taskStreamService.complete(taskId, "✓ 合成完成 · 视频地址：" + storedUrl);

            log.info("[VideoCompose] 完成（选配）episodeId={}, 耗时={}ms, 段数={}",
                    episodeId, System.currentTimeMillis() - startMs, segments.size());
        } finally {
            try {
                FileSystemUtils.deleteRecursively(workDir.toFile());
            } catch (Exception e) {
                log.warn("[VideoCompose] 临时目录清理失败 {}", workDir, e);
            }
        }
    }

    /**
     * 为镜头准备配音音频并混入视频。
     * <p>
     * 1. 查询该镜头所有成功的 AudioTask（按 seq 取最新）
     * 2. 下载音频文件，多句台词按 seq 拼接成单个音频
     * 3. 用 ffmpeg 将音频替换/叠加到视频
     *
     * @return 混入配音后的视频路径，无配音时返回 null
     */
    private Path mixVoiceoverForItem(Path video, Long itemId, int index, Path workDir) {
        if (itemId == null) return null;
        try {
            Path audioFile = prepareItemAudio(itemId, index, workDir);
            if (audioFile == null) return null;

            Path output = workDir.resolve(String.format("vo_%04d.mp4", index));
            double videoDur = probeDuration(video);
            double audioDur = probeDuration(audioFile);

            List<String> cmd = new ArrayList<>();
            cmd.add(getFfmpegExecutable());
            cmd.add("-y");
            cmd.add("-threads");
            cmd.add("2");
            cmd.add("-i");
            cmd.add(video.toString());
            cmd.add("-i");
            cmd.add(audioFile.toString());

            if (audioDur < videoDur) {
                // 音频比视频短 → 末尾补静音
                cmd.add("-filter_complex");
                cmd.add("[1:a]apad=whole_dur=" + String.format(Locale.ROOT, "%.3f", videoDur) + "[aout]");
                cmd.add("-map");
                cmd.add("0:v");
                cmd.add("-map");
                cmd.add("[aout]");
            } else {
                // 音频比视频长或相等 → 以视频时长为准
                cmd.add("-map");
                cmd.add("0:v");
                cmd.add("-map");
                cmd.add("1:a");
                cmd.add("-shortest");
            }

            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(output.toString());

            boolean ok = runFfmpeg(cmd, "mix-voiceover-" + index, 5);
            if (ok && Files.exists(output) && Files.size(output) > 0) {
                log.info("[VideoCompose] 镜头 {} 配音混入成功", itemId);
                // 清理原始文件释放空间
                try { Files.deleteIfExists(video); } catch (Exception ignored) {}
                try { Files.deleteIfExists(audioFile); } catch (Exception ignored) {}
                return output;
            }
            log.warn("[VideoCompose] 镜头 {} 配音混入失败，使用原视频", itemId);
            try { Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { Files.deleteIfExists(audioFile); } catch (Exception ignored) {}
            return null;
        } catch (Exception e) {
            log.warn("[VideoCompose] 镜头 {} 配音混入异常，使用原视频: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * 下载并拼接镜头的所有配音音频（按 seq 顺序）。
     * 只取每个 seq 最新且成功的任务。
     */
    private Path prepareItemAudio(Long itemId, int index, Path workDir) throws Exception {
        List<AudioTask> tasks = audioGenerationService.listByItemId(itemId);
        if (tasks.isEmpty()) return null;

        // 按 seq 分组，每组只取 id 最大的（最新的）且状态为成功的任务
        Map<Integer, AudioTask> latestBySeq = tasks.stream()
                .filter(t -> t.getStatus() != null && t.getStatus() == 2 && StringUtils.hasText(t.getAudioUrl()))
                .collect(Collectors.toMap(
                        AudioTask::getSeq,
                        t -> t,
                        (a, b) -> a.getId() > b.getId() ? a : b));

        if (latestBySeq.isEmpty()) return null;

        List<AudioTask> sorted = latestBySeq.values().stream()
                .sorted(Comparator.comparingInt(AudioTask::getSeq))
                .toList();

        // 单句台词直接下载
        if (sorted.size() == 1) {
            Path audioFile = workDir.resolve(String.format("aud_%04d.mp3", index));
            downloadToFile(sorted.get(0).getAudioUrl(), audioFile);
            return audioFile;
        }

        // 多句台词按 seq 拼接
        List<Path> audioParts = new ArrayList<>();
        for (AudioTask task : sorted) {
            Path part = workDir.resolve(String.format("aud_%04d_s%d.mp3", index, task.getSeq()));
            downloadToFile(task.getAudioUrl(), part);
            audioParts.add(part);
        }

        Path listFile = workDir.resolve(String.format("aud_%04d_list.txt", index));
        StringBuilder sb = new StringBuilder();
        for (Path f : audioParts) {
            String s = f.toAbsolutePath().toString().replace("'", "'\\''");
            sb.append("file '").append(s).append("'\n");
        }
        Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

        Path combined = workDir.resolve(String.format("aud_%04d_combined.mp3", index));
        List<String> cmd = List.of(
                getFfmpegExecutable(), "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                combined.toString()
        );
        boolean ok = runFfmpeg(cmd, "audio-concat-" + index, 5);
        if (!ok || !Files.exists(combined) || Files.size(combined) == 0) {
            // 回退到重编码
            List<String> reencodeCmd = List.of(
                    getFfmpegExecutable(), "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", listFile.toString(),
                    "-c:a", "libmp3lame",
                    "-b:a", "128k",
                    combined.toString()
            );
            ok = runFfmpeg(reencodeCmd, "audio-concat-reencode-" + index, 5);
        }
        if (!ok || !Files.exists(combined) || Files.size(combined) == 0) {
            log.warn("[VideoCompose] 镜头 {} 音频拼接失败", itemId);
            return null;
        }
        // 清理临时音频片段
        for (Path part : audioParts) {
            try { Files.deleteIfExists(part); } catch (Exception ignored) {}
        }
        try { Files.deleteIfExists(listFile); } catch (Exception ignored) {}
        return combined;
    }

    /**
     * 精确裁剪视频：-ss 在 -i 后（精确 seek），重编码保证帧精度。
     */
    private boolean runFfmpegTrim(Path input, Path output, Double startSec, Double endSec) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        cmd.add("-threads");
        cmd.add("2");
        cmd.add("-i");
        cmd.add(input.toString());
        if (startSec != null && startSec > 0) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.ROOT, "%.3f", startSec));
        }
        if (endSec != null && endSec > 0) {
            cmd.add("-to");
            cmd.add(String.format(Locale.ROOT, "%.3f", endSec));
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("18");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());

        log.info("[VideoCompose:trim] 裁剪: {} start={} end={}", input.getFileName(), startSec, endSec);
        return runFfmpeg(cmd, "trim", 10);
    }

    /**
     * 两两合并策略：每次只取2个视频做 xfade 合并，结果作为下一轮输入，类似归并排序。
     * 内存恒定为2个视频输入，适用于段数较多的场景。
     * 例：8段 → 4段 → 2段 → 1段，共3轮，每轮只处理2个输入。
     */
    private boolean runFfmpegPairwiseMerge(List<Path> files, Path workDir, Path output,
                                          List<String> transitions, List<Double> transitionDurations) throws Exception {
        Path mergeDir = workDir.resolve("pairwise");
        Files.createDirectories(mergeDir);

        List<Path> current = new ArrayList<>(files);
        // 追踪每个 current 文件对应的原始右边界索引（即它包含的最后一个原始视频的 index）
        List<Integer> rightBoundaries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            rightBoundaries.add(i);
        }
        int round = 0;

        while (current.size() > 1) {
            List<Path> next = new ArrayList<>();
            List<Integer> nextBoundaries = new ArrayList<>();
            log.info("[VideoCompose:pairwise] 第 {} 轮，待合并 {} 段", round + 1, current.size());

            for (int i = 0; i < current.size(); i += 2) {
                if (i + 1 >= current.size()) {
                    // 奇数个，最后一个直接保留到下一轮
                    next.add(current.get(i));
                    nextBoundaries.add(rightBoundaries.get(i));
                    continue;
                }

                Path a = current.get(i);
                Path b = current.get(i + 1);
                Path merged = mergeDir.resolve(String.format("r%02d_m%03d.mp4", round, i / 2));

                // a 的右边界 = rightBoundaries.get(i)，这就是 a→b 之间转场的索引
                int boundaryIdx = rightBoundaries.get(i);
                String t = boundaryIdx < transitions.size() ? transitions.get(boundaryIdx) : "fade";
                double d = boundaryIdx < transitionDurations.size() ? transitionDurations.get(boundaryIdx) : XFADE_DURATION;

                log.info("[VideoCompose:pairwise] 合并第 {} 轮 {}/{}: {} + {} (transition={})", round + 1, i / 2 + 1,
                        (current.size() + 1) / 2, a.getFileName(), b.getFileName(), t);

                boolean ok = runFfmpegXfadePair(a, b, merged, t, d);
                if (!ok || !Files.exists(merged) || Files.size(merged) == 0) {
                    log.warn("[VideoCompose:pairwise] xfade 合并失败，回退到普通 concat: {} + {}", a, b);
                    try { Files.deleteIfExists(merged); } catch (Exception ignored) {}
                    ok = runFfmpegFilterConcat(List.of(a, b), merged);
                }
                if (!ok || !Files.exists(merged) || Files.size(merged) == 0) {
                    log.error("[VideoCompose:pairwise] 合并失败: {} + {}", a, b);
                    return false;
                }

                // 合并成功后删除输入文件释放磁盘空间
                if (i > 0 || round > 0) {
                    try { Files.deleteIfExists(a); } catch (Exception ignored) {}
                    try { Files.deleteIfExists(b); } catch (Exception ignored) {}
                }

                next.add(merged);
                // 合并后的右边界 = b 的右边界
                nextBoundaries.add(rightBoundaries.get(i + 1));
            }

            current = next;
            rightBoundaries = nextBoundaries;
            round++;
        }

        // 最终结果复制到 output
        Files.copy(current.get(0), output, StandardCopyOption.REPLACE_EXISTING);
        log.info("[VideoCompose:pairwise] 完成，共 {} 轮", round);
        return true;
    }

    /**
     * 对2个视频做 xfade 交叉淡入淡出合并。
     */
    private boolean runFfmpegXfadePair(Path a, Path b, Path output,
                                      String transition, Double transitionDuration) throws Exception {
        String xfadeTransition = (transition != null && !transition.isBlank()) ? transition : "fade";
        double xfadeDur = (transitionDuration != null && transitionDuration > 0) ? transitionDuration : XFADE_DURATION;
        VideoStreamInfo target = probeVideoInfo(a);
        int width = target.width() > 0 ? target.width() : 1280;
        int height = target.height() > 0 ? target.height() : 720;
        String fps = formatFrameRate(target.fps());

        double durA = probeDuration(a);
        double durB = probeDuration(b);
        double offset = durA - xfadeDur;

        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        cmd.add("-threads");
        cmd.add("2");
        cmd.add("-i");
        cmd.add(a.toString());
        cmd.add("-i");
        cmd.add(b.toString());

        StringBuilder filter = new StringBuilder();
        // 归一化视频
        filter.append("[0:v]fps=").append(fps)
                .append(",scale=").append(width).append(":").append(height)
                .append(":force_original_aspect_ratio=decrease,pad=").append(width).append(":").append(height)
                .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v0];");
        filter.append("[1:v]fps=").append(fps)
                .append(",scale=").append(width).append(":").append(height)
                .append(":force_original_aspect_ratio=decrease,pad=").append(width).append(":").append(height)
                .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v1];");

        // 归一化音频
        if (hasAudioStream(a)) {
            filter.append("[0:a]aresample=48000,aformat=channel_layouts=stereo[a0];");
        } else {
            filter.append("anullsrc=r=48000:cl=stereo:d=").append(String.format(Locale.ROOT, "%.3f", durA)).append("[a0];");
        }
        if (hasAudioStream(b)) {
            filter.append("[1:a]aresample=48000,aformat=channel_layouts=stereo[a1];");
        } else {
            filter.append("anullsrc=r=48000:cl=stereo:d=").append(String.format(Locale.ROOT, "%.3f", durB)).append("[a1];");
        }

        // xfade 过渡
        boolean useXfade = offset > 0 && !"none".equalsIgnoreCase(xfadeTransition);
        if (useXfade) {
            filter.append("[v0][v1]xfade=transition=").append(xfadeTransition).append(":duration=").append(xfadeDur)
                    .append(":offset=").append(String.format(Locale.ROOT, "%.3f", offset))
                    .append("[outv];");
            filter.append("[a0][a1]acrossfade=d=").append(xfadeDur).append("[outa]");
        } else {
            // transition=none 或第一个视频太短，直接 concat 不做 xfade
            filter.append("[v0][v1]concat=n=2:v=1:a=0[outv];");
            filter.append("[a0][a1]concat=n=2:v=0:a=1[outa]");
        }

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[outv]");
        cmd.add("-map");
        cmd.add("[outa]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("18");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());

        return runFfmpeg(cmd, "pairwise-xfade", 10);
    }

    /**
     * 两步法合成：第一步逐个重编码统一格式（分辨率/fps/编码一致），第二步 concat demuxer 流拷贝。
     * 每次只处理一个视频，内存占用极低，适用于段数较多的场景。
     */
    private boolean runFfmpegTwoStepConcat(List<Path> files, Path workDir, Path output) throws Exception {
        VideoStreamInfo target = probeVideoInfo(files.get(0));
        int width = target.width() > 0 ? target.width() : 1280;
        int height = target.height() > 0 ? target.height() : 720;
        String fps = formatFrameRate(target.fps());

        String vf = String.format(Locale.ROOT,
                "fps=%s,scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p",
                fps, width, height, width, height);

        Path normalizedDir = workDir.resolve("normalized");
        Files.createDirectories(normalizedDir);

        List<Path> normalizedFiles = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path normalized = normalizedDir.resolve(String.format("n%04d.mp4", i));
            boolean hasAudio = hasAudioStream(files.get(i));

            List<String> cmd = new ArrayList<>();
            cmd.add(getFfmpegExecutable());
            cmd.add("-y");
            cmd.add("-threads");
            cmd.add("2");
            cmd.add("-i");
            cmd.add(files.get(i).toString());
            if (!hasAudio) {
                cmd.add("-f");
                cmd.add("lavfi");
                cmd.add("-i");
                cmd.add("anullsrc=r=48000:cl=stereo");
            }
            cmd.add("-vf");
            cmd.add(vf);
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-crf");
            cmd.add("18");
            cmd.add("-r");
            cmd.add(fps);
            cmd.add("-video_track_timescale");
            cmd.add("15360");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add("-ar");
            cmd.add("48000");
            cmd.add("-ac");
            cmd.add("2");
            if (!hasAudio) {
                cmd.add("-shortest");
            }
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(normalized.toString());

            log.info("[VideoCompose:two-step] 重编码第 {}/{} 段: {}", i + 1, files.size(), files.get(i).getFileName());
            boolean ok = runFfmpeg(cmd, "two-step-normalize-" + i, 10);
            if (!ok || !Files.exists(normalized) || Files.size(normalized) == 0) {
                log.error("[VideoCompose:two-step] 第 {} 段重编码失败", i + 1);
                return false;
            }
            normalizedFiles.add(normalized);
        }

        // 第二步：concat demuxer 流拷贝（所有视频已统一格式，可以直接 copy）
        Path normalizedListFile = workDir.resolve("normalized_list.txt");
        StringBuilder sb = new StringBuilder();
        for (Path f : normalizedFiles) {
            String s = f.toAbsolutePath().toString().replace("'", "'\\''");
            sb.append("file '").append(s).append("'\n");
        }
        Files.writeString(normalizedListFile, sb.toString(), StandardCharsets.UTF_8);

        List<String> concatCmd = List.of(
                getFfmpegExecutable(), "-y",
                "-f", "concat", "-safe", "0",
                "-i", normalizedListFile.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                output.toString()
        );
        log.info("[VideoCompose:two-step] concat 流拷贝: {} 段", normalizedFiles.size());
        return runFfmpeg(concatCmd, "two-step-concat", 15);
    }

    private boolean runFfmpegConcatDemuxer(Path listFile, Path output) throws Exception {
        List<String> cmd = List.of(
                getFfmpegExecutable(), "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                output.toString()
        );
        return runFfmpeg(cmd, "concat-demuxer", 15);
    }

    /**
     * concat demuxer + 重编码：单输入逐段读取，避免多输入同时解码导致 OOM。
     * 适用于段数较多的场景（>4 段）。
     * 需要所有视频编码格式一致，否则 concat demuxer 可能出现异常。
     */
    private boolean runFfmpegConcatDemuxerReencode(Path listFile, Path output, VideoStreamInfo target) throws Exception {
        int width = target.width() > 0 ? target.width() : 1280;
        int height = target.height() > 0 ? target.height() : 720;
        String fps = formatFrameRate(target.fps());

        String vf = String.format(Locale.ROOT,
                "fps=%s,scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p",
                fps, width, height, width, height);

        List<String> cmd = new ArrayList<>(List.of(
                getFfmpegExecutable(), "-y",
                "-threads", "2",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-vf", vf,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "18",
                "-c:a", "aac",
                "-b:a", "128k",
                "-ar", "48000",
                "-ac", "2",
                "-movflags", "+faststart",
                output.toString()
        ));
        return runFfmpeg(cmd, "concat-demuxer-reencode", 30);
    }

    private boolean runFfmpegFilterConcat(List<Path> files, Path output,
                                          List<String> transitions, List<Double> transitionDurations) throws Exception {
        VideoStreamInfo target = probeVideoInfo(files.get(0));
        int width = target.width() > 0 ? target.width() : 1280;
        int height = target.height() > 0 ? target.height() : 720;
        String fps = formatFrameRate(target.fps());

        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        cmd.add("-threads");
        cmd.add("2");
        for (Path f : files) {
            cmd.add("-i");
            cmd.add(f.toString());
        }

        // 计算每段视频时长，用于 J-Cut/L-Cut 音频偏移
        List<Double> durations = new ArrayList<>();
        for (Path f : files) {
            durations.add(probeDuration(f));
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            double dur = durations.get(i);
            filter.append("[").append(i).append(":v]")
                    .append("fps=").append(fps).append(",")
                    .append("scale=").append(width).append(":").append(height)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(width).append(":").append(height)
                    .append(":(ow-iw)/2:(oh-ih)/2,")
                    .append("setsar=1,format=yuv420p[v").append(i).append("];");
            if (hasAudioStream(files.get(i))) {
                filter.append("[").append(i).append(":a]")
                        .append("aresample=48000,aformat=channel_layouts=stereo");
                // J-Cut: 下一段音频提前出现（当前段音频提前结束，为下一段音频留出空间）
                // L-Cut: 当前段音频延续到下一段（当前段音频延长，下一段音频延迟开始）
                // 这里在 concat 前对音频做 pad/trim 处理
                filter.append("[a").append(i).append("];");
            } else {
                double d = dur > 0 ? dur : 10.0;
                filter.append("anullsrc=r=48000:cl=stereo:d=").append(String.format(Locale.ROOT, "%.3f", d))
                        .append("[a").append(i).append("];");
            }
        }

        // 构建 J-Cut/L-Cut 音频偏移
        // transitions[i] 是从视频 i 到视频 i+1 的转场
        // J-Cut: 视频 i+1 的音频提前 transitionDuration 秒出现 → 视频 i 的音频在末尾 trim 掉 transitionDuration
        // L-Cut: 视频 i 的音频延续 transitionDuration 秒 → 视频 i+1 的音频延迟 transitionDuration 秒开始
        for (int i = 0; i < files.size(); i++) {
            double audioPadSec = 0;
            double audioTrimSec = 0;
            double audioDelaySec = 0;

            // 检查当前段到下一段的转场（L-Cut: 当前段音频延长）
            if (transitions != null && i < transitions.size()) {
                String t = transitions.get(i);
                double td = transitionDurations != null && i < transitionDurations.size()
                        ? transitionDurations.get(i) : 0.5;
                if ("lcut".equalsIgnoreCase(t)) {
                    // L-Cut: 当前段音频延长 td 秒到下一段画面
                    audioPadSec = td;
                }
            }
            // 检查上一段到当前段的转场（J-Cut: 当前段音频提前 / L-Cut: 当前段音频延迟）
            if (transitions != null && i > 0) {
                String prevT = transitions.get(i - 1);
                double prevTd = transitionDurations != null && i - 1 < transitionDurations.size()
                        ? transitionDurations.get(i - 1) : 0.5;
                if ("jcut".equalsIgnoreCase(prevT)) {
                    // J-Cut: 当前段音频提前 prevTd 秒 → 当前段音频延迟负值（提前）
                    // 实现：当前段音频在视频切换前 prevTd 秒就开始播放
                    // 简化处理：当前段音频 trim 掉末尾 prevTd 秒（让下一段音频提前进入）
                    audioTrimSec = prevTd;
                } else if ("lcut".equalsIgnoreCase(prevT)) {
                    // L-Cut: 上一段音频延续到当前段 → 当前段音频延迟 prevTd 秒开始
                    audioDelaySec = prevTd;
                }
            }

            // 应用音频处理
            if (audioPadSec > 0 || audioTrimSec > 0 || audioDelaySec > 0) {
                filter.append("[a").append(i).append("]");
                if (audioDelaySec > 0) {
                    // L-Cut: 延迟音频开始
                    long delayMs = (long) (audioDelaySec * 1000);
                    filter.append("adelay=").append(delayMs).append(":").append(delayMs);
                }
                if (audioTrimSec > 0) {
                    // J-Cut: trim 音频末尾
                    double trimEnd = durations.get(i) - audioTrimSec;
                    if (trimEnd > 0) {
                        if (audioDelaySec > 0) {
                            filter.append(",");
                        }
                        filter.append("atrim=0:").append(String.format(Locale.ROOT, "%.3f", trimEnd))
                                .append(",asetpts=PTS-STARTPTS");
                    }
                }
                if (audioPadSec > 0) {
                    // L-Cut: 音频末尾补静音
                    if (audioDelaySec > 0 || audioTrimSec > 0) {
                        filter.append(",");
                    }
                    filter.append("apad=pad_dur=").append(String.format(Locale.ROOT, "%.3f", audioPadSec));
                }
                filter.append("[ap").append(i).append("];");
            } else {
                // 无偏移，直接重命名标签
                filter.append("[a").append(i).append("]copy[ap").append(i).append("];");
            }
        }

        for (int i = 0; i < files.size(); i++) {
            filter.append("[v").append(i).append("][ap").append(i).append("]");
        }
        filter.append("concat=n=").append(files.size()).append(":v=1:a=1[outv][outa]");

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[outv]");
        cmd.add("-map");
        cmd.add("[outa]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("18");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-shortest");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());
        return runFfmpeg(cmd, "filter-complex", 30);
    }

    private boolean runFfmpegFilterConcat(List<Path> files, Path output) throws Exception {
        return runFfmpegFilterConcat(files, output, null, null);
    }

    private static final double XFADE_DURATION = 0.5;

    private boolean runFfmpegXfadeConcat(List<Path> files, Path output,
                                       List<String> transitions, List<Double> transitionDurations) throws Exception {
        if (files.size() < 2) {
            return runFfmpegFilterConcat(files, output);
        }

        VideoStreamInfo target = probeVideoInfo(files.get(0));
        int width = target.width() > 0 ? target.width() : 1280;
        int height = target.height() > 0 ? target.height() : 720;
        String fps = formatFrameRate(target.fps());

        List<Double> durations = new ArrayList<>();
        for (Path f : files) {
            durations.add(probeDuration(f));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        cmd.add("-threads");
        cmd.add("2");
        for (Path f : files) {
            cmd.add("-i");
            cmd.add(f.toString());
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            filter.append("[").append(i).append(":v]")
                    .append("fps=").append(fps).append(",")
                    .append("scale=").append(width).append(":").append(height)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(width).append(":").append(height)
                    .append(":(ow-iw)/2:(oh-ih)/2,")
                    .append("setsar=1,format=yuv420p[v").append(i).append("];");
            if (hasAudioStream(files.get(i))) {
                filter.append("[").append(i).append(":a]")
                        .append("aresample=48000,aformat=channel_layouts=stereo[a").append(i).append("];");
            } else {
                filter.append("anullsrc=r=48000:cl=stereo:d=").append(String.format(Locale.ROOT, "%.3f", durations.get(i))).append("[a").append(i).append("];");
            }
        }

        // 逐对构建 xfade：transitions[i] = 从视频 i 到视频 i+1 的转场
        double offset = durations.get(0) - transitionDurations.get(0);
        filter.append("[v0][v1]xfade=transition=").append(transitions.get(0))
                .append(":duration=").append(transitionDurations.get(0))
                .append(":offset=").append(String.format(Locale.ROOT, "%.3f", offset))
                .append("[xv0];");
        filter.append("[a0][a1]acrossfade=d=").append(transitionDurations.get(0)).append("[xa0];");

        for (int i = 1; i < files.size() - 1; i++) {
            offset += durations.get(i) - transitionDurations.get(i);
            filter.append("[xv").append(i - 1).append("][v").append(i + 1).append("]xfade=transition=").append(transitions.get(i))
                    .append(":duration=").append(transitionDurations.get(i))
                    .append(":offset=").append(String.format(Locale.ROOT, "%.3f", offset))
                    .append("[xv").append(i).append("];");
            filter.append("[xa").append(i - 1).append("][a").append(i + 1).append("]acrossfade=d=").append(transitionDurations.get(i))
                    .append("[xa").append(i).append("];");
        }

        int lastIdx = files.size() - 2;
        filter.append("[xv").append(lastIdx).append("]format=yuv420p[outv];");
        filter.append("[xa").append(lastIdx).append("]aformat=sample_rates=48000:channel_layouts=stereo[outa]");

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[outv]");
        cmd.add("-map");
        cmd.add("[outa]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("18");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());
        return runFfmpeg(cmd, "xfade-concat", 60);
    }

    private double probeDuration(Path file) {
        List<String> cmd = List.of(
                getFfprobeExecutable(), "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.toString()
        );
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            return Double.parseDouble(output);
        } catch (Exception e) {
            log.warn("[VideoCompose] ffprobe 读取时长失败: {}", file, e);
            return 0;
        }
    }

    private record VideoStreamInfo(int width, int height, double fps) {
    }

    private VideoStreamInfo probeVideoInfo(Path file) {
        List<String> cmd = List.of(
                getFfprobeExecutable(), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate",
                "-of", "csv=p=0",
                file.toString()
        );
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("[VideoCompose] ffprobe 读取视频参数超时: {}", file);
                return new VideoStreamInfo(0, 0, 0);
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (process.exitValue() != 0 || !StringUtils.hasText(output)) {
                return new VideoStreamInfo(0, 0, 0);
            }
            String[] parts = output.split(",");
            int width = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
            int height = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            double fps = parts.length > 2 ? parseFrameRate(parts[2].trim()) : 0;
            return new VideoStreamInfo(width, height, fps);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VideoStreamInfo(0, 0, 0);
        } catch (Exception e) {
            log.warn("[VideoCompose] ffprobe 读取视频参数失败，使用默认值: {}", file, e);
            return new VideoStreamInfo(0, 0, 0);
        }
    }

    private double parseFrameRate(String avgFrameRate) {
        try {
            String[] fraction = avgFrameRate.split("/");
            if (fraction.length == 2) {
                double numerator = Double.parseDouble(fraction[0]);
                double denominator = Double.parseDouble(fraction[1]);
                return denominator > 0 ? numerator / denominator : 0;
            }
            return Double.parseDouble(avgFrameRate);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatFrameRate(double fps) {
        if (fps <= 0) {
            return "30";
        }
        if (Math.abs(fps - Math.rint(fps)) < 0.01) {
            return String.valueOf((int) Math.rint(fps));
        }
        return String.format(Locale.ROOT, "%.2f", fps);
    }

    private boolean runFfmpeg(List<String> cmd, String tag, int timeoutMinutes) throws Exception {
        log.info("[VideoCompose:{}] 执行: {}", tag, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw wrapExecutableStartException(e, "ffmpeg", "video.compose.ffmpeg-path", getFfmpegExecutable());
        }
        StringBuffer outputTail = new StringBuffer();
        Thread outputReader = new Thread(() -> drainProcessOutput(p.getInputStream(), tag, outputTail),
                "ffmpeg-" + tag + "-output");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean done;
        try {
            done = p.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!done) {
            p.destroy();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            log.error("[VideoCompose:{}] 超时，已强制终止。最近输出: {}", tag, summarizeOutputTail(outputTail));
            return false;
        }

        outputReader.join(TimeUnit.SECONDS.toMillis(5));
        int exit = p.exitValue();
        if (exit != 0) {
            log.error("[VideoCompose:{}] 退出码非 0: {}。最近输出: {}", tag, exit, summarizeOutputTail(outputTail));
            return false;
        }
        return true;
    }

    private void downloadToFile(String url, Path dest) throws IOException {
        if (url.startsWith(LOCAL_MEDIA_PUBLIC_PREFIX)) {
            Path local = resolveManagedMediaPath(url);
            if (Files.exists(local)) {
                Files.copy(local, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (!isAbsoluteHttpUrl(url)) {
                throw new IOException("本地媒体文件不存在: " + local);
            }
            log.warn("[VideoCompose] /media/ 路径文件不存在，回退 HTTP: {}", url);
        }

        URI current = URI.create(url);
        int redirects = 0;
        while (true) {
            validateRemoteUri(current);
            HttpURLConnection conn = openConnection(current);
            try {
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    if (redirects >= maxRedirects) {
                        throw new IOException("下载重定向次数过多: " + url);
                    }
                    String location = conn.getHeaderField("Location");
                    if (!StringUtils.hasText(location)) {
                        throw new IOException("下载重定向缺少 Location: " + url);
                    }
                    current = current.resolve(location);
                    redirects++;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("下载失败 HTTP " + code + ": " + current);
                }
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    private void markFailed(Long episodeId, String msg) {
        try {
            String trimmed = msg == null ? "未知错误" : (msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposeStatus(STATUS_FAILED);
            update.setComposeErrorMsg(trimmed);
            update.setComposedVideoUrl(null);
            update.setComposedAt(null);
            episodeMapper.updateById(update);
        } catch (Exception e) {
            log.error("[VideoCompose] 更新失败状态异常", e);
        }
    }

    private boolean hasAudioStream(Path file) {
        List<String> cmd = List.of(
            getFfprobeExecutable(), "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index",
                "-of", "csv=p=0",
                file.toString()
        );
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                log.warn("[VideoCompose] ffprobe 检测音轨超时: {}", file);
                return false;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) {
                log.warn("[VideoCompose] ffprobe 检测音轨失败，按无音轨处理: {}", file);
                return false;
            }
            return StringUtils.hasText(output.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VideoCompose] ffprobe 检测音轨被中断: {}", file, e);
            return false;
        } catch (IOException e) {
            IOException wrapped = wrapExecutableStartException(
                    e,
                    "ffprobe",
                    "video.compose.ffprobe-path",
                    getFfprobeExecutable()
            );
            log.warn("[VideoCompose] ffprobe 不可用或检测失败，按无音轨处理: {}", file, wrapped);
            return false;
        }
    }

    private String getFfmpegExecutable() {
        return StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
    }

    private String getFfprobeExecutable() {
        return StringUtils.hasText(ffprobePath) ? ffprobePath.trim() : "ffprobe";
    }

    private IOException wrapExecutableStartException(IOException exception,
                                                     String executableName,
                                                     String propertyName,
                                                     String configuredValue) {
        if (!isExecutableMissing(exception)) {
            return exception;
        }
        return new IOException(buildMissingExecutableMessage(executableName, propertyName, configuredValue), exception);
    }

    private boolean isExecutableMissing(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("createprocess error=2")
                        || normalized.contains("系统找不到指定的文件")
                        || normalized.contains("no such file or directory")
                        || normalized.contains("cannot run program")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildMissingExecutableMessage(String executableName,
                                                String propertyName,
                                                String configuredValue) {
        return "未找到 " + executableName + " 可执行文件，请先安装 " + executableName
                + "，或在配置中设置 " + propertyName + "。当前值：" + configuredValue;
    }

    private void drainProcessOutput(InputStream inputStream, String tag, StringBuffer outputTail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutputTail(outputTail, line);
                if (log.isDebugEnabled()) {
                    log.debug("[ffmpeg:{}] {}", tag, line);
                }
            }
        } catch (IOException e) {
            log.warn("[VideoCompose:{}] 读取 ffmpeg 输出失败", tag, e);
        }
    }

    private void appendOutputTail(StringBuffer outputTail, String line) {
        synchronized (outputTail) {
            if (outputTail.length() > 0) {
                outputTail.append(System.lineSeparator());
            }
            outputTail.append(line);
            int maxLength = 4000;
            if (outputTail.length() > maxLength) {
                outputTail.delete(0, outputTail.length() - maxLength);
            }
        }
    }

    private String summarizeOutputTail(StringBuffer outputTail) {
        synchronized (outputTail) {
            return outputTail.isEmpty() ? "<no output>" : outputTail.toString();
        }
    }

    private Path resolveManagedMediaPath(String url) throws IOException {
        String rel = url.substring(LOCAL_MEDIA_PUBLIC_PREFIX.length());
        Path relativePath = Paths.get(rel).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("非法媒体相对路径: " + rel);
        }

        Path basePath = resolveLocalMediaBasePath();
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IOException("媒体路径越界: " + rel);
        }
        return resolved;
    }

    private Path resolveLocalMediaBasePath() {
        String basePath = mediaLocalPath;
        try {
            StorageConfig config = storageConfigService.getDefaultConfig();
            if (config != null
                    && "local".equalsIgnoreCase(config.getType())
                    && StringUtils.hasText(config.getBasePath())) {
                basePath = config.getBasePath();
            }
        } catch (Exception e) {
            log.debug("[VideoCompose] 读取默认存储配置失败，回退到 application 配置路径: {}", basePath, e);
        }
        return Paths.get(basePath).toAbsolutePath().normalize();
    }

    private HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private void validateRemoteUri(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("仅允许下载 http/https 资源: " + uri);
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IOException("下载地址缺少 host: " + uri);
        }

        String normalizedHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        Set<String> allowedHosts = parseAllowedHosts();
        if (!allowedHosts.isEmpty()) {
            if (!matchesAllowedHost(normalizedHost, allowedHosts)) {
                throw new IOException("下载地址 host 不在白名单: " + normalizedHost);
            }
            return;
        }

        InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("拒绝访问内网或本地地址: " + normalizedHost);
            }
        }
    }

    private Set<String> parseAllowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        if (!StringUtils.hasText(allowedHostsConfig)) {
            return hosts;
        }
        for (String token : allowedHostsConfig.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }

    private boolean matchesAllowedHost(String host, Set<String> allowedHosts) {
        for (String pattern : allowedHosts) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (host.endsWith(suffix)) {
                    return true;
                }
                continue;
            }
            if (host.equals(pattern) || host.endsWith("." + pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private boolean isAbsoluteHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
