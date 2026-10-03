"use client";

import { useEffect, useState, useMemo } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/store/auth-store";
import { projectApi, type Project } from "@/lib/api/project";
import { assetApi, type Asset, type AssetPageResp } from "@/lib/api/asset";
import {
  FolderKanban,
  Images,
  Sparkles,
  Clock,
  Calendar,
  Film,
  Loader2,
  ArrowRight,
  Plus,
  Package,
  Users,
  MapPin,
  Wrench,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { resolveMediaUrl } from "@/lib/api/client";
import { SafeImage } from "@/components/ui/safe-image";
import { usePermissionStore } from "@/lib/store/permission-store";

// ============================================================
// 动画
// ============================================================

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

// ============================================================
// 配置
// ============================================================

const ASSET_TYPE_CONFIG: Record<string, { label: string; icon: typeof Users; color: string; bg: string }> = {
  character: { label: "角色", icon: Users, color: "text-blue-400", bg: "bg-blue-500/10" },
  scene: { label: "场景", icon: MapPin, color: "text-green-400", bg: "bg-green-500/10" },
  prop: { label: "道具", icon: Wrench, color: "text-amber-400", bg: "bg-amber-500/10" },
};

// ============================================================
// 工具
// ============================================================

function formatTime(iso: string) {
  const d = new Date(iso);
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "刚刚";
  if (mins < 60) return `${mins} 分钟前`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} 天前`;
  return d.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
}

function getGreeting() {
  const hour = new Date().getHours();
  if (hour < 6) return "夜深了";
  if (hour < 12) return "早上好";
  if (hour < 14) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
}

// ============================================================
// 页面
// ============================================================

export default function DashboardPage() {
  const user = useAuthStore((s) => s.user);
  const router = useRouter();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [assetData, setAssetData] = useState<AssetPageResp | null>(null);
  const canViewProjects = usePermissionStore((state) => state.hasPermission("project:view"));
  const canCreateProject = usePermissionStore((state) => state.hasPermission("project:create"));
  const canViewAssets = usePermissionStore((state) => state.hasPermission("asset:view"));

  useEffect(() => {
    (async () => {
      try {
        const [projectResult, assetResult] = await Promise.allSettled([
          canViewProjects ? projectApi.list() : Promise.resolve([]),
          canViewAssets ? assetApi.listAll({ page: 1, size: 10 }) : Promise.resolve(null),
        ]);
        if (projectResult.status === "fulfilled") setProjects(projectResult.value);
        if (assetResult.status === "fulfilled") setAssetData(assetResult.value);
      } catch (err) {
        console.error("加载仪表盘数据失败:", err);
      } finally {
        setLoading(false);
      }
    })();
  }, [canViewProjects, canViewAssets]);

  const recentProjects = useMemo(
    () =>
      [...projects]
        .sort((a, b) => new Date(b.updateTime).getTime() - new Date(a.updateTime).getTime())
        .slice(0, 6),
    [projects]
  );

  const totalAssetCount = useMemo(() => {
    if (!assetData?.typeCounts) return 0;
    return Object.values(assetData.typeCounts).reduce((s, c) => s + c, 0);
  }, [assetData]);

  const recentAssets = assetData?.records ?? [];

  // 近 7 天活跃
  const activityDots = useMemo(() => {
    const now = new Date();
    const result: { label: string; count: number }[] = [];
    for (let i = 6; i >= 0; i--) {
      const date = new Date(now);
      date.setDate(date.getDate() - i);
      const label = date.toLocaleDateString("zh-CN", { weekday: "short" }).replace("周", "");
      const count = projects.filter(
        (p) => new Date(p.updateTime).toDateString() === date.toDateString()
      ).length;
      result.push({ label, count });
    }
    return result;
  }, [projects]);

  const maxActivity = Math.max(...activityDots.map((d) => d.count), 1);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-32">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div
      className="pb-12"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* ========== Hero 问候区 ========== */}
      <motion.div
        variants={itemVariants}
        className="relative rounded-2xl p-8 mb-6 overflow-hidden border border-border/50 bg-[linear-gradient(135deg,rgba(124,92,255,0.12)_0%,rgba(0,229,255,0.06)_100%)] shadow-[0_8px_30px_-8px_rgba(0,0,0,0.25)]"
      >
        <div className="absolute -top-1/2 -right-[10%] w-[500px] h-[500px] bg-[radial-gradient(circle,rgba(124,92,255,0.18),transparent_70%)] pointer-events-none" />
        <div className="relative flex flex-col md:flex-row md:items-end md:justify-between gap-4">
          <div>
            <div className="flex items-center gap-1.5 mb-3 text-xs text-muted-foreground">
              <Calendar className="h-3.5 w-3.5" />
              {new Date().toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" })}
            </div>
            <h1 className="text-3xl font-extrabold tracking-tight mb-1.5">
              {getGreeting()}，
              <span className="bg-linear-to-r from-cyan-400 to-pink-400 bg-clip-text text-transparent">
                {user?.nickname || user?.username}
              </span>
            </h1>
            <p className="text-sm text-muted-foreground">
              欢迎回到织影，开启今天的创作
            </p>
          </div>
          {canCreateProject && (
            <button
              onClick={() => router.push("/projects")}
              className="shrink-0 inline-flex items-center gap-2 px-5 py-2.5 rounded-full bg-primary text-primary-foreground text-sm font-semibold shadow-[0_0_24px_rgba(0,229,255,0.18)] hover:shadow-[0_0_32px_rgba(0,229,255,0.28)] transition-all"
            >
              <Plus className="h-4 w-4" />
              新建项目
            </button>
          )}
        </div>
      </motion.div>

      {/* ========== 双栏布局（交换位置） ========== */}
      <div className="grid grid-cols-1 lg:grid-cols-[320px_1fr] gap-5">
        {/* 右侧：主要内容 */}
        <div className="order-2 lg:order-1 flex flex-col gap-4">
          {/* 活跃度 */}
          <motion.div variants={itemVariants} className="rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm p-4 transition-colors hover:border-border-hi shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]">
            <div className="flex items-center justify-between mb-1">
              <span className="text-sm font-bold">近 7 天活跃度</span>
              <span className="inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full bg-green-500/10 text-green-400">+23%</span>
            </div>
            <div className="flex items-end gap-1 h-10 mt-2.5">
              {activityDots.map((dot, i) => (
                <div key={i} className="flex-1 flex flex-col items-center">
                  <div
                    className={cn(
                      "w-full rounded-t-[3px] min-h-[4px]",
                      dot.count > 0 ? "bg-cyan-400" : "bg-muted/40"
                    )}
                    style={{
                      height: dot.count > 0
                        ? `${Math.max(20, (dot.count / maxActivity) * 100)}%`
                        : "4px",
                      opacity: dot.count > 0 ? 0.3 + (i / activityDots.length) * 0.7 : 1,
                    }}
                  />
                </div>
              ))}
            </div>
            <div className="flex items-center gap-1 mt-1.5">
              {activityDots.map((dot, i) => (
                <span key={i} className="flex-1 text-center text-[10px] text-muted-foreground/30 leading-none">
                  {dot.label}
                </span>
              ))}
            </div>
          </motion.div>

          {/* 最近动态 */}
          <motion.div variants={itemVariants} className="rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm p-4 transition-colors hover:border-border-hi shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]">
            <div className="text-sm font-bold mb-2.5">最近动态</div>
            <div className="divide-y divide-border">
              {recentProjects.slice(0, 5).map((proj, i) => {
                const colors = ["bg-cyan-400 text-cyan-400", "bg-green-400 text-green-400", "bg-amber-400 text-amber-400", "bg-violet-400 text-violet-400", "bg-pink-400 text-pink-400"];
                return (
                  <div key={proj.id} className="flex gap-2.5 py-3 first:pt-0 last:pb-0">
                    <div className={cn("w-2 h-2 rounded-full mt-1.5 shrink-0 shadow-[0_0_8px_currentColor]", colors[i % colors.length])} />
                    <div className="flex-1 min-w-0">
                      <p className="text-xs truncate">{proj.name}</p>
                      <p className="text-[11px] text-muted-foreground/40 mt-0.5">{formatTime(proj.updateTime)}</p>
                    </div>
                  </div>
                );
              })}
              {recentProjects.length === 0 && (
                <p className="text-xs text-muted-foreground/40 py-4 text-center">暂无动态</p>
              )}
            </div>
          </motion.div>
        </div>

        {/* 左侧：统计 + 快捷 + 项目 */}
        <div className="order-1 lg:order-2 flex flex-col gap-5">
          {/* 统计 */}
          <motion.div variants={itemVariants} className="grid grid-cols-3 gap-3.5">
            {canViewProjects && <StatCard
              label="项目"
              value={String(projects.length)}
              icon={Film}
              iconColor="text-cyan-400"
              iconBg="bg-cyan-500/10"
              onClick={() => router.push("/projects")}
            />}
            {canViewAssets && <StatCard
              label="素材资产"
              value={String(totalAssetCount)}
              icon={Package}
              iconColor="text-amber-400"
              iconBg="bg-amber-500/10"
              onClick={() => router.push("/assets")}
            />}
            <StatCard
              label="最近更新"
              value={recentProjects.length > 0 ? formatTime(recentProjects[0].updateTime) : "—"}
              icon={Clock}
              iconColor="text-violet-400"
              iconBg="bg-violet-500/10"
              small
            />
          </motion.div>

          {/* 快捷操作 */}
          <motion.div variants={itemVariants}>
            <div className="flex items-center justify-between mb-3.5">
              <span className="text-sm font-bold">快捷操作</span>
            </div>
            <div className="grid grid-cols-3 gap-3.5">
              {canCreateProject && <QuickAction
                icon={FolderKanban}
                label="新建项目"
                desc="开始全新的创作"
                color="text-cyan-400"
                bg="bg-cyan-500/10"
                onClick={() => router.push("/projects")}
              />}
              {canViewAssets && <QuickAction
                icon={Images}
                label="管理素材"
                desc="查看所有创作资产"
                color="text-amber-400"
                bg="bg-amber-500/10"
                onClick={() => router.push("/assets")}
              />}
              {canViewProjects && <QuickAction
                icon={Sparkles}
                label="AI 助手"
                desc="智能辅助创作"
                color="text-violet-400"
                bg="bg-violet-500/10"
                onClick={() => router.push("/projects")}
              />}
            </div>
          </motion.div>

          {/* 最近项目 */}
          {canViewProjects && <motion.div variants={itemVariants}>
            <SectionHeader
              title="最近项目"
              icon={<Film className="h-4 w-4 text-cyan-400" />}
              action={projects.length > 0 ? { label: "全部项目", onClick: () => router.push("/projects") } : undefined}
            />

            {recentProjects.length === 0 ? (
              <div
                onClick={() => router.push("/projects")}
                className={cn(
                  "rounded-2xl border border-dashed border-border/40 p-10",
                  "flex flex-col items-center justify-center text-center",
                  "bg-card/20 cursor-pointer hover:border-cyan-500/40 hover:bg-cyan-500/5 transition-all"
                )}
              >
                <div className="h-12 w-12 rounded-2xl bg-cyan-500/10 flex items-center justify-center mb-3">
                  <Plus className="h-6 w-6 text-cyan-400" />
                </div>
                <p className="text-sm font-medium mb-0.5">创建你的第一个项目</p>
                <p className="text-xs text-muted-foreground">
                  点击此处开始创建项目
                </p>
              </div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
                {recentProjects.map((proj) => (
                  <div
                    key={proj.id}
                    onClick={() => router.push(`/projects/${proj.id}`)}
                    className="group relative rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm p-4 cursor-pointer overflow-hidden transition-all hover:border-primary/30 hover:bg-card/80 hover:-translate-y-0.5 shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]"
                  >
                    <div className="flex items-start gap-3.5">
                      {proj.coverUrl ? (
                        <SafeImage
                          src={resolveMediaUrl(proj.coverUrl)}
                          alt={proj.name}
                          className="h-16 w-16 rounded-xl object-cover shrink-0 bg-muted/5"
                          fallbackType="image"
                        />
                      ) : (
                        <div className="h-16 w-16 rounded-xl bg-cyan-500/10 flex items-center justify-center shrink-0">
                          <Film className="h-6 w-6 text-cyan-400" />
                        </div>
                      )}
                      <div className="flex-1 min-w-0 pt-0.5">
                        <p className="text-[15px] font-bold truncate group-hover:text-cyan-400 transition-colors">
                          {proj.name}
                        </p>
                        <p className="text-xs text-muted-foreground/70 truncate mt-1">
                          {proj.description || "暂无描述"}
                        </p>
                        <div className="flex items-center gap-2 mt-2.5">
                          <span className="inline-flex items-center text-[10px] font-semibold px-2 py-0.5 rounded-full bg-cyan-500/10 text-cyan-400 shrink-0">
                            进行中
                          </span>
                          <span className="text-[10px] text-muted-foreground/40 tabular-nums">
                            {formatTime(proj.updateTime)}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </motion.div>}
        </div>

        {/* 右栏（隐藏） */}
        <div className="hidden flex-col gap-4">
          {/* 活跃度 — mockup .card + .mini-bars */}
          <motion.div variants={itemVariants} className="rounded-xl border border-border bg-card p-4 transition-colors hover:border-border-hi">
            <div className="flex items-center justify-between mb-1">
              <span className="text-sm font-bold">近 7 天活跃度</span>
              <span className="inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full bg-green-500/10 text-green-400">+23%</span>
            </div>
            <div className="flex items-end gap-1 h-10 mt-2.5">
              {activityDots.map((dot, i) => (
                <div key={i} className="flex-1 flex flex-col items-center">
                  <div
                    className={cn(
                      "w-full rounded-t-[3px] min-h-[4px]",
                      dot.count > 0 ? "bg-cyan-400" : "bg-muted/40"
                    )}
                    style={{
                      height: dot.count > 0
                        ? `${Math.max(20, (dot.count / maxActivity) * 100)}%`
                        : "4px",
                      opacity: dot.count > 0 ? 0.3 + (i / activityDots.length) * 0.7 : 1,
                    }}
                  />
                </div>
              ))}
            </div>
            <div className="flex items-center gap-1 mt-1.5">
              {activityDots.map((dot, i) => (
                <span key={i} className="flex-1 text-center text-[10px] text-muted-foreground/30 leading-none">
                  {dot.label}
                </span>
              ))}
            </div>
          </motion.div>

          {/* 最近动态 — mockup .card + .activity-item */}
          <motion.div variants={itemVariants} className="rounded-xl border border-border bg-card p-4 transition-colors hover:border-border-hi">
            <div className="text-sm font-bold mb-2.5">最近动态</div>
            <div className="divide-y divide-border">
              {recentProjects.slice(0, 5).map((proj, i) => {
                const colors = ["bg-cyan-400 text-cyan-400", "bg-green-400 text-green-400", "bg-amber-400 text-amber-400", "bg-violet-400 text-violet-400", "bg-pink-400 text-pink-400"];
                return (
                  <div key={proj.id} className="flex gap-2.5 py-3 first:pt-0 last:pb-0">
                    <div className={cn("w-2 h-2 rounded-full mt-1.5 shrink-0 shadow-[0_0_8px_currentColor]", colors[i % colors.length])} />
                    <div className="flex-1 min-w-0">
                      <p className="text-xs truncate">{proj.name}</p>
                      <p className="text-[11px] text-muted-foreground/40 mt-0.5">{formatTime(proj.updateTime)}</p>
                    </div>
                  </div>
                );
              })}
              {recentProjects.length === 0 && (
                <p className="text-xs text-muted-foreground/40 py-4 text-center">暂无动态</p>
              )}
            </div>
          </motion.div>
        </div>
      </div>

      {/* ========== 最近资产 ========== */}
      {recentAssets.length > 0 && (
        <motion.div variants={itemVariants} className="mt-5">
          <SectionHeader
            title="最近资产"
            icon={<Images className="h-4 w-4 text-amber-400" />}
            action={{ label: "全部资产", onClick: () => router.push("/assets") }}
          />

          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
            {recentAssets.map((asset) => (
              <AssetCard key={asset.id} asset={asset} onClick={() =>
                router.push(`/projects/${asset.projectId}/assets?highlight=${asset.id}`)
              } />
            ))}
          </div>
        </motion.div>
      )}
    </motion.div>
  );
}

// ============================================================
// 子组件
// ============================================================

/** 统计卡片 — 玻璃数字卡 */
function StatCard({
  label,
  value,
  icon: Icon,
  iconColor,
  iconBg,
  onClick,
  small,
}: {
  label: string;
  value: string;
  icon: typeof Film;
  iconColor: string;
  iconBg: string;
  onClick?: () => void;
  small?: boolean;
}) {
  return (
    <div
      onClick={onClick}
      className={cn(
        "rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm p-5",
        "flex flex-col justify-between gap-3 transition-all",
        "shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]",
        "hover:border-primary/30 hover:bg-card/80 hover:-translate-y-0.5",
        onClick && "cursor-pointer"
      )}
    >
      <div className={cn("h-9 w-9 rounded-[10px] flex items-center justify-center self-start", iconBg)}>
        <Icon className={cn("h-[18px] w-[18px]", iconColor)} />
      </div>
      <div>
        <div className={cn("font-extrabold leading-none tracking-tight", small ? "text-xl" : "text-3xl", iconColor)}>{value}</div>
        <div className="text-xs text-muted-foreground mt-1.5">{label}</div>
      </div>
    </div>
  );
}

/** 快捷操作 — 玻璃 Bento 卡 */
function QuickAction({
  icon: Icon,
  label,
  desc,
  color,
  bg,
  onClick,
}: {
  icon: typeof Film;
  label: string;
  desc: string;
  color: string;
  bg: string;
  onClick: () => void;
}) {
  return (
    <div
      onClick={onClick}
      className={cn(
        "rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm p-5",
        "flex flex-col gap-3 cursor-pointer transition-all",
        "shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]",
        "hover:border-primary/30 hover:bg-card/80 hover:-translate-y-0.5"
      )}
    >
      <div className={cn("h-10 w-10 rounded-[10px] flex items-center justify-center", bg)}>
        <Icon className={cn("h-5 w-5", color)} />
      </div>
      <div className="min-w-0">
        <p className="text-[15px] font-bold truncate">{label}</p>
        <p className="text-[12px] text-muted-foreground truncate mt-0.5">{desc}</p>
      </div>
    </div>
  );
}

/** Section 头 */
function SectionHeader({
  title,
  icon,
  action,
}: {
  title: string;
  icon: React.ReactNode;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex items-center justify-between mb-4">
      <h2 className="text-base font-bold flex items-center gap-2 text-foreground">
        {icon}
        {title}
      </h2>
      {action && (
        <button
          onClick={action.onClick}
          className="text-xs text-cyan-400 font-medium hover:text-cyan-300 flex items-center gap-0.5 transition-colors"
        >
          {action.label}
          <ArrowRight className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

/** 资产卡片 */
function AssetCard({ asset, onClick }: { asset: Asset; onClick: () => void }) {
  const coverSrc = resolveMediaUrl(asset.coverUrl);
  const typeConf = ASSET_TYPE_CONFIG[asset.type];
  const TypeIcon = typeConf?.icon || Package;

  return (
    <div
      onClick={onClick}
      className={cn(
        "group rounded-2xl border border-border/50 bg-card/60 backdrop-blur-sm",
        "cursor-pointer overflow-hidden",
        "hover:border-primary/30 hover:shadow-lg hover:shadow-black/5 transition-all duration-200"
      )}
    >
      <div className="aspect-[3/4] relative overflow-hidden bg-muted/5">
        <SafeImage
          src={coverSrc}
          fallbackType={
            asset.type === "character"
              ? "avatar"
              : asset.type === "scene"
              ? "scene"
              : asset.type === "prop"
              ? "prop"
              : "image"
          }
          alt={asset.name}
          className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
        />
        {coverSrc && (
          /* 底部渐变遮罩 */
          <div className="absolute inset-x-0 bottom-0 h-16 bg-linear-to-t from-black/70 to-transparent pointer-events-none" />
        )}

        {/* 类型标签 */}
        <div className={cn(
          "absolute top-1.5 left-1.5 flex items-center gap-0.5 px-1.5 py-0.5 rounded-md",
          "bg-black/40 backdrop-blur-sm text-white/80 text-[9px] font-medium"
        )}>
          <TypeIcon className="h-2.5 w-2.5" />
          {typeConf?.label || asset.type}
        </div>

        {/* 名称 */}
        <div className="absolute bottom-0 inset-x-0 px-2.5 pb-2">
          <p className="text-[11px] font-medium text-white truncate">{asset.name}</p>
        </div>
      </div>
    </div>
  );
}
