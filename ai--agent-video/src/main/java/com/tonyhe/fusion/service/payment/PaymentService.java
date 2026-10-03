package com.tonyhe.fusion.service.payment;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.workbench.CreditTransaction;
import com.tonyhe.fusion.entity.workbench.RechargeOrder;
import com.tonyhe.fusion.entity.workbench.RechargePackage;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.mapper.workbench.CreditTransactionMapper;
import com.tonyhe.fusion.mapper.workbench.RechargeOrderMapper;
import com.tonyhe.fusion.mapper.workbench.RechargePackageMapper;
import com.tonyhe.fusion.service.system.SystemConfigService;
import com.tonyhe.fusion.service.workbench.RewardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final RechargePackageMapper rechargePackageMapper;
    private final CreditTransactionMapper creditTransactionMapper;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;
    private final RewardService rewardService;

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 创建充值订单。
     */
    @Transactional
    public RechargeOrder createOrder(Long userId, BigDecimal amountCny, String payChannel) {
        // 查找匹配的套餐
        RechargePackage pkg = rechargePackageMapper.selectOne(
                new LambdaQueryWrapper<RechargePackage>()
                        .eq(RechargePackage::getAmountCny, amountCny)
                        .eq(RechargePackage::getStatus, 1)
                        .last("LIMIT 1"));

        int creditAmount;
        if (pkg != null) {
            creditAmount = pkg.getCreditAmount() + pkg.getBonusCredit();
        } else {
            // 无套餐匹配时按 1 CNY = 10 创作点
            creditAmount = amountCny.multiply(BigDecimal.valueOf(10)).intValue();
        }

        String orderNo = generateOrderNo();

        RechargeOrder order = RechargeOrder.builder()
                .orderNo(orderNo)
                .userId(userId)
                .packageId(pkg != null ? pkg.getId() : null)
                .amountCny(amountCny)
                .creditAmount(creditAmount)
                .status("PENDING")
                .payChannel(payChannel)
                .payProvider("vmq")
                .expireAt(LocalDateTime.now().plusMinutes(5))
                .deletedId(0L)
                .build();
        rechargeOrderMapper.insert(order);

        log.info("Recharge order created: orderNo={}, userId={}, amount={}, credit={}",
                orderNo, userId, amountCny, creditAmount);
        return order;
    }

    /**
     * V免签回调处理：验签 → 幂等 → 入账。
     *
     * @param payId      V免签 payId（对应我们的 orderNo）
     * @param param      V免签 param（对应 userId）
     * @param type       1=微信 2=支付宝
     * @param price      原始金额
     * @param reallyPrice 实付金额
     * @param sign       签名
     * @return true 处理成功
     */
    @Transactional
    public boolean handleVmqCallback(String payId, String param, String type,
                                      String price, String reallyPrice, String sign) {
        // 1. 验签
        String vmqKey = systemConfigService.getValue("vmq_comm_key");
        if (StrUtil.isBlank(vmqKey)) {
            log.error("VMQ comm key not configured");
            return false;
        }

        String expectedSign = SecureUtil.md5(payId + param + type + price + reallyPrice + vmqKey);
        if (!expectedSign.equalsIgnoreCase(sign)) {
            log.warn("VMQ callback sign mismatch: payId={}, expected={}, got={}", payId, expectedSign, sign);
            return false;
        }

        // 2. 查订单
        RechargeOrder order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getOrderNo, payId)
                        .last("LIMIT 1"));

        if (order == null) {
            log.warn("VMQ callback order not found: payId={}", payId);
            return false;
        }

        // 3. 幂等：已支付直接返回
        if ("PAID".equals(order.getStatus())) {
            log.info("VMQ callback order already paid: {}", payId);
            return true;
        }

        // 4. 更新订单
        order.setStatus("PAID");
        order.setReallyPrice(new BigDecimal(reallyPrice));
        order.setProviderPayId(payId);
        order.setPaidAt(LocalDateTime.now());
        rechargeOrderMapper.updateById(order);

        // 5. 加创作点（乐观锁）
        addCredit(order.getUserId(), order.getCreditAmount(), order.getId());

        // 6. P2: 首充优惠
        int firstBonus = rewardService.grantFirstRechargeBonus(order.getUserId(), order.getId());

        // 7. P2: 邀请奖励（首充后触发）
        if (firstBonus > 0) {
            rewardService.grantInvitationReward(order.getUserId());
        }

        log.info("VMQ callback processed: orderNo={}, userId={}, credit={}",
                payId, order.getUserId(), order.getCreditAmount());
        return true;
    }

    /**
     * 管理员手动补单。
     */
    @Transactional
    public boolean adminConfirmOrder(String orderNo) {
        RechargeOrder order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getOrderNo, orderNo)
                        .last("LIMIT 1"));

        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }

        if ("PAID".equals(order.getStatus())) {
            return true; // 幂等
        }

        order.setStatus("PAID");
        order.setPaidAt(LocalDateTime.now());
        rechargeOrderMapper.updateById(order);

        addCredit(order.getUserId(), order.getCreditAmount(), order.getId());

        // P2: 首充优惠 + 邀请奖励
        int firstBonus = rewardService.grantFirstRechargeBonus(order.getUserId(), order.getId());
        if (firstBonus > 0) {
            rewardService.grantInvitationReward(order.getUserId());
        }

        log.info("Admin confirmed order: orderNo={}, userId={}, credit={}",
                orderNo, order.getUserId(), order.getCreditAmount());
        return true;
    }

    /**
     * 查询订单状态。
     */
    public RechargeOrder getOrderByNo(String orderNo) {
        return rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getOrderNo, orderNo)
                        .last("LIMIT 1"));
    }

    /**
     * 获取待支付订单列表（管理员用）。
     */
    public List<RechargeOrder> getWaitPayOrders() {
        return rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getStatus, "WAIT_PAY")
                        .orderByDesc(RechargeOrder::getCreateTime));
    }

    /**
     * 关闭过期订单。
     */
    @Transactional
    public int closeExpiredOrders() {
        List<RechargeOrder> expired = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getStatus, "WAIT_PAY")
                        .lt(RechargeOrder::getExpireAt, LocalDateTime.now()));

        int count = 0;
        for (RechargeOrder order : expired) {
            order.setStatus("CLOSED");
            rechargeOrderMapper.updateById(order);
            count++;
        }
        if (count > 0) {
            log.info("Closed {} expired recharge orders", count);
        }
        return count;
    }

    /**
     * 加创作点（带乐观锁）。
     */
    private void addCredit(Long userId, int amount, Long orderId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            log.error("User not found for credit add: userId={}", userId);
            return;
        }

        int newBalance = user.getCreditBalance() + amount;
        int newVersion = user.getCreditVersion() + 1;

        int rows = userMapper.updateCreditBalance(userId, amount, user.getCreditVersion());
        if (rows == 0) {
            // 乐观锁冲突，重试一次
            user = userMapper.selectById(userId);
            newBalance = user.getCreditBalance() + amount;
            newVersion = user.getCreditVersion() + 1;
            rows = userMapper.updateCreditBalance(userId, amount, user.getCreditVersion());
            if (rows == 0) {
                log.error("Credit update failed after retry: userId={}, amount={}", userId, amount);
                throw new BusinessException(500, "充值入账失败，请重试");
            }
        }

        // 写流水
        CreditTransaction tx = CreditTransaction.builder()
                .userId(userId)
                .type("RECHARGE")
                .amount(amount)
                .balanceAfter(newBalance)
                .relatedId(orderId)
                .relatedType("RECHARGE_ORDER")
                .remark("充值入账")
                .build();
        creditTransactionMapper.insert(tx);
    }

    private String generateOrderNo() {
        return "ORD-" + LocalDateTime.now().format(ORDER_NO_FORMAT) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
