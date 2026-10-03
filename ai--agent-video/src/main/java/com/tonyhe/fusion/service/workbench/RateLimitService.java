package com.tonyhe.fusion.service.workbench;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 风控限流服务（P1）。
 * <p>
 * - IP 限流：单 IP 每日创建游客数上限 5 个
 * - IP 生图限流：单 IP 每小时生图上限 30 次
 * - 设备指纹去重：同一指纹的游客不重复发放免费额度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    private static final String IP_GUEST_DAILY_PREFIX = "ratelimit:ip:guest:";
    private static final String IP_GENERATE_HOURLY_PREFIX = "ratelimit:ip:gen:";
    private static final String FINGERPRINT_GUEST_PREFIX = "ratelimit:fp:guest:";

    private static final int IP_GUEST_DAILY_LIMIT = 5;
    private static final int IP_GENERATE_HOURLY_LIMIT = 30;

    /**
     * 检查并记录 IP 创建游客（每日上限 5 个）。
     *
     * @return true 允许创建，false 超限
     */
    public boolean checkIpGuestCreate(String ip) {
        if (StrUtil.isBlank(ip)) return true;
        String key = IP_GUEST_DAILY_PREFIX + ip;
        String countStr = redisTemplate.opsForValue().get(key);
        int count = countStr != null ? Integer.parseInt(countStr) : 0;
        if (count >= IP_GUEST_DAILY_LIMIT) {
            log.warn("IP {} 创建游客数超限: {}/{}", ip, count, IP_GUEST_DAILY_LIMIT);
            return false;
        }
        redisTemplate.opsForValue().increment(key);
        if (count == 0) {
            redisTemplate.expire(key, Duration.ofDays(1));
        }
        return true;
    }

    /**
     * 检查并记录 IP 生图请求（每小时上限 30 次）。
     *
     * @return true 允许，false 超限
     */
    public boolean checkIpGenerate(String ip) {
        if (StrUtil.isBlank(ip)) return true;
        String key = IP_GENERATE_HOURLY_PREFIX + ip;
        String countStr = redisTemplate.opsForValue().get(key);
        int count = countStr != null ? Integer.parseInt(countStr) : 0;
        if (count >= IP_GENERATE_HOURLY_LIMIT) {
            log.warn("IP {} 生图请求超限: {}/{}", ip, count, IP_GENERATE_HOURLY_LIMIT);
            return false;
        }
        redisTemplate.opsForValue().increment(key);
        if (count == 0) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        return true;
    }

    /**
     * 检查设备指纹是否已绑定游客（去重）。
     *
     * @return true 首次出现，false 已存在
     */
    public boolean checkFingerprintUnique(String fingerprint) {
        if (StrUtil.isBlank(fingerprint)) return true;
        String key = FINGERPRINT_GUEST_PREFIX + fingerprint;
        return Boolean.FALSE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 标记设备指纹已绑定游客。
     */
    public void markFingerprintBound(String fingerprint) {
        if (StrUtil.isBlank(fingerprint)) return;
        String key = FINGERPRINT_GUEST_PREFIX + fingerprint;
        redisTemplate.opsForValue().set(key, "1", Duration.ofDays(365));
    }
}
