package com.tonyhe.fusion.service.storage.strategy;

import com.tonyhe.fusion.entity.storage.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageStrategyTests {

    @TempDir
    Path tempDir;

    private final LocalStorageStrategy strategy = new LocalStorageStrategy();

    @Test
    void nestedSafeDirectoryRemainsCompatibleWithVideoComposition() {
        StorageConfig config = StorageConfig.builder().basePath(tempDir.toString()).build();

        String url = strategy.storeBytes(new byte[]{1, 2, 3}, "videos/composed", "mp4", config);

        assertThat(url).startsWith("/media/videos/composed/");
        assertThat(Files.exists(tempDir.resolve(url.substring("/media/".length())))).isTrue();
    }

    @Test
    void pathTraversalAndAbsoluteDirectoriesAreRejected() {
        StorageConfig config = StorageConfig.builder().basePath(tempDir.toString()).build();
        assertThatThrownBy(() -> strategy.storeBytes(new byte[]{1}, "../outside", "png", config))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategy.storeBytes(new byte[]{1}, "/absolute", "png", config))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategy.storeBytes(new byte[]{1}, "images\\outside", "png", config))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
