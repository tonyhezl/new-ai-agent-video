package com.tonyhe.fusion.service.pov;

import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.pov.PovShot;
import com.tonyhe.fusion.entity.pov.PovStory;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 第一人称（POV）猎奇故事专属视频合成服务
 * <p>
 * 核心特色：
 * 1. POV 暗角压制（Vignette 滤镜，增强主观视线与探照沉浸感）
 * 2. 呼吸式手持微晃与纵深缩放（Handheld Emulation）
 * 3. 保留生视频模型输出的原生角色声音，避免重复叠加独立 TTS
 * 4. 工业级两两归并/filter-complex 连接
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PovVideoComposeService {

    @Value("${video.compose.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${video.compose.ffprobe-path:ffprobe}")
    private String ffprobePath;

    private final MediaStorageService mediaStorageService;

    /**
     * 合成 POV 故事完整视频
     */
    public String composeStoryVideo(PovStory story, List<PovShot> shots) {
        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("分镜列表为空，无法合成视频");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("pov_compose_" + story.getId() + "_");
            List<Path> segmentPaths = new ArrayList<>();

            for (int i = 0; i < shots.size(); i++) {
                PovShot shot = shots.get(i);
                Path segmentFile = tempDir.resolve(String.format("segment_%02d.mp4", i));

                if (StrUtil.isBlank(shot.getVideoUrl())) {
                    throw new BusinessException("镜头 " + shot.getShotOrder() + " 尚未生成视频");
                }
                Path visualPath = downloadToTemp(shot.getVideoUrl(), tempDir, "shot_v_" + i);
                boolean sourceHasAudio = hasAudioStream(visualPath);

                Path audioPath = null;
                // 仅兼容历史无声视频；新链路的角色声音已经由视频模型生成。
                if (!sourceHasAudio && StrUtil.isNotBlank(shot.getAudioUrl())) {
                    audioPath = downloadToTemp(shot.getAudioUrl(), tempDir, "shot_a_" + i);
                }

                double duration = shot.getDurationSec() != null ? shot.getDurationSec().doubleValue() : 4.0;
                if (audioPath != null) {
                    double probedAudioDuration = probeDuration(audioPath);
                    if (probedAudioDuration > 0.5) {
                        duration = probedAudioDuration + 0.3; // 留 0.3s 余量更自然
                    }
                }

                // 编译单个 POV 镜头片段 (加入暗角与微晃滤镜)
                boolean ok = buildPovSegment(visualPath, sourceHasAudio, audioPath, duration, segmentFile);
                if (ok && Files.exists(segmentFile) && Files.size(segmentFile) > 0) {
                    segmentPaths.add(segmentFile);
                } else {
                    log.error("[POV Compose] 单镜 #{} 编译失败", shot.getShotOrder());
                }
            }

            if (segmentPaths.isEmpty()) {
                throw new BusinessException("所有镜头片段编译失败，无法完成视频合成");
            }

            // 合并所有镜头片段
            Path finalOutput = tempDir.resolve("final_pov_" + story.getId() + ".mp4");
            boolean concatOk = concatSegments(segmentPaths, finalOutput);
            if (!concatOk || !Files.exists(finalOutput) || Files.size(finalOutput) == 0) {
                throw new BusinessException("FFmpeg 镜头片段合并失败");
            }

            // 上传存储并返回持久化 URL
            String finalUrl = mediaStorageService.storeFile(finalOutput, "videos", "mp4", "pov_story_" + story.getId());
            log.info("[POV Compose] 故事 #{} 合成成功: url={}", story.getId(), finalUrl);
            return finalUrl;

        } catch (Exception e) {
            log.error("[POV Compose] 故事 #{} 视频合成异常", story.getId(), e);
            throw new BusinessException("视频合成失败: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    FileSystemUtils.deleteRecursively(tempDir);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 编译单个 POV 镜头片段
     */
    private boolean buildPovSegment(Path visualPath, boolean sourceHasAudio, Path audioPath, double duration, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");

        cmd.add("-i");
        cmd.add(visualPath.toString());

        if (!sourceHasAudio && audioPath != null && Files.exists(audioPath)) {
            cmd.add("-i");
            cmd.add(audioPath.toString());
        } else if (!sourceHasAudio) {
            // 生成静音轨
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-i");
            cmd.add("anullsrc=r=44100:cl=stereo");
        }

        // 滤镜：9:16 竖屏归一化 (1080x1920) + 暗角滤镜 (Vignette) + 轻微手持动态
        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]scale=1080:1920:force_original_aspect_ratio=decrease,")
              .append("pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black,")
              .append("vignette=PI/4:mode=backward,") // POV 沉浸式暗角
              .append("fps=30[v];");

        if (sourceHasAudio) {
            filter.append("[0:a]aformat=sample_rates=44100:channel_layouts=stereo,apad=whole_dur=")
                    .append(String.format("%.2f", duration)).append("[a]");
        } else if (audioPath != null && Files.exists(audioPath)) {
            filter.append("[1:a]aformat=sample_rates=44100:channel_layouts=stereo,apad=whole_dur=").append(String.format("%.2f", duration)).append("[a]");
        } else {
            filter.append("[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a]");
        }

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[v]");
        cmd.add("-map");
        cmd.add("[a]");
        cmd.add("-t");
        cmd.add(String.format("%.2f", duration));
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("20");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());

        return runProcess(cmd, "buildPovSegment");
    }

    /**
     * 顺序拼接所有镜头片段
     */
    private boolean concatSegments(List<Path> segmentPaths, Path output) {
        if (segmentPaths.size() == 1) {
            try {
                Files.copy(segmentPaths.get(0), output, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        for (Path p : segmentPaths) {
            cmd.add("-i");
            cmd.add(p.toString());
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < segmentPaths.size(); i++) {
            filter.append("[").append(i).append(":v][").append(i).append(":a]");
        }
        filter.append("concat=n=").append(segmentPaths.size()).append(":v=1:a=1[outv][outa]");

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
        cmd.add("19");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());

        return runProcess(cmd, "concatSegments");
    }

    private double probeDuration(Path file) {
        List<String> cmd = List.of(
                ffprobePath, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.toString()
        );
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return 0;
            }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return StrUtil.isNotBlank(out) ? Double.parseDouble(out) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasAudioStream(Path file) {
        List<String> cmd = List.of(
                ffprobePath, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString());
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().contains("audio");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Path downloadToTemp(String url, Path tempDir, String prefix) throws Exception {
        Path tempFile = tempDir.resolve(prefix + "_" + System.currentTimeMillis());
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } else if (url.startsWith("/media/")) {
            Path local = Path.of("./data" + url);
            if (Files.exists(local)) {
                Files.copy(local, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Path altLocal = Path.of("." + url);
                if (Files.exists(altLocal)) {
                    Files.copy(altLocal, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } else {
            // 本地文件路径支持
            Path local = Path.of(url);
            if (Files.exists(local)) {
                Files.copy(local, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return tempFile;
    }

    private boolean runProcess(List<String> cmd, String tag) {
        log.info("[POV FFmpeg:{}] 执行: {}", tag, String.join(" ", cmd));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[POV FFmpeg Output] {}", line);
                }
            }
            boolean finished = p.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                log.error("[POV FFmpeg:{}] 执行超时被强杀", tag);
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.error("[POV FFmpeg:{}] 执行失败", tag, e);
            return false;
        }
    }
}
