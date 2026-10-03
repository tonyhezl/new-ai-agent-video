package com.tonyhe.fusion.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.system.RoleAssignment;
import com.tonyhe.fusion.mapper.system.RoleAssignmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 权限监控服务
 * <p>
 * - 权限版本号管理（每次权限变更递增）
 * - 缓存命中率统计
 * - 403 拒绝次数统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionMonitorService {

    private static final String PERMISSION_VERSION_KEY = "permission:version";
    private static final String DENIED_COUNTER_KEY = "permission:denied:count";
    private static final String CACHE_HIT_KEY = "permission:cache:hit";
    private static final String CACHE_MISS_KEY = "permission:cache:miss";

    private final StringRedisTemplate stringRedisTemplate;
    private final RoleAssignmentMapper roleAssignmentMapper;

    private final AtomicLong deniedCount = new AtomicLong(0);
    private final AtomicLong cacheHit = new AtomicLong(0);
    private final AtomicLong cacheMiss = new AtomicLong(0);

    /**
     * 获取当前权限版本号
     */
    public Long getPermissionVersion() {
        String value = stringRedisTemplate.opsForValue().get(PERMISSION_VERSION_KEY);
        return value != null ? Long.parseLong(value) : 0L;
    }

    /**
     * 权限变更时递增版本号
     */
    public void incrementPermissionVersion() {
        stringRedisTemplate.opsForValue().increment(PERMISSION_VERSION_KEY);
    }

    /**
     * 记录 403 拒绝
     */
    public void recordDenied() {
        deniedCount.incrementAndGet();
        stringRedisTemplate.opsForValue().increment(DENIED_COUNTER_KEY);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHit.incrementAndGet();
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMiss.incrementAndGet();
    }

    /**
     * 获取监控指标
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("permissionVersion", getPermissionVersion());
        metrics.put("deniedCount", deniedCount.get());
        metrics.put("cacheHit", cacheHit.get());
        metrics.put("cacheMiss", cacheMiss.get());
        double hitRate = (cacheHit.get() + cacheMiss.get()) > 0
                ? (double) cacheHit.get() / (cacheHit.get() + cacheMiss.get())
                : 0.0;
        metrics.put("cacheHitRate", String.format("%.2f%%", hitRate * 100));

        // 统计过期但未清理的分配数
        long expiredCount = roleAssignmentMapper.selectCount(
                new LambdaQueryWrapper<RoleAssignment>()
                        .eq(RoleAssignment::getStatus, "ACTIVE")
                        .isNotNull(RoleAssignment::getExpiresAt)
                        .lt(RoleAssignment::getExpiresAt, java.time.LocalDateTime.now()));
        metrics.put("expiredAssignments", expiredCount);

        return metrics;
    }

    /**
     * 每小时输出一次监控日志
     */
    @Scheduled(fixedRate = 3600000)
    public void logMetrics() {
        Map<String, Object> metrics = getMetrics();
        log.info("[PermissionMonitor] {}", metrics);
    }
}
