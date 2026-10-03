"use client";

import { useState, useEffect, useCallback } from "react";
import { motion } from "framer-motion";
import { Loader2, TrendingUp, DollarSign, Image as ImageIcon, RotateCcw } from "lucide-react";
import { toast } from "sonner";
import {
  getReportOverview,
  getReportTransactions,
  type ReportOverview,
  type CreditTransaction,
} from "@/lib/api/admin-workbench";
import { containerVariants, itemVariants } from "../../_shared";

export default function ReportsPage() {
  const [overview, setOverview] = useState<ReportOverview | null>(null);
  const [transactions, setTransactions] = useState<CreditTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [ov, txns] = await Promise.all([
        getReportOverview(from || undefined, to || undefined),
        getReportTransactions({ pageNo: 1, pageSize: 50 }),
      ]);
      setOverview(ov);
      setTransactions(txns.list);
    } catch {
      toast.error("加载报表失败");
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const stats = [
    {
      label: "充值总额",
      value: overview ? `¥${overview.totalRechargeCny.toFixed(2)}` : "-",
      sub: overview ? `${overview.paidOrderCount} 笔订单` : "",
      icon: DollarSign,
      color: "text-green-400",
      bg: "bg-green-500/10",
    },
    {
      label: "充值创作点",
      value: overview ? `${overview.totalRechargeCredit}` : "-",
      sub: "累计到账",
      icon: TrendingUp,
      color: "text-cyan-400",
      bg: "bg-cyan-500/10",
    },
    {
      label: "生成任务",
      value: overview ? `${overview.totalTasks}` : "-",
      sub: overview
        ? `成功 ${overview.successTasks} / 失败 ${overview.failedTasks}`
        : "",
      icon: ImageIcon,
      color: "text-violet-400",
      bg: "bg-violet-500/10",
    },
    {
      label: "退点总额",
      value: overview ? `${overview.totalRefundCredit}` : "-",
      sub: "失败退点",
      icon: RotateCcw,
      color: "text-amber-400",
      bg: "bg-amber-500/10",
    },
  ];

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="space-y-6"
    >
      <motion.div variants={itemVariants}>
        <h1 className="text-xl font-bold">对账报表</h1>
        <p className="text-sm text-muted-foreground mt-1">
          充值、生成、退点运营数据概览
        </p>
      </motion.div>

      {/* 日期筛选 */}
      <motion.div variants={itemVariants} className="flex items-center gap-3">
        <input
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          className="bg-background border border-border/40 rounded-lg px-3 py-2 text-sm"
        />
        <span className="text-muted-foreground text-sm">至</span>
        <input
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          className="bg-background border border-border/40 rounded-lg px-3 py-2 text-sm"
        />
        <button
          onClick={loadData}
          className="px-3 h-9 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
        >
          查询
        </button>
      </motion.div>

      {/* 统计卡片 */}
      <motion.div variants={itemVariants} className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat) => (
          <div
            key={stat.label}
            className="rounded-xl border border-border/40 p-4 bg-card/40"
          >
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs text-muted-foreground">{stat.label}</span>
              <div className={`p-1.5 rounded-lg ${stat.bg}`}>
                <stat.icon className={`h-4 w-4 ${stat.color}`} />
              </div>
            </div>
            <div className="text-2xl font-bold">{stat.value}</div>
            {stat.sub && (
              <div className="text-xs text-muted-foreground mt-1">{stat.sub}</div>
            )}
          </div>
        ))}
      </motion.div>

      {/* 概览详情 */}
      {overview && (
        <motion.div variants={itemVariants} className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {/* 生成统计 */}
          <div className="rounded-xl border border-border/40 p-5 bg-card/40">
            <h3 className="text-sm font-bold mb-4">生成统计</h3>
            <div className="space-y-3">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">总任务数</span>
                <span className="font-medium">{overview.totalTasks}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">成功任务</span>
                <span className="font-medium text-green-400">{overview.successTasks}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">失败任务</span>
                <span className="font-medium text-red-400">{overview.failedTasks}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">免费任务</span>
                <span className="font-medium">{overview.freeTasks}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">付费任务</span>
                <span className="font-medium">{overview.paidTasks}</span>
              </div>
              <div className="flex justify-between text-sm pt-2 border-t border-border/40">
                <span className="text-muted-foreground">成功率</span>
                <span className="font-bold text-cyan-400">
                  {overview.successRate.toFixed(1)}%
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">免费转付费率</span>
                <span className="font-bold text-violet-400">
                  {overview.freeConversionRate.toFixed(1)}%
                </span>
              </div>
            </div>
          </div>

          {/* 模型分布 */}
          <div className="rounded-xl border border-border/40 p-5 bg-card/40">
            <h3 className="text-sm font-bold mb-4">模型分布</h3>
            <div className="space-y-3">
              {Object.entries(overview.modelStats || {}).map(([modelId, stat]) => (
                <div key={modelId} className="space-y-1">
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">模型 #{modelId}</span>
                    <span className="font-medium">
                      {stat.total} 次 / 成功 {stat.success}
                    </span>
                  </div>
                  <div className="h-2 rounded-full bg-muted/30 overflow-hidden">
                    <div
                      className="h-full bg-gradient-to-r from-cyan-400 to-violet-400 transition-all"
                      style={{
                        width: `${
                          overview.totalTasks > 0
                            ? (stat.total / overview.totalTasks) * 100
                            : 0
                        }%`,
                      }}
                    />
                  </div>
                  <div className="flex justify-between text-xs text-muted-foreground">
                    <span>免费 {stat.free} / 付费 {stat.total - stat.free}</span>
                    <span>消耗 {stat.paidCredit} 创作点</span>
                  </div>
                </div>
              ))}
              {Object.keys(overview.modelStats || {}).length === 0 && (
                <p className="text-sm text-muted-foreground text-center py-4">
                  暂无数据
                </p>
              )}
            </div>
          </div>
        </motion.div>
      )}

      {/* 创作点流水 */}
      <motion.div variants={itemVariants} className="rounded-xl border border-border/40 overflow-hidden">
        <div className="px-4 py-3 border-b border-border/40">
          <h3 className="text-sm font-bold">创作点流水明细</h3>
        </div>
        <table className="w-full text-sm">
          <thead className="bg-muted/30 text-xs text-muted-foreground">
            <tr>
              <th className="text-left px-4 py-3 font-medium">ID</th>
              <th className="text-left px-4 py-3 font-medium">用户ID</th>
              <th className="text-left px-4 py-3 font-medium">类型</th>
              <th className="text-left px-4 py-3 font-medium">变动</th>
              <th className="text-left px-4 py-3 font-medium">余额</th>
              <th className="text-left px-4 py-3 font-medium">备注</th>
              <th className="text-left px-4 py-3 font-medium">时间</th>
            </tr>
          </thead>
          <tbody>
            {transactions.map((tx) => (
              <tr key={tx.id} className="border-t border-border/40 hover:bg-muted/20">
                <td className="px-4 py-3 text-muted-foreground text-xs">{tx.id}</td>
                <td className="px-4 py-3 text-xs">{tx.userId}</td>
                <td className="px-4 py-3">
                  <span
                    className={`text-xs px-1.5 py-0.5 rounded ${
                      tx.type === "RECHARGE"
                        ? "bg-green-500/10 text-green-400"
                        : tx.type === "DEDUCT"
                        ? "bg-amber-500/10 text-amber-400"
                        : tx.type === "REFUND"
                        ? "bg-cyan-500/10 text-cyan-400"
                        : "bg-muted/40 text-muted-foreground"
                    }`}
                  >
                    {tx.type}
                  </span>
                </td>
                <td
                  className={`px-4 py-3 font-medium text-xs ${
                    tx.amount > 0 ? "text-green-400" : "text-red-400"
                  }`}
                >
                  {tx.amount > 0 ? "+" : ""}
                  {tx.amount}
                </td>
                <td className="px-4 py-3 text-xs">{tx.balanceAfter}</td>
                <td className="px-4 py-3 text-muted-foreground text-xs">
                  {tx.remark || "-"}
                </td>
                <td className="px-4 py-3 text-muted-foreground text-xs">
                  {tx.createTime || "-"}
                </td>
              </tr>
            ))}
            {transactions.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-muted-foreground">
                  暂无流水记录
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </motion.div>
    </motion.div>
  );
}
