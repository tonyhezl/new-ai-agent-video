package com.tonyhe.fusion.security;

import cn.hutool.crypto.digest.DigestUtil;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.mapper.media.MediaAccessMapper;
import com.tonyhe.fusion.service.system.PermissionMonitorService;
import com.tonyhe.fusion.service.system.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** Authorizes local media reads without exposing the storage directory as a public namespace. */
@Service
@RequiredArgsConstructor
public class MediaAccessService {

    private static final Duration ACCESS_CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration UPLOAD_GRANT_TTL = Duration.ofHours(24);
    private static final String ACCESS_KEY_PREFIX = "media:access:";
    private static final String UPLOAD_KEY_PREFIX = "media:upload-owner:";

    private final MediaAccessMapper mediaAccessMapper;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;
    private final UserService userService;
    private final PermissionMonitorService permissionMonitorService;
    private final StringRedisTemplate redisTemplate;

    public void registerTemporaryUpload(String url, Long userId) {
        if (isLocalMediaUrl(url) && userId != null) {
            redisTemplate.opsForValue().set(uploadKey(url), userId.toString(), UPLOAD_GRANT_TTL);
        }
    }

    public boolean canRead(String url, Long userId) {
        if (!isLocalMediaUrl(url) || userId == null) return false;
        if (userService.isAdmin(userId)) return true;

        String uploadOwner = redisTemplate.opsForValue().get(uploadKey(url));
        if (userId.toString().equals(uploadOwner)) return true;

        String accessKey = accessKey(url, userId);
        if (Boolean.TRUE.toString().equals(redisTemplate.opsForValue().get(accessKey))) return true;

        List<MediaAccessGrant> grants = mediaAccessMapper.findGrantsByUrl(url);
        for (MediaAccessGrant grant : grants) {
            if (canRead(grant, userId)) {
                redisTemplate.opsForValue().set(accessKey, Boolean.TRUE.toString(), ACCESS_CACHE_TTL);
                return true;
            }
        }
        return false;
    }

    private boolean canRead(MediaAccessGrant grant, Long userId) {
        if (grant.getResourceType() == null || grant.getResourceId() == null) return false;
        return switch (grant.getResourceType()) {
            case "USER" -> userId.equals(grant.getResourceId());
            case "TEAM" -> authorizationService.canAccessTeam(userId, grant.getResourceId());
            case "PROJECT" -> authorizationService.canAccessProject(userId, grant.getResourceId());
            case "POV" -> canReadPov(grant.getResourceId(), userId);
            default -> false;
        };
    }

    private boolean canReadPov(Long storyId, Long userId) {
        try {
            resourceAccessService.checkPovStoryAccess(storyId, userId);
            return true;
        } catch (BusinessException ignored) {
            return false;
        }
    }

    private String accessKey(String url, Long userId) {
        return ACCESS_KEY_PREFIX + permissionMonitorService.getPermissionVersion() + ":"
                + userId + ":" + DigestUtil.sha256Hex(url);
    }

    private String uploadKey(String url) {
        return UPLOAD_KEY_PREFIX + DigestUtil.sha256Hex(url);
    }

    private boolean isLocalMediaUrl(String url) {
        return url != null && url.startsWith("/media/") && !url.contains("..");
    }
}
