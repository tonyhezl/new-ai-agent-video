package com.tonyhe.fusion.service.storage.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tonyhe.fusion.entity.storage.StorageConfig;
import com.tonyhe.fusion.service.storage.StorageStrategy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件存储策略
 * <p>
 * 将远程文件下载到本地磁盘，通过 Spring MVC 静态资源映射提供 HTTP 访问。
 */
@Component
@Slf4j
public class LocalStorageStrategy implements StorageStrategy {

    private static final String DEFAULT_BASE_PATH = "./data/media";
    private static final String URL_PREFIX = "/media";

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public String store(String remoteUrl, String subDir, StorageConfig config) {
        return store(remoteUrl, subDir, config, null);
    }

    @Override
    public String store(String remoteUrl, String subDir, StorageConfig config, String customFilename) {
        String basePath = resolveBasePath(config);
        String safeSubDir = validateSubDir(subDir);

        Request request = new Request.Builder().url(remoteUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载文件失败: HTTP " + response.code() + " url=" + remoteUrl);
            }

            String extension = guessExtension(remoteUrl, response.header("Content-Type"));
            String filename = buildFilename(customFilename, extension);

            Path dir = Paths.get(basePath, safeSubDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);

            try (InputStream is = response.body().byteStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }

            long fileSize = Files.size(target);
            log.info("[LocalStorage] 文件已保存: {} ({} bytes)", target, fileSize);

            return URL_PREFIX + "/" + safeSubDir + "/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("本地存储文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeBytes(byte[] data, String subDir, String extension, StorageConfig config) {
        return storeBytes(data, subDir, extension, config, null);
    }

    @Override
    public String storeBytes(byte[] data, String subDir, String extension, StorageConfig config, String customFilename) {
        String basePath = resolveBasePath(config);
        String safeSubDir = validateSubDir(subDir);
        String filename = buildFilename(customFilename, extension);

        try {
            Path dir = Paths.get(basePath, safeSubDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            Files.write(target, data);

            log.info("[LocalStorage] 文件已保存: {} ({} bytes)", target, data.length);
            return URL_PREFIX + "/" + safeSubDir + "/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("本地存储文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeFile(Path filePath, String subDir, String extension, StorageConfig config) {
        return storeFile(filePath, subDir, extension, config, null);
    }

    @Override
    public String storeFile(Path filePath, String subDir, String extension, StorageConfig config, String customFilename) {
        String basePath = resolveBasePath(config);
        String safeSubDir = validateSubDir(subDir);
        String filename = buildFilename(customFilename, extension);

        try {
            Path dir = Paths.get(basePath, safeSubDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            Files.copy(filePath, target, new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});

            long fileSize = Files.size(target);
            log.info("[LocalStorage] 文件已保存: {} ({} bytes)", target, fileSize);
            return URL_PREFIX + "/" + safeSubDir + "/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("本地存储文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建文件名：有 customFilename 时用可读前缀 + 短UUID 保证唯一性，否则用纯 UUID
     */    
    private String buildFilename(String customFilename, String extension) {
        if (StrUtil.isNotBlank(customFilename)) {
            String safe = customFilename.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9._-]", "");
            if (StrUtil.isNotBlank(safe)) {
                return safe + "_" + IdUtil.fastSimpleUUID().substring(0, 8) + "." + extension;
            }
        }
        return IdUtil.fastSimpleUUID() + "." + extension;
    }

    private String resolveBasePath(StorageConfig config) {
        if (config != null && StrUtil.isNotBlank(config.getBasePath())) {
            return config.getBasePath();
        }
        return DEFAULT_BASE_PATH;
    }

    private String validateSubDir(String subDir) {
        if (StrUtil.isBlank(subDir)
                || !subDir.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9_-]{0,63})*")) {
            throw new IllegalArgumentException("非法存储子目录");
        }
        return subDir;
    }

    /**
     * 推断文件扩展名
     */
    private String guessExtension(String url, String contentType) {
        // 优先从 Content-Type 推断
        if (StrUtil.isNotBlank(contentType)) {
            String lower = contentType.toLowerCase();
            if (lower.contains("png")) return "png";
            if (lower.contains("jpeg") || lower.contains("jpg")) return "jpg";
            if (lower.contains("gif")) return "gif";
            if (lower.contains("webp")) return "webp";
            if (lower.contains("svg")) return "svg";
            if (lower.contains("mp4")) return "mp4";
            if (lower.contains("webm")) return "webm";
            if (lower.contains("mov") || lower.contains("quicktime")) return "mov";
        }

        // 从 URL 提取
        try {
            String path = url.split("\\?")[0]; // 去掉 query 参数
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx > 0) {
                String ext = path.substring(dotIdx + 1).toLowerCase();
                if (ext.length() <= 5) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }

        return "bin";
    }
}
