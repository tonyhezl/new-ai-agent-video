"use client";

import { useState, useEffect, useCallback, useRef, useMemo } from "react";
import { useRouter } from "next/navigation";
import {
  Loader2,
  Film,
  ImageIcon,
  Trash2,
  X,
  CheckSquare,
  Square,
  Play,
  FolderKanban,
  Clapperboard,
  Layers,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { mediaApi, type MediaEntry } from "@/lib/api/media";
import { projectApi, type Project } from "@/lib/api/project";
import { resolveMediaUrl } from "@/lib/api/client";
import { SafeImage } from "@/components/ui/safe-image";

const PAGE_SIZE = 60;

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.04, delayChildren: 0.05 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.3, ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number] },
  },
};

export default function MediaLibraryPage() {
  const router = useRouter();

  // 数据
  const [entries, setEntries] = useState<MediaEntry[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);

  // 筛选
  const [selectedProjectId, setSelectedProjectId] = useState<string>("all");
  const [selectedMediaType, setSelectedMediaType] = useState<string>("all");
  const [selectedSource, setSelectedSource] = useState<string>("all");

  // 勾选
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // 预览
  const [previewEntry, setPreviewEntry] = useState<MediaEntry | null>(null);

  // 删除确认
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const totalPages = Math.ceil(total / PAGE_SIZE);

  // 加载项目列表
  useEffect(() => {
    projectApi.list().then(setProjects).catch(() => setProjects([]));
  }, []);

  const buildParams = useCallback(
    (pageNum: number) => {
      const params: {
        projectId?: number;
        mediaType?: "image" | "video";
        source?: "storyboard" | "asset";
        page: number;
        size: number;
      } = {
        page: pageNum,
        size: PAGE_SIZE,
      };
      if (selectedProjectId !== "all") params.projectId = Number(selectedProjectId);
      if (selectedMediaType !== "all") params.mediaType = selectedMediaType as "image" | "video";
      if (selectedSource !== "all") params.source = selectedSource as "storyboard" | "asset";
      return params;
    },
    [selectedProjectId, selectedMediaType, selectedSource],
  );

  const loadPage = useCallback(async (pageNum: number) => {
    setLoading(true);
    setSelectedIds(new Set());
    try {
      const resp = await mediaApi.list(buildParams(pageNum));
      setEntries(resp.records || []);
      setTotal(resp.total);
      setCurrentPage(pageNum);
    } catch {
      setEntries([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [buildParams]);

  useEffect(() => {
    loadPage(1);
  }, [loadPage]);

  // 勾选
  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedIds.size === entries.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(entries.map((e) => e.id)));
    }
  };

  // 批量删除
  const handleBatchClear = async () => {
    setDeleting(true);
    try {
      const toClear = entries
        .filter((e) => selectedIds.has(e.id))
        .map((e) => ({ source: e.source, entityId: e.entityId, field: e.field }));
      const count = await mediaApi.batchClear(toClear);
      setShowDeleteConfirm(false);
      setSelectedIds(new Set());
      // 重新加载当前页
      await loadPage(currentPage);
    } catch (err) {
      console.error("批量清除失败:", err);
    } finally {
      setDeleting(false);
    }
  };

  // 单个删除
  const handleSingleClear = async (entry: MediaEntry) => {
    if (!confirm(`确定要清除「${entry.label} - ${entry.subLabel}」的媒体吗？`)) return;
    try {
      await mediaApi.batchClear([
        { source: entry.source, entityId: entry.entityId, field: entry.field },
      ]);
      await loadPage(currentPage);
    } catch (err) {
      console.error("清除失败:", err);
    }
  };

  const sourceLabel = (s: string) =>
    s === "storyboard" ? "分镜" : s === "asset" ? "资产" : s;

  const mediaTypeIcon = (t: string) =>
    t === "video" ? <Film className="h-3.5 w-3.5" /> : <ImageIcon className="h-3.5 w-3.5" />;

  const projectMap = useMemo(() => {
    const map: Record<number, string> = {};
    projects.forEach((p) => { map[p.id] = p.name; });
    return map;
  }, [projects]);

  return (
    <div className="min-h-screen pb-20">
      {/* 标题区 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight">媒体库</h1>
        <p className="text-sm text-muted-foreground mt-1">
          浏览所有已生成的图片和视频，批量清除不需要的媒体
        </p>
      </div>

      {/* 筛选栏 */}
      <div className="flex flex-wrap items-center gap-3 mb-6">
        {/* 项目筛选 */}
        <select
          value={selectedProjectId}
          onChange={(e) => setSelectedProjectId(e.target.value)}
          className="h-9 rounded-lg border border-border/50 bg-background/60 px-3 text-sm outline-none focus:border-primary/50 transition-colors"
        >
          <option value="all">全部项目</option>
          {projects.map((p) => (
            <option key={p.id} value={String(p.id)}>{p.name}</option>
          ))}
        </select>

        {/* 媒体类型筛选 */}
        <div className="flex items-center gap-1 rounded-lg border border-border/50 bg-background/60 p-0.5">
          {[
            { value: "all", label: "全部", icon: <Layers className="h-3.5 w-3.5" /> },
            { value: "image", label: "图片", icon: <ImageIcon className="h-3.5 w-3.5" /> },
            { value: "video", label: "视频", icon: <Film className="h-3.5 w-3.5" /> },
          ].map((opt) => (
            <button
              key={opt.value}
              onClick={() => setSelectedMediaType(opt.value)}
              className={cn(
                "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors",
                selectedMediaType === opt.value
                  ? "bg-primary/10 text-primary font-medium"
                  : "text-muted-foreground hover:text-foreground hover:bg-foreground/5",
              )}
            >
              {opt.icon}
              {opt.label}
            </button>
          ))}
        </div>

        {/* 来源筛选 */}
        <div className="flex items-center gap-1 rounded-lg border border-border/50 bg-background/60 p-0.5">
          {[
            { value: "all", label: "全部来源", icon: <Layers className="h-3.5 w-3.5" /> },
            { value: "storyboard", label: "分镜", icon: <Clapperboard className="h-3.5 w-3.5" /> },
            { value: "asset", label: "资产", icon: <ImageIcon className="h-3.5 w-3.5" /> },
          ].map((opt) => (
            <button
              key={opt.value}
              onClick={() => setSelectedSource(opt.value)}
              className={cn(
                "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors",
                selectedSource === opt.value
                  ? "bg-primary/10 text-primary font-medium"
                  : "text-muted-foreground hover:text-foreground hover:bg-foreground/5",
              )}
            >
              {opt.icon}
              {opt.label}
            </button>
          ))}
        </div>

        {/* 统计 */}
        <div className="ml-auto flex items-center gap-3 text-sm text-muted-foreground">
          <span>共 {total} 条</span>
          {selectedIds.size > 0 && (
            <span className="text-primary font-medium">已选 {selectedIds.size}</span>
          )}
        </div>
      </div>

      {/* 批量操作栏 */}
      {selectedIds.size > 0 && (
        <div className="flex items-center gap-3 mb-4 px-4 py-2.5 rounded-xl border border-primary/20 bg-primary/5">
          <button
            onClick={toggleSelectAll}
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            {selectedIds.size === entries.length ? "取消全选" : "全选当前页"}
          </button>
          <div className="flex-1" />
          <button
            onClick={() => setShowDeleteConfirm(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 text-sm font-medium transition-colors"
          >
            <Trash2 className="h-4 w-4" />
            批量清除 ({selectedIds.size})
          </button>
          <button
            onClick={() => setSelectedIds(new Set())}
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            取消选择
          </button>
        </div>
      )}

      {/* 内容区 */}
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="h-8 w-8 text-primary animate-spin" />
        </div>
      ) : entries.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
          <ImageIcon className="h-12 w-12 mb-3 opacity-20" />
          <p className="text-sm">暂无媒体文件</p>
        </div>
      ) : (
        <>
          <motion.div
            variants={containerVariants}
            initial="hidden"
            animate="visible"
            className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3"
          >
            {entries.map((entry) => {
              const isSelected = selectedIds.has(entry.id);
              const resolvedUrl = resolveMediaUrl(entry.url) || "";
              const resolvedThumb = resolveMediaUrl(entry.thumbnailUrl) || resolvedUrl;

              return (
                <motion.div
                  key={entry.id}
                  variants={itemVariants}
                  className={cn(
                    "rounded-xl overflow-hidden cursor-pointer relative bg-card border transition-all duration-200 group flex flex-col",
                    isSelected
                      ? "border-2 border-primary ring-2 ring-primary/10"
                      : "border-border/50 hover:border-primary/40 hover:shadow-lg hover:-translate-y-0.5",
                  )}
                  onClick={() => setPreviewEntry(entry)}
                >
                  {/* 媒体区域 */}
                  <div className="aspect-video relative bg-muted/40 overflow-hidden shrink-0">
                    {entry.mediaType === "video" ? (
                      <>
                        <video
                          src={resolvedUrl}
                          className="w-full h-full object-cover"
                          muted
                          preload="metadata"
                          playsInline
                        />
                        <div className="absolute inset-0 flex items-center justify-center bg-black/20 group-hover:bg-black/30 transition-colors">
                          <Play className="h-7 w-7 text-white/90 fill-white/90" />
                        </div>
                      </>
                    ) : (
                      <SafeImage
                        src={resolvedThumb}
                        alt={entry.label}
                        fallbackType="image"
                        className="w-full h-full object-cover transition-transform group-hover:scale-105"
                      />
                    )}

                    {/* 勾选框 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleSelect(entry.id);
                      }}
                      className={cn(
                        "absolute top-2 left-2 z-10 rounded-md transition-all",
                        isSelected
                          ? "bg-primary text-primary-foreground opacity-100"
                          : "bg-black/40 text-white opacity-0 group-hover:opacity-100 hover:bg-black/60",
                      )}
                    >
                      {isSelected ? (
                        <CheckSquare className="h-5 w-5" />
                      ) : (
                        <Square className="h-5 w-5" />
                      )}
                    </button>

                    {/* 类型标签 */}
                    <div className="absolute top-2 right-2 flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-black/50 text-white text-[10px] backdrop-blur-sm">
                      {mediaTypeIcon(entry.mediaType)}
                      {sourceLabel(entry.source)}
                    </div>

                    {/* 单个删除按钮 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleSingleClear(entry);
                      }}
                      className={cn(
                        "absolute bottom-2 right-2 z-10 p-1.5 rounded-md bg-black/50 text-white",
                        "opacity-0 group-hover:opacity-100 hover:bg-red-500/80 transition-all",
                      )}
                      title="清除该媒体"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>

                  {/* 信息区 */}
                  <div className="p-2 flex-1 min-h-0">
                    <p className="text-xs font-medium truncate">{entry.label}</p>
                    <p className="text-[10px] text-muted-foreground truncate mt-0.5">
                      {entry.subLabel}
                    </p>
                    {entry.projectName && (
                      <p className="text-[10px] text-muted-foreground/60 truncate mt-0.5 flex items-center gap-0.5">
                        <FolderKanban className="h-2.5 w-2.5 shrink-0" />
                        {entry.projectName}
                      </p>
                    )}
                  </div>
                </motion.div>
              );
            })}
          </motion.div>

          {/* 分页 */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 mt-8">
              <button
                onClick={() => loadPage(currentPage - 1)}
                disabled={currentPage <= 1 || loading}
                className="flex items-center gap-1 px-3 py-2 rounded-lg border border-border/50 bg-background/60 text-sm hover:border-primary/40 hover:bg-primary/5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <ChevronLeft className="h-4 w-4" />
                上一页
              </button>
              <span className="text-sm text-muted-foreground px-3">
                {currentPage} / {totalPages}
              </span>
              <button
                onClick={() => loadPage(currentPage + 1)}
                disabled={currentPage >= totalPages || loading}
                className="flex items-center gap-1 px-3 py-2 rounded-lg border border-border/50 bg-background/60 text-sm hover:border-primary/40 hover:bg-primary/5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                下一页
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}
        </>
      )}

      {/* 预览弹窗 */}
      <AnimatePresence>
        {previewEntry && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm"
            onClick={() => setPreviewEntry(null)}
          >
            <motion.div
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              className="relative max-w-[90vw] max-h-[85vh] flex flex-col overflow-hidden rounded-2xl border border-border/30 bg-card shadow-2xl"
              onClick={(e) => e.stopPropagation()}
            >
              {/* Header */}
              <div className="flex items-center justify-between px-4 py-3 border-b border-border/20">
                <div className="min-w-0">
                  <p className="text-sm font-medium truncate">{previewEntry.label}</p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {previewEntry.subLabel} · {sourceLabel(previewEntry.source)} · {previewEntry.mediaType === "video" ? "视频" : "图片"}
                  </p>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={() => handleSingleClear(previewEntry)}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 text-xs font-medium transition-colors"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    清除
                  </button>
                  <button
                    onClick={() => setPreviewEntry(null)}
                    className="p-1.5 rounded-lg hover:bg-muted text-muted-foreground transition-colors"
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
              </div>

              {/* 媒体展示 */}
              <div className="flex-1 overflow-auto flex items-center justify-center p-4">
                {previewEntry.mediaType === "video" ? (
                  <video
                    src={resolveMediaUrl(previewEntry.url) || ""}
                    controls
                    autoPlay
                    className="max-w-full max-h-[70vh] rounded-lg"
                  />
                ) : (
                  <SafeImage
                    src={resolveMediaUrl(previewEntry.url) || ""}
                    alt={previewEntry.label}
                    fallbackType="image"
                    className="max-w-full max-h-[70vh] object-contain rounded-lg"
                  />
                )}
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 批量删除确认弹窗 */}
      <AnimatePresence>
        {showDeleteConfirm && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
            onClick={() => !deleting && setShowDeleteConfirm(false)}
          >
            <motion.div
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              className="relative w-[400px] max-w-[90vw] bg-card border border-border/30 rounded-2xl shadow-2xl p-6"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center gap-3 mb-4">
                <div className="h-10 w-10 rounded-xl bg-red-500/10 flex items-center justify-center shrink-0">
                  <Trash2 className="h-5 w-5 text-red-500" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">确认批量清除</h3>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    将清除 {selectedIds.size} 个媒体的 URL 引用
                  </p>
                </div>
              </div>
              <p className="text-xs text-muted-foreground mb-5 leading-relaxed">
                清除后，对应的图片/视频将不再显示在分镜或资产中。此操作不可撤销，但原始文件仍保留在存储中。
              </p>
              <div className="flex items-center justify-end gap-2">
                <button
                  onClick={() => setShowDeleteConfirm(false)}
                  disabled={deleting}
                  className="px-4 py-2 rounded-lg text-sm text-muted-foreground hover:text-foreground hover:bg-muted transition-colors disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleBatchClear}
                  disabled={deleting}
                  className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-red-500 text-white hover:bg-red-600 text-sm font-medium transition-colors disabled:opacity-50"
                >
                  {deleting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
                  {deleting ? "清除中..." : "确认清除"}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
