"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { X, Loader2, CheckCircle2, QrCode, Wallet } from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import {
  listRechargePackages,
  createRechargeOrder,
  pollOrderStatus,
  type RechargePackage,
  type RechargeOrder,
} from "@/lib/api/payment";
import { useAuthStore } from "@/lib/store/auth-store";

interface RechargeDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess?: () => void;
}

export function RechargeDialog({
  open,
  onOpenChange,
  onSuccess,
}: RechargeDialogProps) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const [packages, setPackages] = useState<RechargePackage[]>([]);
  const [selectedPkg, setSelectedPkg] = useState<RechargePackage | null>(null);
  const [payChannel, setPayChannel] = useState<"alipay" | "wxpay">("wxpay");
  const [order, setOrder] = useState<RechargeOrder | null>(null);
  const [loading, setLoading] = useState(false);
  const [payStatus, setPayStatus] = useState<"idle" | "waiting" | "success">(
    "idle"
  );
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (open) {
      loadPackages();
    } else {
      reset();
    }
  }, [open]);

  useEffect(() => {
    return () => {
      if (pollTimerRef.current) clearInterval(pollTimerRef.current);
    };
  }, []);

  const loadPackages = async () => {
    try {
      const list = await listRechargePackages();
      setPackages(list);
      if (list.length > 0 && !selectedPkg) setSelectedPkg(list[0]);
    } catch (err) {
      toast.error("加载套餐失败");
    }
  };

  const reset = () => {
    setOrder(null);
    setPayStatus("idle");
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  const handlePay = useCallback(async () => {
    if (!selectedPkg) return;
    if (!isAuthenticated) {
      toast.error("请先登录后再充值");
      return;
    }

    setLoading(true);
    try {
      const newOrder = await createRechargeOrder(
        selectedPkg.amountCny,
        payChannel
      );
      setOrder(newOrder);
      setPayStatus("waiting");

      pollTimerRef.current = setInterval(async () => {
        try {
          const updated = await pollOrderStatus(newOrder.orderNo, 0, 0).catch(
            () => null
          );
          if (updated && updated.status === 1) {
            if (pollTimerRef.current) {
              clearInterval(pollTimerRef.current);
              pollTimerRef.current = null;
            }
            setPayStatus("success");
            toast.success("充值成功！创作点已到账");
            setTimeout(() => {
              onSuccess?.();
              onOpenChange(false);
            }, 1500);
          }
        } catch {
          // continue polling
        }
      }, 2000);

      setTimeout(() => {
        if (pollTimerRef.current) {
          clearInterval(pollTimerRef.current);
          pollTimerRef.current = null;
        }
      }, 300000);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "创建订单失败");
    } finally {
      setLoading(false);
    }
  }, [selectedPkg, payChannel, isAuthenticated, onSuccess, onOpenChange]);

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
          onClick={() => onOpenChange(false)}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="w-full max-w-md rounded-2xl border border-border/50 bg-card shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="flex items-center justify-between px-5 h-14 border-b border-border/50">
              <div className="flex items-center gap-2">
                <Wallet className="h-4 w-4 text-cyan-400" />
                <span className="text-sm font-bold">充值创作点</span>
              </div>
              <button
                onClick={() => onOpenChange(false)}
                className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Body */}
            <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
              {payStatus === "success" ? (
                <div className="flex flex-col items-center justify-center py-12">
                  <CheckCircle2 className="h-12 w-12 text-green-400 mb-3" />
                  <p className="text-sm font-medium">充值成功</p>
                  <p className="text-xs text-muted-foreground mt-1">
                    创作点已到账
                  </p>
                </div>
              ) : payStatus === "waiting" && order ? (
                <div className="flex flex-col items-center">
                  <div className="w-48 h-48 rounded-xl border border-border/50 bg-white flex items-center justify-center mb-3 relative overflow-hidden">
                    {order.qrcodeUrl ? (
                      <img
                        src={order.qrcodeUrl}
                        alt="支付二维码"
                        className="w-full h-full object-contain"
                      />
                    ) : (
                      <QrCode className="h-16 w-16 text-muted-foreground/30" />
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground mb-2">
                    请使用
                    {payChannel === "wxpay" ? "微信" : "支付宝"}
                    扫码支付，二维码 5 分钟内有效
                  </p>
                  <div className="flex items-center justify-between w-full px-3 py-2 rounded-lg bg-muted/30 text-xs text-muted-foreground">
                    <span>订单 {order.orderNo}</span>
                    <span>待支付 ¥{order.amountCny.toFixed(2)}</span>
                  </div>
                  <div className="flex items-center gap-1.5 mt-3 text-xs text-muted-foreground">
                    <Loader2 className="h-3 w-3 animate-spin" />
                    等待支付确认...
                  </div>
                </div>
              ) : (
                <>
                  {/* 套餐选择 */}
                  <div className="grid grid-cols-2 gap-2.5">
                    {packages.map((pkg) => (
                      <button
                        key={pkg.id}
                        onClick={() => setSelectedPkg(pkg)}
                        className={cn(
                          "rounded-xl border p-3.5 text-left transition-all",
                          selectedPkg?.id === pkg.id
                            ? "border-cyan-400/50 bg-cyan-500/5"
                            : "border-border/40 hover:border-border/80"
                        )}
                      >
                        <div className="text-lg font-extrabold">
                          ¥{pkg.amountCny}
                        </div>
                        <div className="text-xs text-muted-foreground mt-0.5">
                          {pkg.creditAmount} 创作点
                        </div>
                        {pkg.bonusCredit > 0 && (
                          <div className="text-[10px] text-green-400 mt-1">
                            额外赠送 {pkg.bonusCredit} 点
                          </div>
                        )}
                      </button>
                    ))}
                  </div>

                  {/* 支付方式 */}
                  <div className="flex gap-2.5">
                    <button
                      onClick={() => setPayChannel("wxpay")}
                      className={cn(
                        "flex-1 h-10 rounded-lg border flex items-center justify-center gap-1.5 text-xs transition-all",
                        payChannel === "wxpay"
                          ? "border-cyan-400/50 text-cyan-400 bg-cyan-500/5"
                          : "border-border/40 text-muted-foreground"
                      )}
                    >
                      微信扫码
                    </button>
                    <button
                      onClick={() => setPayChannel("alipay")}
                      className={cn(
                        "flex-1 h-10 rounded-lg border flex items-center justify-center gap-1.5 text-xs transition-all",
                        payChannel === "alipay"
                          ? "border-cyan-400/50 text-cyan-400 bg-cyan-500/5"
                          : "border-border/40 text-muted-foreground"
                      )}
                    >
                      支付宝扫码
                    </button>
                  </div>

                  {/* 充值按钮 */}
                  <button
                    onClick={handlePay}
                    disabled={!selectedPkg || loading || !isAuthenticated}
                    className={cn(
                      "w-full h-11 rounded-xl font-bold text-sm transition-all",
                      "bg-gradient-to-r from-cyan-400 to-violet-400 text-white",
                      "hover:brightness-110 disabled:opacity-50 disabled:cursor-not-allowed"
                    )}
                  >
                    {loading ? (
                      <span className="flex items-center justify-center gap-1.5">
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        创建订单中...
                      </span>
                    ) : !isAuthenticated ? (
                      "请先登录"
                    ) : (
                      `立即支付 ¥${selectedPkg?.amountCny ?? 0}`
                    )}
                  </button>

                  {!isAuthenticated && (
                    <p className="text-[11px] text-muted-foreground/60 text-center">
                      充值需要先登录账号
                    </p>
                  )}
                </>
              )}
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
