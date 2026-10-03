package com.tonyhe.fusion.service.storage;

import com.tonyhe.fusion.entity.storage.StorageConfig;

import java.nio.file.Path;

/**
 * 存储策略接口
 * <p>
 * 不同存储后端（本地磁盘 / 阿里云 OSS / 腾讯 COS 等）实现此接口。
 */
public interface StorageStrategy {

    /**
     * 策略类型标识（对应 StorageConfig.type）
     */
    String getType();

    /**
     * 从远程 URL 下载文件并保存到当前存储后端
     *
     * @param remoteUrl 远程文件 URL
     * @param subDir    子目录（如 images、videos）
     * @param config    存储配置
     * @return 持久化后的可访问 URL
     */
    String store(String remoteUrl, String subDir, StorageConfig config);

    /**
     * 从远程 URL 下载文件并保存到当前存储后端，使用自定义文件名前缀
     *
     * @param remoteUrl      远程文件 URL
     * @param subDir         子目录（如 images、videos）
     * @param config         存储配置
     * @param customFilename 自定义文件名前缀（不含扩展名），为 null 时回退到 UUID
     * @return 持久化后的可访问 URL
     */
    default String store(String remoteUrl, String subDir, StorageConfig config, String customFilename) {
        return store(remoteUrl, subDir, config);
    }

    /**
     * 从字节数组保存文件（适用于 VertexAI 返回 base64 的场景）
     *
     * @param data      文件字节数据
     * @param subDir    子目录
     * @param extension 文件扩展名（如 png、mp4）
     * @param config    存储配置
     * @return 持久化后的可访问 URL
     */
    String storeBytes(byte[] data, String subDir, String extension, StorageConfig config);

    /**
     * 从字节数组保存文件，使用自定义文件名前缀
     */
    default String storeBytes(byte[] data, String subDir, String extension, StorageConfig config, String customFilename) {
        return storeBytes(data, subDir, extension, config);
    }

    /**
     * 从本地文件保存到当前存储后端。
     *
     * @param filePath   本地文件路径
     * @param subDir     子目录
     * @param extension  文件扩展名
     * @param config     存储配置
     * @return 持久化后的可访问 URL
     */
    String storeFile(Path filePath, String subDir, String extension, StorageConfig config);

    /**
     * 从本地文件保存到当前存储后端，使用自定义文件名前缀
     */
    default String storeFile(Path filePath, String subDir, String extension, StorageConfig config, String customFilename) {
        return storeFile(filePath, subDir, extension, config);
    }
}
