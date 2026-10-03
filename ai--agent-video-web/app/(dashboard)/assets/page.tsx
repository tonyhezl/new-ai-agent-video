"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useRouter } from "next/navigation";
import {
  Search,
  Loader2,
  Package,
  Users,
  MapPin,
  Wrench,
  ExternalLink,
  ChevronRight,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { assetApi, type Asset } from "@/lib/api/asset";
import { projectApi, type Project } from "@/lib/api/project";
import { resolveMediaUrl } from "@/lib/api/client";
import AssetTypePlaceholder from "@/components/dashboard/asset-type-placeholder";
import { SafeImage } from "@/components/ui/safe-image";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

// ============================================================
// 常量
// ============================================================

const PAGE_SIZE = 20;

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.1 },
  },
};

/** 资产类型配置 */
const ASSET_TYPES = [
  { value: "character", label: "角色", icon: Users, color: "text-blue-400", bg: "bg-blue-500/10", gradient: "bg-gradient-to-r from-blue-400/60 via-cyan-400/60 to-violet-400/60" },
  { value: "scene", label: "场景", icon: MapPin, color: "text-green-400", bg: "bg-green-500/10", gradient: "bg-gradient-to-r from-green-400/60 via-emerald-400/60 to-teal-400/60" },
  { value: "prop", label: "道具", icon: Wrench, color: "text-amber-400", bg: "bg-amber-500/10", gradient: "bg-gradient-to-r from-amber-400/60 via-orange-400/60 to-red-400/60" },
] as const;

const typeMap = Object.fromEntries(ASSET_TYPES.map((t) => [t.value, t]));

// ============================================================
// 页面
// ============================================================

export default function AssetsPage() {
  const router = useRouter();

  // 数据
  const [assets, setAssets] = useState<Asset[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [total, setTotal] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);
  const [typeCounts, setTypeCounts] = useState<Record<string, number>>({});

  // 筛选
  const [selectedProjectId, setSelectedProjectId] = useState<string>("all");
  const [selectedType, setSelectedType] = useState<string>("all");
  const [keyword, setKeyword] = useState("");

  // 搜索防抖
  const [debouncedKeyword, setDebouncedKeyword] = useState("");
  const debounceTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // 加载版本号：每次 loadFirstPage 递增，loadMore 完成时校验，防止竞态追加过期数据
  const loadVersionRef = useRef(0);

  useEffect(() => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => setDebouncedKeyword(keyword), 300);
    return () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); };
  }, [keyword]);

  // 滚动加载 sentinel
  const sentinelRef = useRef<HTMLDivElement>(null);

  // 是否还有更多
  const hasMore = assets.length < total;

  // 项目名称映射
  const projectMap = useMemo(() => {
    const map: Record<number, string> = {};
    projects.forEach((p) => { map[p.id] = p.name; });
    return map;
  }, [projects]);

  // 总资产数 = typeCounts 之和
  const totalAssetCount = useMemo(() => {
    return Object.values(typeCounts).reduce((sum, c) => sum + c, 0);
  }, [typeCounts]);

  // 加载项目列表
  useEffect(() => {
    projectApi.list().then(setProjects).catch(() => setProjects([]));
  }, []);

  // 加载第一页（筛选改变时重置）
  const loadFirstPage = useCallback(async () => {
    loadVersionRef.current += 1;
    setLoading(true);
    setAssets([]);
    setCurrentPage(1);
    try {
      const params: { projectId?: number; type?: string; keyword?: string; page: number; size: number } = {
        page: 1,
        size: PAGE_SIZE,
      };
      if (selectedProjectId !== "all") params.projectId = Number(selectedProjectId);
      if (selectedType !== "all") params.type = selectedType;
      if (debouncedKeyword.trim()) params.keyword = debouncedKeyword.trim();
      const resp = await assetApi.listAll(params);
      // 对单页结果也去重，防止后端返回重复 ID
      const seen = new Set<number>();
      const records = (resp.records || []).filter((a) => {
        if (seen.has(a.id)) return false;
        seen.add(a.id);
        return true;
      });
      setAssets(records);
      setTotal(resp.total);
      setTypeCounts(resp.typeCounts || {});
      setCurrentPage(1);
    } catch {
      setAssets([]);
      setTotal(0);
      setTypeCounts({});
    } finally {
      setLoading(false);
    }
  }, [selectedProjectId, selectedType, debouncedKeyword]);

  useEffect(() => {
    loadFirstPage();
  }, [loadFirstPage]);

  // 加载更多
  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    const version = loadVersionRef.current;
    setLoadingMore(true);
    try {
      const nextPage = currentPage + 1;
      const params: { projectId?: number; type?: string; keyword?: string; page: number; size: number } = {
        page: nextPage,
        size: PAGE_SIZE,
      };
      if (selectedProjectId !== "all") params.projectId = Number(selectedProjectId);
      if (selectedType !== "all") params.type = selectedType;
      if (debouncedKeyword.trim()) params.keyword = debouncedKeyword.trim();
      const resp = await assetApi.listAll(params);
      // 如果期间 loadFirstPage 被触发，丢弃本次过期结果
      if (version !== loadVersionRef.current) return;
      setAssets((prev) => {
        const existingIds = new Set(prev.map((a) => a.id));
        const newRecords = (resp.records || []).filter((a) => !existingIds.has(a.id));
        return [...prev, ...newRecords];
      });
      setTotal(resp.total);
      setCurrentPage(nextPage);
    } catch {
      // ignore
    } finally {
      if (version === loadVersionRef.current) setLoadingMore(false);
    }
  }, [loadingMore, hasMore, currentPage, selectedProjectId, selectedType, debouncedKeyword]);

  // IntersectionObserver 滚动加载
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !loading && !loadingMore) {
          loadMore();
        }
      },
      { rootMargin: "200px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loading, loadingMore, loadMore]);

  // 项目选择器选项
  const projectOptions = useMemo(() => [
    { value: "all", label: "全部项目" },
    ...projects.map((p) => ({ value: String(p.id), label: p.name })),
  ], [projects]);

  // 按类型分组
  const groupedAssets = useMemo(() => {
    const groups: { type: string; label: string; icon: typeof Users; color: string; gradient: string; items: Asset[] }[] = [];
    const typeOrder = ["character", "scene", "prop"];
    for (const type of typeOrder) {
      const filtered = assets.filter((a) => a.type === type);
      if (filtered.length > 0) {
        const config = typeMap[type];
        groups.push({
          type,
          label: config?.label || type,
          icon: config?.icon || Package,
          color: config?.color || "text-muted-foreground",
          gradient: config?.gradient || "",
          items: filtered,
        });
      }
    }
    const unknown = assets.filter((a) => !typeOrder.includes(a.type));
    if (unknown.length > 0) {
      groups.push({ type: "other", label: "其他", icon: Package, color: "text-muted-foreground", gradient: "", items: unknown });
    }
    return groups;
  }, [assets]);

  // 点击资产卡片
  const handleAssetClick = (asset: Asset) => {
    router.push(`/projects/${asset.projectId}/assets?highlight=${asset.id}`);
  };

  // 选中的预览资产
  const [previewAsset, setPreviewAsset] = useState<Asset | null>(null);
  const [expandedTypes, setExpandedTypes] = useState<Record<string, boolean>>({});

  return (
    <motion.div
      className="max-w-[1200px] h-full flex rounded-2xl border border-border/50 overflow-hidden bg-card/60 backdrop-blur-sm shadow-[0_8px_32px_-12px_rgba(0,0,0,0.35)]"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* ========== 左侧：树状资产列表 ========== */}
      <div className="w-[340px] shrink-0 flex flex-col border-r border-border/30 bg-card/40 backdrop-blur-sm">
        {/* 顶栏 */}
        <div className="shrink-0 px-3 py-3 border-b border-border/30 space-y-2.5">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold flex items-center gap-1.5 shrink-0">
              <Package className="h-4 w-4 text-primary" />
              素材资产
              <span className="text-[10px] text-muted-foreground font-normal">{totalAssetCount}</span>
            </h2>
            <div className="flex-1" />
            {/* 项目选择器 */}
            <Select
              value={selectedProjectId}
              onValueChange={(v) => setSelectedProjectId(v ?? "all")}
              items={projectOptions}
            >
              <SelectTrigger className="h-7 w-auto text-[10px] gap-1 px-2">
                <SelectValue placeholder="项目" />
              </SelectTrigger>
              <SelectContent className="text-xs">
                <SelectGroup>
                  {projectOptions.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value} className="text-xs">
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </div>
          {/* 搜索框 */}
          <div className={cn(
            "relative flex items-center gap-2 px-2.5 py-1.5 rounded-full",
            "border border-border/40 bg-white/5 dark:bg-white/[0.03] backdrop-blur-sm",
            "focus-within:border-primary/40 focus-within:ring-1 focus-within:ring-primary/20 transition-all"
          )}>
            <Search className="h-3 w-3 text-muted-foreground/50 shrink-0" />
            <input
              type="text"
              placeholder="搜索..."
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              className="flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground/40 min-w-0"
            />
          </div>
          {/* 类型 tab */}
          <div className="flex items-center gap-1 overflow-x-auto scrollbar-none">
            <button
              onClick={() => setSelectedType("all")}
              className={cn(
                "flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium transition-all whitespace-nowrap shrink-0",
                selectedType === "all"
                  ? "bg-primary/15 text-primary border border-primary/30"
                  : "text-muted-foreground hover:text-foreground hover:bg-foreground/5 border border-transparent"
              )}
            >
              全部
              <span className="text-[8px] opacity-50 tabular-nums">{totalAssetCount}</span>
            </button>
            {ASSET_TYPES.map((t) => {
              const Icon = t.icon;
              const count = typeCounts[t.value] || 0;
              return (
                <button
                  key={t.value}
                  onClick={() => setSelectedType(selectedType === t.value ? "all" : t.value)}
                  className={cn(
                    "flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium transition-all whitespace-nowrap shrink-0",
                    selectedType === t.value
                      ? "bg-primary/15 text-primary border border-primary/30"
                      : "text-muted-foreground hover:text-foreground hover:bg-foreground/5 border border-transparent"
                  )}
                >
                  <Icon className={cn("h-2.5 w-2.5", t.color)} />
                  {t.label}
                  {count > 0 && <span className="text-[8px] opacity-50 tabular-nums">{count}</span>}
                </button>
              );
            })}
          </div>
        </div>

        {/* 树状列表 */}
        <div className="flex-1 min-h-0 overflow-y-auto py-2">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground/40" />
            </div>
          ) : assets.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center px-4">
              <Package className="h-8 w-8 text-muted-foreground/20 mb-2" />
              <p className="text-muted-foreground/50 text-xs">
                {debouncedKeyword || selectedType !== "all" || selectedProjectId !== "all"
                  ? "无匹配资产" : "暂无资产"}
              </p>
            </div>
          ) : (
            <div className="space-y-1">
              {groupedAssets.map((group) => {
                const isExpanded = expandedTypes[group.type] !== false;
                return (
                  <div key={group.type}>
                    <button
                      onClick={() => setExpandedTypes((prev) => ({ ...prev, [group.type]: !isExpanded }))}
                      className="w-full flex items-center gap-2 px-3 py-1.5 hover:bg-foreground/5 transition-colors"
                    >
                      <div className={cn("h-6 w-6 rounded-md flex items-center justify-center shrink-0", "bg-white/5 dark:bg-white/[0.03]")}>
                        <group.icon className={cn("h-3.5 w-3.5", group.color)} />
                      </div>
                      <span className="text-sm font-semibold flex-1 text-left">{group.label}</span>
                      <span className="text-[10px] text-muted-foreground/40 tabular-nums">{group.items.length}</span>
                      <ChevronRight className={cn(
                        "h-3 w-3 text-muted-foreground/30 transition-transform shrink-0",
                        isExpanded && "rotate-90"
                      )} />
                    </button>
                    {isExpanded && (
                      <div className="ml-3 border-l border-border/20">
                        {group.items.map((asset) => {
                          const isSelected = previewAsset?.id === asset.id;
                          return (
                            <button
                              key={asset.id}
                              onClick={() => setPreviewAsset(asset)}
                              className={cn(
                                "w-full flex items-center gap-2 pl-3 pr-2 py-1.5 text-left transition-colors relative",
                                isSelected
                                  ? "bg-primary/10 text-primary"
                                  : "hover:bg-foreground/5 text-muted-foreground hover:text-foreground"
                              )}
                            >
                              {isSelected && <div className="absolute left-0 top-0 bottom-0 w-[2px] bg-primary" />}
                              <div className="h-9 w-9 rounded-lg overflow-hidden bg-muted/10 shrink-0">
                                {asset.coverUrl ? (
                                  <SafeImage
                                    src={resolveMediaUrl(asset.coverUrl) || undefined}
                                    alt={asset.name}
                                    className="w-full h-full object-cover"
                                    fallbackType={
                                      asset.type === "character" ? "avatar" :
                                      asset.type === "scene" ? "scene" :
                                      asset.type === "prop" ? "prop" : "image"
                                    }
                                  />
                                ) : (
                                  <AssetTypePlaceholder type={asset.type} className="w-full h-full" />
                                )}
                              </div>
                              <div className="flex-1 min-w-0">
                                <span className="text-sm truncate block">{asset.name}</span>
                                <span className="text-[10px] text-muted-foreground/40 truncate block">
                                  {projectMap[asset.projectId] || `项目 ${asset.projectId}`}
                                </span>
                              </div>
                            </button>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 滚动加载 sentinel */}
        {!loading && hasMore && (
          <div ref={sentinelRef} className="shrink-0 flex items-center justify-center py-3 border-t border-border/20">
            {loadingMore && (
              <div className="flex items-center gap-1.5 text-muted-foreground text-[10px]">
                <Loader2 className="h-3 w-3 animate-spin" />
                加载更多...
              </div>
            )}
          </div>
        )}
      </div>

      {/* ========== 右侧：资产预览 ========== */}
      <div className="flex-1 min-w-0 flex flex-col overflow-y-auto">
        {previewAsset ? (
          <div className="p-6 space-y-5">
            {/* 标题 + 跳转 */}
            <div className="flex items-center gap-3">
              <div className="flex-1 min-w-0">
                <h2 className="text-lg font-bold truncate">{previewAsset.name}</h2>
                <div className="flex items-center gap-2 mt-1">
                  <span className={cn(
                    "inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10px] font-medium",
                    typeMap[previewAsset.type]?.bg || "bg-muted/10",
                    typeMap[previewAsset.type]?.color || "text-muted-foreground"
                  )}>
                    {(() => {
                      const Icon = typeMap[previewAsset.type]?.icon || Package;
                      return <Icon className="h-2.5 w-2.5" />;
                    })()}
                    {typeMap[previewAsset.type]?.label || previewAsset.type}
                  </span>
                  <span className="text-[10px] text-muted-foreground">
                    {projectMap[previewAsset.projectId] || `项目 ${previewAsset.projectId}`}
                  </span>
                  <span className="text-[10px] text-muted-foreground/60">
                    {formatDate(previewAsset.updateTime)}
                  </span>
                </div>
              </div>
              <button
                onClick={() => handleAssetClick(previewAsset)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-all shrink-0"
              >
                <ExternalLink className="h-3 w-3" />
                跳转编辑
              </button>
            </div>

            {/* 平铺：封面左 + 信息右 */}
            <div className="flex gap-5">
              {/* 封面 */}
              <div className="w-[320px] shrink-0">
                <div className="aspect-square w-full rounded-xl border border-border/30 overflow-hidden bg-muted/5 relative">
                  {previewAsset.coverUrl ? (
                    <SafeImage
                      src={resolveMediaUrl(previewAsset.coverUrl) || undefined}
                      alt={previewAsset.name}
                      className="w-full h-full object-contain"
                      fallbackType={
                        previewAsset.type === "character" ? "avatar" :
                        previewAsset.type === "scene" ? "scene" :
                        previewAsset.type === "prop" ? "prop" : "image"
                      }
                    />
                  ) : (
                    <AssetTypePlaceholder type={previewAsset.type} className="w-full h-full" />
                  )}
                </div>
              </div>

              {/* 信息 */}
              <div className="flex-1 min-w-0 space-y-3">
                {previewAsset.description && (
                  <div>
                    <h4 className="text-[11px] font-semibold text-muted-foreground uppercase tracking-wider mb-1.5">描述</h4>
                    <p className="text-xs text-muted-foreground/70 leading-relaxed">{previewAsset.description}</p>
                  </div>
                )}
                {previewAsset.properties && (
                  <div>
                    <h4 className="text-[11px] font-semibold text-muted-foreground uppercase tracking-wider mb-1.5">属性</h4>
                    <div className="rounded-xl border border-border/40 bg-white/5 dark:bg-white/[0.03] backdrop-blur-sm p-3 grid grid-cols-2 gap-2">
                      {Object.entries(parsePropsPreview(previewAsset.properties)).map(([key, val]) => (
                        <div key={key} className="space-y-0.5">
                          <span className="text-[10px] text-muted-foreground/50">{key}</span>
                          <p className="text-xs truncate">{val || "—"}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center text-center">
            <Package className="h-12 w-12 text-muted-foreground/15 mb-3" />
            <p className="text-sm text-muted-foreground/40">从左侧选择资产查看预览</p>
          </div>
        )}
      </div>
    </motion.div>
  );
}

// ============================================================
// 工具函数
// ============================================================

function formatDate(dateStr: string): string {
  if (!dateStr) return "";
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  const diffHour = Math.floor(diffMs / 3600000);
  const diffDay = Math.floor(diffMs / 86400000);

  if (diffMin < 1) return "刚刚";
  if (diffMin < 60) return `${diffMin} 分钟前`;
  if (diffHour < 24) return `${diffHour} 小时前`;
  if (diffDay < 7) return `${diffDay} 天前`;
  return date.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
}

function parsePropsPreview(raw: Record<string, unknown> | null): Record<string, string> {
  if (!raw || typeof raw !== "object") return {};
  const result: Record<string, string> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (v !== null && v !== undefined && String(v).trim()) {
      result[k] = String(v);
    }
  }
  return result;
}
