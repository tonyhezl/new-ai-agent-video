package com.tonyhe.fusion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonyhe.fusion.entity.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CacheConfigTests {

    private RedisCacheConfiguration configuration;

    @BeforeEach
    void setUp() {
        RedisCacheManager manager = new CacheConfig().cacheManager(mock(RedisConnectionFactory.class));
        manager.afterPropertiesSet();
        RedisCache cache = (RedisCache) manager.getCache("project");
        assertThat(cache).isNotNull();
        configuration = cache.getCacheConfiguration();
    }

    @Test
    void cacheKeysAreIsolatedFromThePreviousDefaultNamespace() {
        String previousKey = RedisCacheConfiguration.defaultCacheConfig().getKeyPrefixFor("project") + "42";
        String currentKey = configuration.getKeyPrefixFor("project") + "42";

        assertThat(configuration.usePrefix()).isTrue();
        assertThat(currentKey).isNotEqualTo(previousKey);
        assertThat(configuration.getKeyPrefixFor("project"))
                .isNotEqualTo(configuration.getKeyPrefixFor("userByUsername"));
    }

    @Test
    void projectCacheRoundTripsUsingTheRenamedJavaType() throws Exception {
        Project project = Project.builder().id(42L).name("迁移后的项目").build();
        ByteBuffer payload = configuration.getValueSerializationPair().write(project);
        byte[] json = new byte[payload.remaining()];
        payload.duplicate().get(json);

        assertThat(new ObjectMapper().readTree(json).get("@class").asText())
                .isEqualTo(Project.class.getName());
        Object restored = configuration.getValueSerializationPair().read(ByteBuffer.wrap(json));
        assertThat(restored).isInstanceOf(Project.class);
        assertThat((Project) restored).isEqualTo(project);
    }
}
