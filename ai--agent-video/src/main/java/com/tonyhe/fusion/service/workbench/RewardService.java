package com.tonyhe.fusion.service.workbench;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.workbench.CreditTransaction;
import com.tonyhe.fusion.entity.workbench.FirstRechargeLog;
import com.tonyhe.fusion.entity.workbench.Invitation;
import com.tonyhe.fusion.entity.workbench.RechargeOrder;
import com.tonyhe.fusion.mapper.system.UserMapper;
import com.tonyhe.fusion.mapper.workbench.CreditTransactionMapper;
import com.tonyhe.fusion.mapper.workbench.FirstRechargeLogMapper;
import com.tonyhe.fusion.mapper.workbench.InvitationMapper;
import com.tonyhe.fusion.service.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * P2 奖励服务：邀请奖励、首充优惠、失败自动退点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardService {

    private final InvitationMapper invitationMapper;
    private final FirstRechargeLogMapper firstRechargeLogMapper;
    private final CreditTransactionMapper creditTransactionMapper;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 首充优惠：首次充值额外赠送创作点。
     * <p>
     * 在 PaymentService 入账后调用。
     *
     * @param userId 用户 ID
     * @param orderId 订单 ID
     * @return 赠送的创作点数（0 表示非首充或已领过）
     */
    @Transactional
    public int grantFirstRechargeBonus(Long userId, Long orderId) {
        // 检查是否已领过首充
        boolean exists = firstRechargeLogMapper.exists(
                new LambdaQueryWrapper<FirstRechargeLog>()
                        .eq(FirstRechargeLog::getUserId, userId));
        if (exists) {
            return 0;
        }

        int bonusCredit = Integer.parseInt(
                StrUtil.blankToDefault(systemConfigService.getValue("first_recharge_bonus_credit"), "50"));

        FirstRechargeLog log = FirstRechargeLog.builder()
                .userId(userId)
                .orderId(orderId)
                .bonusCredit(bonusCredit)
                .build();
        firstRechargeLogMapper.insert(log);

        // 加创作点
        User user = userMapper.selectById(userId);
        if (user == null) return 0;

        userMapper.updateCreditBalance(userId, bonusCredit, user.getCreditVersion());

        // 写流水
        CreditTransaction tx = CreditTransaction.builder()
                .userId(userId)
                .type("RECHARGE")
                .amount(bonusCredit)
                .balanceAfter(user.getCreditBalance() + bonusCredit)
                .relatedId(orderId)
                .relatedType("RECHARGE_ORDER")
                .remark("首充优惠赠送")
                .build();
        creditTransactionMapper.insert(tx);

        RewardService.log.info("First recharge bonus granted: userId={}, bonus={}", userId, bonusCredit);
        return bonusCredit;
    }

    /**
     * 邀请奖励：被邀请人首充后，双方各得创作点。
     * <p>
     * 在首充优惠之后调用。
     *
     * @param userId 被邀请人 ID（首充用户）
     * @return 奖励的创作点数（0 表示无邀请关系或已奖励）
     */
    @Transactional
    public int grantInvitationReward(Long userId) {
        // 查找邀请关系
        Invitation invitation = invitationMapper.selectOne(
                new LambdaQueryWrapper<Invitation>()
                        .eq(Invitation::getInviteeId, userId)
                        .eq(Invitation::getStatus, "PENDING")
                        .last("LIMIT 1"));
        if (invitation == null) {
            return 0;
        }

        int rewardCredit = invitation.getRewardCredit() != null
                ? invitation.getRewardCredit()
                : Integer.parseInt(StrUtil.blankToDefault(
                        systemConfigService.getValue("invitation_reward_credit"), "30"));

        // 奖励被邀请人
        User invitee = userMapper.selectById(userId);
        if (invitee != null) {
            userMapper.updateCreditBalance(userId, rewardCredit, invitee.getCreditVersion());
            CreditTransaction inviteeTx = CreditTransaction.builder()
                    .userId(userId)
                    .type("RECHARGE")
                    .amount(rewardCredit)
                    .balanceAfter(invitee.getCreditBalance() + rewardCredit)
                    .remark("邀请奖励（被邀请）")
                    .build();
            creditTransactionMapper.insert(inviteeTx);
        }

        // 奖励邀请人
        User inviter = userMapper.selectById(invitation.getInviterId());
        if (inviter != null) {
            userMapper.updateCreditBalance(invitation.getInviterId(), rewardCredit, inviter.getCreditVersion());
            CreditTransaction inviterTx = CreditTransaction.builder()
                    .userId(invitation.getInviterId())
                    .type("RECHARGE")
                    .amount(rewardCredit)
                    .balanceAfter(inviter.getCreditBalance() + rewardCredit)
                    .remark("邀请奖励（邀请人）")
                    .build();
            creditTransactionMapper.insert(inviterTx);
        }

        // 标记已奖励
        invitation.setStatus("REWARDED");
        invitation.setRewardedAt(LocalDateTime.now());
        invitationMapper.updateById(invitation);

        log.info("Invitation reward granted: inviter={}, invitee={}, credit={}",
                invitation.getInviterId(), userId, rewardCredit);
        return rewardCredit;
    }

    /**
     * 失败自动退点。
     * <p>
     * 在生图任务失败后调用，退还已扣的创作点。
     *
     * @param userId     用户 ID
     * @param amount     退还数量
     * @param taskId     关联任务 ID
     */
    @Transactional
    public void refundFailedTask(Long userId, int amount, Long taskId) {
        if (userId == null || amount <= 0) return;

        User user = userMapper.selectById(userId);
        if (user == null) return;

        userMapper.updateCreditBalance(userId, amount, user.getCreditVersion());

        CreditTransaction tx = CreditTransaction.builder()
                .userId(userId)
                .type("REFUND")
                .amount(amount)
                .balanceAfter(user.getCreditBalance() + amount)
                .relatedId(taskId)
                .relatedType("WORKBENCH_TASK")
                .remark("生成失败退回")
                .build();
        creditTransactionMapper.insert(tx);

        log.info("Failed task refund: userId={}, amount={}, taskId={}", userId, amount, taskId);
    }
}
