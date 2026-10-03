package com.tonyhe.fusion.controller.payment;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.workbench.RechargeOrder;
import com.tonyhe.fusion.entity.workbench.RechargePackage;
import com.tonyhe.fusion.mapper.workbench.RechargePackageMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.payment.PaymentService;
import com.tonyhe.fusion.service.system.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "支付")
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final RechargePackageMapper rechargePackageMapper;
    private final UserService userService;

    @GetMapping("/recharge-packages")
    @Operation(summary = "获取充值套餐列表")
    public CommonResult<List<RechargePackage>> listPackages() {
        return success(rechargePackageMapper.selectList(
                new LambdaQueryWrapper<RechargePackage>()
                        .eq(RechargePackage::getStatus, 1)
                        .orderByAsc(RechargePackage::getSort)));
    }

    @PostMapping("/recharge-orders")
    @Operation(summary = "创建充值订单")
    public CommonResult<RechargeOrder> createOrder(@Valid @RequestBody CreateOrderReqVO reqVO) {
        Long userId = SecurityUtils.requireCurrentUserId();
        RechargeOrder order = paymentService.createOrder(userId, reqVO.getAmountCny(), reqVO.getPayChannel());
        return success(order);
    }

    @GetMapping("/recharge-orders/{orderNo}")
    @Operation(summary = "查询订单状态")
    public CommonResult<RechargeOrder> getOrder(@PathVariable String orderNo) {
        Long userId = SecurityUtils.requireCurrentUserId();
        RechargeOrder order = paymentService.getOrderByNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(404, "订单不存在");
        }
        return success(order);
    }

    @PostMapping("/webhook/vmq")
    @Operation(summary = "V免签回调")
    public CommonResult<String> vmqCallback(HttpServletRequest request) {
        String payId = request.getParameter("payId");
        String param = request.getParameter("param");
        String type = request.getParameter("type");
        String price = request.getParameter("price");
        String reallyPrice = request.getParameter("reallyPrice");
        String sign = request.getParameter("sign");

        log.info("VMQ callback received: payId={}, type={}, price={}, reallyPrice={}",
                payId, type, price, reallyPrice);

        if (payId == null || sign == null) {
            return CommonResult.error(400, "缺少必要参数");
        }

        boolean success = paymentService.handleVmqCallback(payId, param, type, price, reallyPrice, sign);
        return success ? success("OK") : CommonResult.error(400, "处理失败");
    }

    @PostMapping("/admin/recharge-orders/{orderNo}/confirm")
    @Operation(summary = "管理员手动补单")
    public CommonResult<Boolean> adminConfirm(@PathVariable String orderNo) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userService.isAdmin(userId)) {
            throw new BusinessException(403, "仅管理员可操作");
        }
        return success(paymentService.adminConfirmOrder(orderNo));
    }

    @GetMapping("/admin/recharge-orders/wait-pay")
    @Operation(summary = "待支付订单列表（管理员）")
    public CommonResult<List<RechargeOrder>> waitPayOrders() {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userService.isAdmin(userId)) {
            throw new BusinessException(403, "仅管理员可操作");
        }
        return success(paymentService.getWaitPayOrders());
    }

    @Data
    public static class CreateOrderReqVO {
        @NotNull(message = "金额不能为空")
        private BigDecimal amountCny;

        @NotBlank(message = "支付渠道不能为空")
        private String payChannel;
    }
}
