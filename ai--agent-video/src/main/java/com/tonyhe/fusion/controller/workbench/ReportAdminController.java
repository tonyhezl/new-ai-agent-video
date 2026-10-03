package com.tonyhe.fusion.controller.workbench;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.workbench.CreditTransaction;
import com.tonyhe.fusion.entity.workbench.RechargeOrder;
import com.tonyhe.fusion.entity.workbench.WorkbenchImageTask;
import com.tonyhe.fusion.mapper.workbench.CreditTransactionMapper;
import com.tonyhe.fusion.mapper.workbench.RechargeOrderMapper;
import com.tonyhe.fusion.mapper.workbench.WorkbenchImageTaskMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.system.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "对账报表（管理员）")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportAdminController {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final CreditTransactionMapper creditTransactionMapper;
    private final WorkbenchImageTaskMapper taskMapper;
    private final UserService userService;

    @GetMapping("/overview")
    @Operation(summary = "运营概览：充值/生成/退点统计")
    public CommonResult<OverviewRespVO> overview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        requireAdmin();

        LocalDateTime fromTime = StrUtil.isNotBlank(from)
                ? LocalDateTime.parse(from + "T00:00:00") : LocalDateTime.now().minusDays(30);
        LocalDateTime toTime = StrUtil.isNotBlank(to)
                ? LocalDateTime.parse(to + "T23:59:59") : LocalDateTime.now();

        // 充值统计
        List<RechargeOrder> paidOrders = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getStatus, "PAID")
                        .ge(RechargeOrder::getPaidAt, fromTime)
                        .le(RechargeOrder::getPaidAt, toTime));

        BigDecimal totalRechargeCny = BigDecimal.ZERO;
        int totalRechargeCredit = 0;
        for (RechargeOrder order : paidOrders) {
            if (order.getAmountCny() != null) totalRechargeCny = totalRechargeCny.add(order.getAmountCny());
            totalRechargeCredit += order.getCreditAmount();
        }

        // 生成统计
        List<WorkbenchImageTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<WorkbenchImageTask>()
                        .ge(WorkbenchImageTask::getCreateTime, fromTime)
                        .le(WorkbenchImageTask::getCreateTime, toTime));

        int totalTasks = tasks.size();
        int successTasks = (int) tasks.stream().filter(t -> t.getStatus() == 2).count();
        int failedTasks = (int) tasks.stream().filter(t -> t.getStatus() == 3).count();
        int freeTasks = (int) tasks.stream().filter(t -> t.getIsFreeUsed() == 1).count();
        int paidTasks = totalTasks - freeTasks;

        // 退点统计
        List<CreditTransaction> refunds = creditTransactionMapper.selectList(
                new LambdaQueryWrapper<CreditTransaction>()
                        .eq(CreditTransaction::getType, "REFUND")
                        .ge(CreditTransaction::getCreateTime, fromTime)
                        .le(CreditTransaction::getCreateTime, toTime));
        int totalRefundCredit = refunds.stream().mapToInt(CreditTransaction::getAmount).sum();

        // 按模型统计
        Map<Long, ModelStat> modelStats = new HashMap<>();
        for (WorkbenchImageTask task : tasks) {
            ModelStat stat = modelStats.computeIfAbsent(task.getModelId(), k -> new ModelStat());
            stat.total++;
            if (task.getStatus() == 2) stat.success++;
            if (task.getIsFreeUsed() == 1) stat.free++;
            else stat.paidCredit += task.getCreditCost();
        }

        OverviewRespVO vo = new OverviewRespVO();
        vo.setTotalRechargeCny(totalRechargeCny);
        vo.setTotalRechargeCredit(totalRechargeCredit);
        vo.setPaidOrderCount(paidOrders.size());
        vo.setTotalTasks(totalTasks);
        vo.setSuccessTasks(successTasks);
        vo.setFailedTasks(failedTasks);
        vo.setFreeTasks(freeTasks);
        vo.setPaidTasks(paidTasks);
        vo.setTotalRefundCredit(totalRefundCredit);
        vo.setSuccessRate(totalTasks > 0 ? (double) successTasks / totalTasks * 100 : 0);
        vo.setFreeConversionRate(freeTasks > 0 ? (double) paidTasks / totalTasks * 100 : 0);
        vo.setModelStats(modelStats);

        return success(vo);
    }

    @GetMapping("/transactions")
    @Operation(summary = "创作点流水明细")
    public CommonResult<com.tonyhe.fusion.common.PageResult<CreditTransaction>> transactions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "50") int pageSize) {
        requireAdmin();
        LambdaQueryWrapper<CreditTransaction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(userId != null, CreditTransaction::getUserId, userId)
                .eq(StrUtil.isNotBlank(type), CreditTransaction::getType, type)
                .orderByDesc(CreditTransaction::getId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<CreditTransaction> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize);
        return success(com.tonyhe.fusion.common.PageResult.of(creditTransactionMapper.selectPage(page, wrapper)));
    }

    private void requireAdmin() {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userService.isAdmin(userId)) {
            throw new BusinessException(403, "仅管理员可操作");
        }
    }

    @Data
    public static class OverviewRespVO {
        private BigDecimal totalRechargeCny;
        private int totalRechargeCredit;
        private int paidOrderCount;
        private int totalTasks;
        private int successTasks;
        private int failedTasks;
        private int freeTasks;
        private int paidTasks;
        private int totalRefundCredit;
        private double successRate;
        private double freeConversionRate;
        private Map<Long, ModelStat> modelStats;
    }

    @Data
    public static class ModelStat {
        private int total = 0;
        private int success = 0;
        private int free = 0;
        private int paidCredit = 0;
    }
}
