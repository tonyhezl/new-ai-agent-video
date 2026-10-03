package com.tonyhe.fusion.service.auth;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.workbench.EmailCode;
import com.tonyhe.fusion.mapper.workbench.EmailCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务。
 * <p>
 * 规则：
 * - 6 位数字验证码，有效期 10 分钟
 * - 同一邮箱 60 秒内不能重复发送
 * - 同一 IP 每日发送上限 20 条
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRE_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int DAILY_IP_LIMIT = 20;

    private static final String RESEND_KEY = "email:resend:";
    private static final String IP_LIMIT_KEY = "email:ip:";

    private final EmailCodeMapper emailCodeMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 生成并发送验证码（实际发送由调用方处理）。
     *
     * @param email   邮箱
     * @param purpose 用途 REGISTER/RESET_PASSWORD/CHANGE_EMAIL
     * @param ip      客户端 IP
     * @return 生成的验证码
     */
    public String generateAndSave(String email, String purpose, String ip) {
        email = email.trim().toLowerCase();

        // 60 秒内不能重复发送
        String resendKey = RESEND_KEY + email + ":" + purpose;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(resendKey))) {
            long ttl = redisTemplate.getExpire(resendKey, TimeUnit.SECONDS);
            throw new BusinessException(400, "验证码已发送，请" + (ttl > 0 ? ttl : 1) + "秒后重试");
        }

        // IP 每日上限
        if (ip != null && !ip.isBlank()) {
            String ipKey = IP_LIMIT_KEY + ip;
            String countStr = redisTemplate.opsForValue().get(ipKey);
            int count = countStr != null ? Integer.parseInt(countStr) : 0;
            if (count >= DAILY_IP_LIMIT) {
                throw new BusinessException(400, "今日验证码发送次数已达上限");
            }
            redisTemplate.opsForValue().increment(ipKey);
            if (count == 0) {
                redisTemplate.expire(ipKey, Duration.ofDays(1));
            }
        }

        // 生成 6 位数字验证码
        String code = RandomUtil.randomNumbers(CODE_LENGTH);

        // 存入数据库
        EmailCode emailCode = EmailCode.builder()
                .email(email)
                .purpose(purpose)
                .code(code)
                .used(0)
                .expireAt(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES))
                .clientIp(ip)
                .build();
        emailCodeMapper.insert(emailCode);

        // 设置重发冷却
        redisTemplate.opsForValue().set(resendKey, "1", Duration.ofSeconds(RESEND_COOLDOWN_SECONDS));

        log.info("Email code generated for {} purpose={}", email, purpose);
        return code;
    }

    /**
     * 校验验证码。
     *
     * @param email   邮箱
     * @param purpose 用途
     * @param code    验证码
     * @return true 校验通过
     */
    public boolean verify(String email, String purpose, String code) {
        email = email.trim().toLowerCase();
        EmailCode emailCode = emailCodeMapper.selectOne(
                new LambdaQueryWrapper<EmailCode>()
                        .eq(EmailCode::getEmail, email)
                        .eq(EmailCode::getPurpose, purpose)
                        .eq(EmailCode::getUsed, 0)
                        .gt(EmailCode::getExpireAt, LocalDateTime.now())
                        .orderByDesc(EmailCode::getId)
                        .last("LIMIT 1"));

        if (emailCode == null) {
            return false;
        }

        if (!emailCode.getCode().equals(code)) {
            return false;
        }

        // 标记已使用
        emailCode.setUsed(1);
        emailCodeMapper.updateById(emailCode);
        return true;
    }
}
