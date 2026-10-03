"use client";

import { useEffect, useState, useCallback } from "react";
import { motion } from "framer-motion";
import {
  Coins,
  ArrowUp,
  ArrowDown,
  Hash,
  Loader2,
  Cpu,
  MessageSquare,
  Calendar,
  RefreshCw,
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  getTokenUsageSummary,
  getTokenUsageByModel,
  getTokenUsageTopConversations,
  getTokenUsageDailyTrend,
  type TokenUsageSummary,
  type TokenUsageByModel,
  type TokenUsageByConversation,
  type TokenUsageDailyTrend,
} from "@/lib/api/ai-assistant";

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.08 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.45, ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number] },
  },
};

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

function formatDate(s: string): string {
  try {
    const d = new Date(s);
    return d.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
  } catch {
    return s;
  }
}

const MODEL_COLORS = [
  "bg-blue-500",
  "bg-purple-500",
  "bg-indigo-500",
  "bg-amber-500",
  "bg-rose-500",
  "bg-green-500",
  "bg-indigo-500",
  "bg-pink-500",
];

export default function TokenUsagePage() {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [summary, setSummary] = useState<TokenUsageSummary | null>(null);
  const [byModel, setByModel] = useState<TokenUsageByModel[]>([]);
  const [topConversations, setTopConversations] = useState<TokenUsageByConversation[]>([]);
  const [dailyTrend, setDailyTrend] = useState<TokenUsageDailyTrend[]>([]);
  const [trendDays, setTrendDays] = useState(30);

  const fetchData = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const [s, m, c, d] = await Promise.all([
        getTokenUsageSummary(),
        getTokenUsageByModel(),
        getTokenUsageTopConversations(20),
        getTokenUsageDailyTrend(trendDays),
      ]);
      setSummary(s);
      setByModel(m);
      setTopConversations(c);
      setDailyTrend(d);
    } catch (err) {
      console.error("Failed to load token usage:", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [trendDays]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const maxDailyTokens = Math.max(...dailyTrend.map((d) => d.totalTokens), 1);
  const maxModelTokens = Math.max(...byModel.map((m) => m.totalTokens), 1);
  const totalModelTokens = byModel.reduce((sum, m) => sum + m.totalTokens, 0);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div
      className="max-w-[1200px]"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* Header */}
      <motion.div variants={itemVariants} className="mb-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
              <Coins className="h-6 w-6 text-amber-400" />
              Token 用量统计
            </h1>
            <p className="text-sm text-muted-foreground mt-1">
              查看 AI 对话的 Token 消耗情况
            </p>
          </div>
          <button
            onClick={() => fetchData(true)}
            disabled={refreshing}
            className={cn(
              "flex items-center gap-2 px-3 py-2 rounded-lg text-sm",
              "border border-border/40 bg-card/50 hover:bg-card/80 transition-colors",
              "text-muted-foreground hover:text-foreground"
            )}
          >
            <RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />
            刷新
          </button>
        </div>
      </motion.div>

      {/* Summary Cards */}
      <motion.div variants={itemVariants} className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatCard
          icon={<ArrowUp className="h-4 w-4" />}
          label="输入 Tokens"
          value={summary ? formatTokens(summary.totalInputTokens) : "0"}
          color="text-blue-400"
          bg="bg-blue-500/10"
        />
        <StatCard
          icon={<ArrowDown className="h-4 w-4" />}
          label="输出 Tokens"
          value={summary ? formatTokens(summary.totalOutputTokens) : "0"}
          color="text-green-400"
          bg="bg-green-500/10"
        />
        <StatCard
          icon={<Coins className="h-4 w-4" />}
          label="总 Tokens"
          value={summary ? formatTokens(summary.totalTokens) : "0"}
          color="text-amber-400"
          bg="bg-amber-500/10"
        />
        <StatCard
          icon={<Hash className="h-4 w-4" />}
          label="总请求次数"
          value={summary ? String(summary.totalRequests) : "0"}
          color="text-purple-400"
          bg="bg-purple-500/10"
        />
      </motion.div>

      {/* Daily Trend Chart */}
      <motion.div
        variants={itemVariants}
        className={cn(
          "rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-6 mb-6"
        )}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold flex items-center gap-2">
            <Calendar className="h-4 w-4 text-indigo-400" />
            每日 Token 用量趋势
          </h2>
          <div className="flex gap-1">
            {[7, 14, 30, 90].map((d) => (
              <button
                key={d}
                onClick={() => setTrendDays(d)}
                className={cn(
                  "px-2.5 py-1 rounded-md text-xs transition-colors",
                  trendDays === d
                    ? "bg-primary/15 text-primary font-medium"
                    : "text-muted-foreground hover:text-foreground hover:bg-foreground/5"
                )}
              >
                {d}天
              </button>
            ))}
          </div>
        </div>

        {dailyTrend.length === 0 ? (
          <div className="py-12 text-center text-sm text-muted-foreground">
            暂无数据
          </div>
        ) : (
          <div className="flex items-end gap-1 h-48 overflow-x-auto">
            {dailyTrend.map((d) => {
              const heightPct = (d.totalTokens / maxDailyTokens) * 100;
              return (
                <div
                  key={d.date}
                  className="flex-1 min-w-[20px] flex flex-col items-center gap-1 group"
                >
                  <div className="relative w-full flex-1 flex items-end">
                    <div
                      className="w-full bg-gradient-to-t from-amber-500/60 to-amber-400/80 rounded-t-sm transition-all duration-300 group-hover:from-amber-500 group-hover:to-amber-400"
                      style={{ height: `${Math.max(heightPct, 2)}%` }}
                    >
                      <div className="opacity-0 group-hover:opacity-100 transition-opacity absolute -top-8 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-md border border-border/40 bg-popover px-2 py-1 text-[10px] z-10">
                        {formatTokens(d.totalTokens)}
                      </div>
                    </div>
                  </div>
                  <span className="text-[9px] text-muted-foreground/60 whitespace-nowrap">
                    {formatDate(d.date)}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* By Model */}
        <motion.div
          variants={itemVariants}
          className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-6"
        >
          <h2 className="text-sm font-semibold flex items-center gap-2 mb-4">
            <Cpu className="h-4 w-4 text-purple-400" />
            按模型分组
          </h2>
          {byModel.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">暂无数据</div>
          ) : (
            <div className="space-y-3">
              {byModel.map((m, i) => {
                const pct = totalModelTokens > 0 ? (m.totalTokens / totalModelTokens) * 100 : 0;
                const barPct = (m.totalTokens / maxModelTokens) * 100;
                return (
                  <div key={m.modelCode}>
                    <div className="flex items-center justify-between text-xs mb-1">
                      <span className="font-medium truncate">{m.modelCode}</span>
                      <span className="text-muted-foreground ml-2 shrink-0">
                        {formatTokens(m.totalTokens)} ({pct.toFixed(1)}%)
                      </span>
                    </div>
                    <div className="h-2 rounded-full bg-foreground/5 overflow-hidden">
                      <div
                        className={cn(
                          "h-full rounded-full transition-all duration-500",
                          MODEL_COLORS[i % MODEL_COLORS.length]
                        )}
                        style={{ width: `${Math.max(barPct, 1)}%` }}
                      />
                    </div>
                    <div className="flex items-center gap-3 mt-1 text-[10px] text-muted-foreground/60">
                      <span>↑{formatTokens(m.totalInputTokens)}</span>
                      <span>↓{formatTokens(m.totalOutputTokens)}</span>
                      <span>{m.requestCount} 次请求</span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </motion.div>

        {/* Top Conversations */}
        <motion.div
          variants={itemVariants}
          className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-6"
        >
          <h2 className="text-sm font-semibold flex items-center gap-2 mb-4">
            <MessageSquare className="h-4 w-4 text-indigo-400" />
            Token 消耗 Top 对话
          </h2>
          {topConversations.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">暂无数据</div>
          ) : (
            <div className="space-y-2 max-h-[400px] overflow-y-auto">
              {topConversations.map((c, i) => (
                <div
                  key={c.conversationId}
                  className="flex items-center gap-3 p-2.5 rounded-lg hover:bg-foreground/3 transition-colors"
                >
                  <span
                    className={cn(
                      "shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-[10px] font-bold",
                      i < 3
                        ? "bg-amber-500/15 text-amber-500"
                        : "bg-foreground/5 text-muted-foreground"
                    )}
                  >
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="text-xs font-medium truncate">{c.title || "未命名对话"}</div>
                    <div className="flex items-center gap-2 mt-0.5 text-[10px] text-muted-foreground/60">
                      {c.category && <span>{c.category}</span>}
                      {c.agentType && <span>· {c.agentType}</span>}
                      <span>· {c.requestCount} 次</span>
                    </div>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="text-xs font-semibold text-amber-500">
                      {formatTokens(c.totalTokens)}
                    </div>
                    <div className="text-[10px] text-muted-foreground/50">
                      {c.lastUsedTime ? formatDate(c.lastUsedTime) : ""}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </motion.div>
      </div>
    </motion.div>
  );
}

function StatCard({
  icon,
  label,
  value,
  color,
  bg,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  color: string;
  bg: string;
}) {
  return (
    <div
      className={cn(
        "rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-4",
        "flex items-center gap-3"
      )}
    >
      <div className={cn("h-10 w-10 rounded-lg flex items-center justify-center shrink-0", bg, color)}>
        {icon}
      </div>
      <div className="min-w-0">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={cn("text-xl font-bold truncate", color)}>{value}</div>
      </div>
    </div>
  );
}
