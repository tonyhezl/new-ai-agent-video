"use client";

import { useEffect, useState, useRef, useCallback, useMemo } from "react";
import { useParams, useSearchParams, useRouter } from "next/navigation";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import {
  Images,
  Plus,
  Search,
  Loader2,
  X,
  ChevronRight,
  Users,
  MapPin,
  Wrench,
  Package,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { assetApi, type Asset } from "@/lib/api/asset";
import { resolveMediaUrl } from "@/lib/api/client";
import AssetDetailPanel from "@/components/dashboard/asset-detail-sheet";
import { SafeImage } from "@/components/ui/safe-image";
import AssetTypePlaceholder from "@/components/dashboard/asset-type-placeholder";

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.1 },
  },
};

const typeIconMap: Record<string, { icon: typeof Users; color: string; gradient: string }> = {
  character: { icon: Users, color: "text-blue-400", gradient: "bg-gradient-to-r from-blue-400/60 via-cyan-400/60 to-violet-400/60" },
  scene: { icon: MapPin, color: "text-green-400", gradient: "bg-gradient-to-r from-green-400/60 via-emerald-400/60 to-teal-400/60" },
  prop: { icon: Wrench, color: "text-amber-400", gradient: "bg-gradient-to-r from-amber-400/60 via-orange-400/60 to-red-400/60" },
};

const typeLabelMap: Record<string, string> = {
  character: "角色",
  scene: "场景",
  prop: "道具",
};

export default function ProjectAssetsPage() {
  const params = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  const projectId = Number(params.id);
  const highlightId = searchParams.get("highlight");

  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeType] = useState<string | undefined>(undefined);
  const [search, setSearch] = useState("");
  const [expandedTypes, setExpandedTypes] = useState<Record<string, boolean>>({});
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null);

  // 选中资产（Sheet 打开）
  const [selectedAsset, setSelectedAsset] = useState<Asset | null>(null);
  // 创建模式
  const [isCreating, setIsCreating] = useState(false);

  const handleOpenCreate = () => {
    setSelectedAsset(null);
    setIsCreating(true);
  };

  const fetchData = useCallback(async (type?: string, keyword?: string) => {
    try {
      setLoading(true);
      const data = await assetApi.list(projectId, type, keyword || undefined);
      setAssets(data);
    } catch (err) {
      console.error("加载资产列表失败:", err);
    } finally {
      setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      fetchData(activeType, search);
    }, search ? 300 : 0);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, activeType, search]);

  // AI 工具执行后自动刷新（debounce）
  const assetsInvalidation = usePipelineStore((s) => s.invalidation.assets);
  const assetsInvRef = useRef(assetsInvalidation);
  const assetsRefreshDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (assetsInvRef.current === assetsInvalidation) return;
    assetsInvRef.current = assetsInvalidation;
    if (assetsRefreshDebounceRef.current) clearTimeout(assetsRefreshDebounceRef.current);
    assetsRefreshDebounceRef.current = setTimeout(() => {
      assetsRefreshDebounceRef.current = null;
      fetchData(activeType, search);
    }, 500);
  }, [assetsInvalidation, activeType, search, fetchData]);

  // URL highlight 参数驱动自动选中
  const highlightHandled = useRef(false);
  useEffect(() => {
    if (!highlightId || highlightHandled.current || loading || assets.length === 0) return;
    const target = assets.find((a) => String(a.id) === highlightId);
    if (target) {
      setSelectedAsset(target);
      highlightHandled.current = true;
      // 清除 URL 中的 highlight 参数，避免刷新重复触发
      router.replace(`/projects/${projectId}/assets`, { scroll: false });
    }
  }, [highlightId, loading, assets, router, projectId]);

  // 按类型分组
  const groupedAssets = useMemo(() => {
    const groups: { type: string; label: string; icon: typeof Users; color: string; gradient: string; items: Asset[] }[] = [];
    const typeOrder = ["character", "scene", "prop"];
    for (const type of typeOrder) {
      const filtered = assets.filter((a) => a.type === type);
      if (filtered.length > 0) {
        const config = typeIconMap[type];
        groups.push({
          type,
          label: typeLabelMap[type] || type,
          icon: config?.icon || Package,
          color: config?.color || "text-muted-foreground",
          gradient: config?.gradient || "",
          items: filtered,
        });
      }
    }
    // 未知类型
    const unknown = assets.filter((a) => !typeOrder.includes(a.type));
    if (unknown.length > 0) {
      groups.push({ type: "other", label: "其他", icon: Package, color: "text-muted-foreground", gradient: "", items: unknown });
    }
    return groups;
  }, [assets]);

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="h-full flex rounded-2xl border border-border/50 overflow-hidden bg-card/60 backdrop-blur-sm shadow-[0_8px_32px_-12px_rgba(0,0,0,0.35)]"
    >
      {/* ========== 左侧：树状资产列表 ========== */}
      <div className="w-[340px] shrink-0 flex flex-col border-r border-border/30 bg-card/40 backdrop-blur-sm">
        {/* 顶栏：搜索 + 新建 */}
        <div className="shrink-0 px-3 py-3 border-b border-border/30 space-y-2.5">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold flex items-center gap-1.5 shrink-0">
              <Images className="h-4 w-4 text-primary" />
              资产库
            </h2>
            <div className="flex-1" />
            <button
              onClick={handleOpenCreate}
              className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-all shrink-0"
            >
              <Plus className="h-3 w-3" />
              新建
            </button>
          </div>
          <div className={cn(
            "relative flex items-center gap-2 px-2.5 py-1.5 rounded-full",
            "border border-border/40 bg-white/5 dark:bg-white/[0.03] backdrop-blur-sm",
            "focus-within:border-primary/40 focus-within:ring-1 focus-within:ring-primary/20 transition-all"
          )}>
            <Search className="h-3 w-3 text-muted-foreground/50 shrink-0" />
            <input
              type="text"
              placeholder="搜索..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground/40 min-w-0"
            />
            {search && (
              <button onClick={() => setSearch("")} className="p-0.5 rounded text-muted-foreground/40 hover:text-foreground transition-colors shrink-0">
                <X className="h-2.5 w-2.5" />
              </button>
            )}
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
              <Images className="h-8 w-8 text-muted-foreground/20 mb-2" />
              <p className="text-muted-foreground/50 text-xs">
                {search ? "无匹配资产" : "暂无资产"}
              </p>
            </div>
          ) : (
            <div className="space-y-1">
              {groupedAssets.map((group) => {
                const isExpanded = expandedTypes[group.type] !== false;
                return (
                  <div key={group.type}>
                    {/* 类型节点 */}
                    <button
                      onClick={() => setExpandedTypes((prev) => ({ ...prev, [group.type]: !isExpanded }))}
                      className="w-full flex items-center gap-2 px-3 py-1.5 hover:bg-foreground/5 transition-colors group"
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
                    {/* 资产列表 */}
                    {isExpanded && (
                      <div className="ml-3 border-l border-border/20">
                        {group.items.map((asset) => {
                          const isSelected = selectedAsset?.id === asset.id;
                          return (
                            <button
                              key={asset.id}
                              onClick={() => { setIsCreating(false); setSelectedAsset(asset); }}
                              className={cn(
                                "w-full flex items-center gap-2 pl-3 pr-2 py-1.5 text-left transition-colors relative",
                                isSelected
                                  ? "bg-primary/10 text-primary"
                                  : "hover:bg-foreground/5 text-muted-foreground hover:text-foreground"
                              )}
                            >
                              {isSelected && <div className="absolute left-0 top-0 bottom-0 w-[2px] bg-primary" />}
                              {/* 缩略图 */}
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
                              <span className="text-sm truncate flex-1">{asset.name}</span>
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
      </div>

      {/* ========== 右侧：平铺详情面板 ========== */}
      <div className="flex-1 min-w-0 flex flex-col">
        {isCreating ? (
          <AssetDetailPanel
            key="create"
            isCreating
            projectId={projectId}
            onClose={() => setIsCreating(false)}
            onSaved={async () => { await fetchData(activeType); }}
            onCreated={async (created) => {
              setIsCreating(false);
              await fetchData(activeType);
              setSelectedAsset(created);
            }}
          />
        ) : selectedAsset ? (
          <AssetDetailPanel
            key={selectedAsset.id}
            asset={selectedAsset}
            onClose={() => setSelectedAsset(null)}
            onSaved={() => fetchData(activeType)}
            onDeleted={() => {
              setSelectedAsset(null);
              fetchData(activeType);
            }}
          />
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center text-center">
            <Images className="h-12 w-12 text-muted-foreground/15 mb-3" />
            <p className="text-sm text-muted-foreground/40">从左侧选择资产查看详情</p>
          </div>
        )}
      </div>
    </motion.div>
  );
}
