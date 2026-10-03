package com.tonyhe.fusion.security;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.mapper.media.MediaAccessMapper;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.system.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaAccessServiceTests {

    @Mock private MediaAccessMapper mediaAccessMapper;
    @Mock private ProjectService projectService;
    @Mock private ResourceAccessService resourceAccessService;
    @Mock private AuthorizationService authorizationService;
    @Mock private UserService userService;
    @Mock private PermissionMonitorService permissionMonitorService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private MediaAccessService mediaAccessService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(permissionMonitorService.getPermissionVersion()).thenReturn(9L);
    }

    @Test
    void temporaryUploadIsOnlyReadableByUploader() {
        String url = "/media/uploads/new.png";
        mediaAccessService.registerTemporaryUpload(url, 7L);
        verify(valueOperations).set(anyString(), eq("7"), eq(Duration.ofHours(24)));

        when(valueOperations.get(anyString())).thenReturn("7");
        assertThat(mediaAccessService.canRead(url, 7L)).isTrue();
        when(mediaAccessMapper.findGrantsByUrl(url)).thenReturn(List.of());
        assertThat(mediaAccessService.canRead(url, 8L)).isFalse();
    }

    @Test
    void projectGrantUsesProjectResourceAccessAndCachesOnlySuccess() {
        MediaAccessGrant grant = grant("PROJECT", 100L);
        when(mediaAccessMapper.findGrantsByUrl("/media/images/a.png")).thenReturn(List.of(grant));
        when(authorizationService.canAccessProject(7L, 100L)).thenReturn(true);

        assertThat(mediaAccessService.canRead("/media/images/a.png", 7L)).isTrue();
        verify(valueOperations).set(anyString(), eq("true"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void personalGrantCannotLeakToAnotherUser() {
        when(mediaAccessMapper.findGrantsByUrl("/media/audio/private.mp3"))
                .thenReturn(List.of(grant("USER", 7L)));

        assertThat(mediaAccessService.canRead("/media/audio/private.mp3", 8L)).isFalse();
        verify(valueOperations, never()).set(anyString(), eq("true"), any(Duration.class));
    }

    @Test
    void teamAndPovGrantsDelegateToTheirResourceChecks() {
        when(mediaAccessMapper.findGrantsByUrl("/media/videos/shared.mp4"))
                .thenReturn(List.of(grant("TEAM", 20L), grant("POV", 30L)));
        when(authorizationService.canAccessTeam(7L, 20L)).thenReturn(false);

        assertThat(mediaAccessService.canRead("/media/videos/shared.mp4", 7L)).isTrue();
        verify(resourceAccessService).checkPovStoryAccess(30L, 7L);
    }

    @Test
    void deniedPovGrantDoesNotBecomeAnAllow() {
        when(mediaAccessMapper.findGrantsByUrl("/media/videos/private.mp4"))
                .thenReturn(List.of(grant("POV", 30L)));
        doThrow(new BusinessException(403, "denied"))
                .when(resourceAccessService).checkPovStoryAccess(30L, 8L);

        assertThat(mediaAccessService.canRead("/media/videos/private.mp4", 8L)).isFalse();
    }

    private MediaAccessGrant grant(String type, Long id) {
        MediaAccessGrant grant = new MediaAccessGrant();
        grant.setResourceType(type);
        grant.setResourceId(id);
        return grant;
    }
}
