package com.tonyhe.fusion.service.workbench;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.ai.AiModel;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.workbench.Guest;
import com.tonyhe.fusion.entity.workbench.ImageModelPrice;
import com.tonyhe.fusion.mapper.ai.AiModelMapper;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.mapper.workbench.GuestMapper;
import com.tonyhe.fusion.mapper.workbench.ImageModelPriceMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserMapper userMapper;
    private final GuestMapper guestMapper;
    private final AiModelMapper aiModelMapper;
    private final ImageModelPriceMapper imageModelPriceMapper;
    private final RateLimitService rateLimitService;

    @Data
    @AllArgsConstructor
    public static class QuotaInfo {
        private boolean isGuest;
        private String guestId;
        private int freeQuotaTotal;
        private int freeQuotaRemaining;
        private int creditBalance;
        private boolean mustLogin;
    }

    @Data
    @AllArgsConstructor
    public static class BillingResult {
        private boolean useFreeQuota;
        private int creditCost;
        private ImageModelPrice price;
    }

    /**
     * 获取当前额度信息。
     */
    public QuotaInfo getQuotaInfo(Long userId, String guestId) {
        return getQuotaInfo(userId, guestId, null, null);
    }

    /**
     * 获取当前额度信息（含风控参数）。
     *
     * @param fingerprint 设备指纹（P1 防刷）
     * @param ip          客户端 IP（P1 限流）
     */
    public QuotaInfo getQuotaInfo(Long userId, String guestId, String fingerprint, String ip) {
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null) {
                int remaining = user.getFreeQuotaTotal() - user.getFreeQuotaUsed();
                return new QuotaInfo(false, user.getGuestId(), user.getFreeQuotaTotal(),
                        remaining, user.getCreditBalance(), false);
            }
        }

        if (StrUtil.isNotBlank(guestId)) {
            Guest guest = guestMapper.selectOne(new LambdaQueryWrapper<Guest>()
                    .eq(Guest::getGuestId, guestId));
            if (guest == null) {
                // P1: IP 创建游客限流
                if (StrUtil.isNotBlank(ip) && !rateLimitService.checkIpGuestCreate(ip)) {
                    throw new BusinessException(429, "该 IP 今日创建游客数已达上限");
                }

                // P1: 设备指纹去重 — 同一指纹已绑定过游客则不再发放免费额度
                boolean fingerprintExists = StrUtil.isNotBlank(fingerprint)
                        && !rateLimitService.checkFingerprintUnique(fingerprint);

                guest = Guest.builder()
                        .guestId(guestId)
                        .fingerprint(fingerprint)
                        .ip(ip)
                        .freeQuotaUsed(fingerprintExists ? 2 : 0)
                        .freeQuotaTotal(2)
                        .lastSeenAt(LocalDateTime.now())
                        .deletedId(0L)
                        .build();
                guestMapper.insert(guest);

                if (StrUtil.isNotBlank(fingerprint)) {
                    rateLimitService.markFingerprintBound(fingerprint);
                }
            } else {
                guest.setLastSeenAt(LocalDateTime.now());
                if (StrUtil.isNotBlank(fingerprint) && StrUtil.isBlank(guest.getFingerprint())) {
                    guest.setFingerprint(fingerprint);
                }
                if (StrUtil.isNotBlank(ip) && StrUtil.isBlank(guest.getIp())) {
                    guest.setIp(ip);
                }
                guestMapper.updateById(guest);
            }
            int remaining = guest.getFreeQuotaTotal() - guest.getFreeQuotaUsed();
            return new QuotaInfo(true, guest.getGuestId(), guest.getFreeQuotaTotal(),
                    remaining, 0, false);
        }

        // 无 userId 无 guestId，返回空
        return new QuotaInfo(true, null, 0, 0, 0, true);
    }

    /**
     * 计费校验：判断用免费额度还是扣创作点。
     */
    public BillingResult resolveBilling(Long userId, String guestId, Long modelId,
                                         String qualityLevel, String aspectRatio) {
        // 查模型
        AiModel model = aiModelMapper.selectById(modelId);
        if (model == null || model.getStatus() == null || model.getStatus() != 1) {
            throw new BusinessException(400, "模型不存在或已禁用");
        }

        // 查价格
        ImageModelPrice price = imageModelPriceMapper.selectOne(
                new LambdaQueryWrapper<ImageModelPrice>()
                        .eq(ImageModelPrice::getModelId, modelId)
                        .eq(ImageModelPrice::getQualityLevel, qualityLevel)
                        .eq(ImageModelPrice::getAspectRatio, aspectRatio)
                        .eq(ImageModelPrice::getStatus, 1)
                        .last("LIMIT 1"));

        if (price == null) {
            // 尝试默认比例
            price = imageModelPriceMapper.selectOne(
                    new LambdaQueryWrapper<ImageModelPrice>()
                            .eq(ImageModelPrice::getModelId, modelId)
                            .eq(ImageModelPrice::getQualityLevel, qualityLevel)
                            .eq(ImageModelPrice::getIsDefault, 1)
                            .eq(ImageModelPrice::getStatus, 1)
                            .last("LIMIT 1"));
        }

        // 兼容旧版质量等级别名 (1K <-> standard/fast, 2K <-> ultra)
        if (price == null) {
            String aliasLevel = null;
            if ("1K".equalsIgnoreCase(qualityLevel) || "1k".equalsIgnoreCase(qualityLevel)) {
                aliasLevel = "standard";
            } else if ("standard".equalsIgnoreCase(qualityLevel)) {
                aliasLevel = "1K";
            } else if ("2K".equalsIgnoreCase(qualityLevel) || "2k".equalsIgnoreCase(qualityLevel)) {
                aliasLevel = "ultra";
            } else if ("ultra".equalsIgnoreCase(qualityLevel)) {
                aliasLevel = "2K";
            }

            if (aliasLevel != null) {
                price = imageModelPriceMapper.selectOne(
                        new LambdaQueryWrapper<ImageModelPrice>()
                                .eq(ImageModelPrice::getModelId, modelId)
                                .eq(ImageModelPrice::getQualityLevel, aliasLevel)
                                .eq(ImageModelPrice::getStatus, 1)
                                .last("LIMIT 1"));
            }
        }

        // 最终兜底：该模型的任意启用价格
        if (price == null) {
            price = imageModelPriceMapper.selectOne(
                    new LambdaQueryWrapper<ImageModelPrice>()
                            .eq(ImageModelPrice::getModelId, modelId)
                            .eq(ImageModelPrice::getStatus, 1)
                            .orderByDesc(ImageModelPrice::getIsDefault)
                            .last("LIMIT 1"));
        }

        if (price == null) {
            throw new BusinessException(400, "该模型未配置价格");
        }

        int creditCost = price.getCreditCost();
        boolean isFreeModel = Boolean.TRUE.equals(model.getIsFreeModel());

        // 判断免费额度
        if (isFreeModel) {
            int freeRemaining = getFreeRemaining(userId, guestId);
            if (freeRemaining > 0) {
                return new BillingResult(true, 0, price);
            }
        }

        // 付费：检查余额
        if (userId == null) {
            throw new BusinessException(403, "免费额度已用完，请登录后充值");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(403, "用户不存在");
        }

        if (user.getCreditBalance() < creditCost) {
            throw new BusinessException(402, "创作点余额不足，请充值");
        }

        return new BillingResult(false, creditCost, price);
    }

    /**
     * 扣免费额度。
     */
    public void deductFreeQuota(Long userId, String guestId) {
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null) {
                user.setFreeQuotaUsed(user.getFreeQuotaUsed() + 1);
                userMapper.updateById(user);
            }
        } else if (StrUtil.isNotBlank(guestId)) {
            Guest guest = guestMapper.selectOne(new LambdaQueryWrapper<Guest>()
                    .eq(Guest::getGuestId, guestId));
            if (guest != null) {
                guest.setFreeQuotaUsed(guest.getFreeQuotaUsed() + 1);
                guestMapper.updateById(guest);
            }
        }
    }

    /**
     * 扣创作点（乐观锁）。
     */
    public void deductCredit(Long userId, int amount) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(403, "用户不存在");
        }

        int rows = userMapper.deductCreditBalance(userId, amount, user.getCreditVersion());
        if (rows == 0) {
            // 重试一次
            user = userMapper.selectById(userId);
            if (user.getCreditBalance() < amount) {
                throw new BusinessException(402, "创作点余额不足");
            }
            rows = userMapper.deductCreditBalance(userId, amount, user.getCreditVersion());
            if (rows == 0) {
                throw new BusinessException(409, "扣费冲突，请重试");
            }
        }
    }

    /**
     * 退还创作点（失败回滚）。
     */
    public void refundCredit(Long userId, int amount) {
        User user = userMapper.selectById(userId);
        if (user == null) return;

        userMapper.updateCreditBalance(userId, amount, user.getCreditVersion());
        log.info("Credit refunded: userId={}, amount={}", userId, amount);
    }

    private int getFreeRemaining(Long userId, String guestId) {
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null) {
                return user.getFreeQuotaTotal() - user.getFreeQuotaUsed();
            }
        }
        if (StrUtil.isNotBlank(guestId)) {
            Guest guest = guestMapper.selectOne(new LambdaQueryWrapper<Guest>()
                    .eq(Guest::getGuestId, guestId));
            if (guest != null) {
                return guest.getFreeQuotaTotal() - guest.getFreeQuotaUsed();
            }
        }
        return 0;
    }
}
