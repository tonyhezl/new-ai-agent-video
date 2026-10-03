"use client";

import { useEffect, useState, useCallback, useMemo, memo } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  FileText,
  Clock,
  Sparkles,
  Trash2,
  Loader2,
  BookOpen,
  Eye,
  RefreshCw,
  Plus,
  Film,
  Type,
  Palette,
  Monitor,
  Images,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { scriptApi, type Script } from "@/lib/api/script";
import {
  storyboardApi,
  type Storyboard,
  type StoryboardEpisode,
} from "@/lib/api/storyboard";
import { assetApi, type Asset } from "@/lib/api/asset";
import { artStyleApi, type ArtStylePreset } from "@/lib/api/art-style";
import { resolveMediaUrl } from "@/lib/api/client";
import { SafeImage } from "@/components/ui/safe-image";
import { useProject } from "./project-context";
import { CreateScriptDialog } from "@/components/dashboard/create-script-dialog";
import { ParseScriptDialog } from "@/components/dashboard/parse-script-dialog";
import { usePipelineStore } from "@/lib/store/pipeline-store";

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.1 },
  },
};
const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.4, ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number] },
  },
};

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
  return d.toLocaleDateString("zh-CN");
}

const parsingStatusMap: Record<number, { label: string; cls: string }> = {
  0: { label: "待解析", cls: "text-muted-foreground bg-muted/50 border-border/30" },
  1: { label: "解析中", cls: "text-cyan-400 bg-cyan-500/10 border-cyan-500/20" },
  2: { label: "已完成", cls: "text-green-400 bg-green-500/10 border-green-500/20" },
  3: { label: "解析失败", cls: "text-destructive bg-destructive/10 border-destructive/20" },
};

const storyboardStatusMap: Record<number, { label: string; cls: string }> = {
  0: { label: "草稿", cls: "text-muted-foreground bg-muted/50 border-border/30" },
  1: { label: "进行中", cls: "text-cyan-400 bg-cyan-500/10 border-cyan-500/20" },
  2: { label: "已完成", cls: "text-green-400 bg-green-500/10 border-green-500/20" },
};

export default function ProjectOverviewPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = Number(params.id);
  const { project } = useProject();
  const properties = (project?.properties as Record<string, string>) || {};

  const [script, setScript] = useState<Script | null>(null);
  const [loading, setLoading] = useState(true);
  const [deletingScript, setDeletingScript] = useState(false);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showParseDialog, setShowParseDialog] = useState(false);
  const [parseMode, setParseMode] = useState<"create" | "reparse">("create");

  // 分镜状态
  const [storyboard, setStoryboard] = useState<Storyboard | null>(null);
  const [storyboardEpisodes, setStoryboardEpisodes] = useState<StoryboardEpisode[]>([]);
  const [storyboardSceneCount, setStoryboardSceneCount] = useState(0);
  const [storyboardItemCount, setStoryboardItemCount] = useState(0);
  const [loadingStoryboard, setLoadingStoryboard] = useState(true);
  const [deletingStoryboard, setDeletingStoryboard] = useState(false);

  // 画风预设
  const [artPresets, setArtPresets] = useState<ArtStylePreset[]>([]);

  useEffect(() => {
    artStyleApi.getPresets().then(setArtPresets).catch(console.error);
  }, []);

  // 资产状态
  const [assets, setAssets] = useState<Asset[]>([]);
  const [assetCounts, setAssetCounts] = useState<Record<string, number>>({});
  const [loadingAssets, setLoadingAssets] = useState(true);

  // 串行加载所有概览数据：剧本 → 分镜 → 集 → 场次/镜头 → 资产
  const loadAllData = useCallback(async () => {
    // 1. 加载剧本
    try {
      setLoading(true);
      const scripts = await scriptApi.list(projectId);
      setScript(scripts.length > 0 ? scripts[0] : null);
    } catch (err) {
      console.error("加载剧本数据失败:", err);
    } finally {
      setLoading(false);
    }

    // 2. 加载分镜
    try {
      setLoadingStoryboard(true);
      const list = await storyboardApi.list(projectId);
      if (list.length > 0) {
        const sb = list[0];
        setStoryboard(sb);

        // 3. 加载集
        const episodes = await storyboardApi.listEpisodes(sb.id);
        setStoryboardEpisodes(episodes);

        // 4. 加载场次和镜头
        const [scenes, items] = await Promise.all([
          storyboardApi.listScenesByStoryboard(sb.id),
          storyboardApi.listItems(sb.id),
        ]);
        setStoryboardSceneCount(scenes.length);
        setStoryboardItemCount(items.length);
      } else {
        setStoryboard(null);
        setStoryboardEpisodes([]);
        setStoryboardSceneCount(0);
        setStoryboardItemCount(0);
      }
    } catch (err) {
      console.error("加载分镜数据失败:", err);
    } finally {
      setLoadingStoryboard(false);
    }

    // 3. 加载资产
    try {
      setLoadingAssets(true);
      const data = await assetApi.list(projectId);
      setAssets(data);
      // 统计各类型数量
      const counts: Record<string, number> = {};
      data.forEach((a) => {
        counts[a.type] = (counts[a.type] || 0) + 1;
      });
      setAssetCounts(counts);
    } catch (err) {
      console.error("加载资产数据失败:", err);
    } finally {
      setLoadingAssets(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadAllData();
  }, [loadAllData]);

  const handleDeleteScript = async () => {
    if (!script) return;
    if (!confirm("确定要删除该剧本吗？所有分集和场次数据将一并删除。")) return;
    setDeletingScript(true);
    try {
      await scriptApi.delete(script.id);
      await loadAllData();
    } catch (err) {
      console.error("删除剧本失败:", err);
    } finally {
      setDeletingScript(false);
    }
  };

  const handleDeleteStoryboard = async () => {
    if (!storyboard) return;
    if (!confirm("确定要删除该分镜吗？所有分镜集、场次和镜头数据将一并删除。")) return;
    setDeletingStoryboard(true);
    try {
      await storyboardApi.delete(storyboard.id);
      await loadAllData();
    } catch (err) {
      console.error("删除分镜失败:", err);
    } finally {
      setDeletingStoryboard(false);
    }
  };

  // AI 生成剧本：创建成功后触发 pipeline
  const addPipeline = usePipelineStore((s) => s.addPipeline);
  const setPanelExpanded = usePipelineStore((s) => s.setPanelExpanded);
  const setExpandedTaskId = usePipelineStore((s) => s.setExpandedTaskId);

  const handleAiScriptCreated = (script: { id: number; title: string }) => {
    const scriptDisplayTitle = script.title?.trim() || project?.name?.trim() || "未命名项目";

    // 刷新列表
    loadAllData();

    // 启动 pipeline
    const pipelineId = addPipeline({
      label: `AI 生成剧本 - ${scriptDisplayTitle}`,
      projectId,
      request: {
        agentType: "script_full_parse",
        category: "pipeline",
        title: `AI 剧本解析：${scriptDisplayTitle}`,
        projectId,
        context: { scriptId: script.id },
      },
      onComplete: () => {
        // pipeline 完成后刷新剧本数据
        loadAllData();
      },
    });

    // 打开任务中心大面板并展开该 pipeline
    setPanelExpanded(true);
    setExpandedTaskId(pipelineId);

    // 跳转到剧本页
    router.push(`/projects/${projectId}/scripts`);
  };

  // AI 生成分镜：启动 pipeline
  const handleAiStoryboard = async () => {
    if (!script) return;

    const scriptDisplayTitle = script.title?.trim() || project?.name?.trim() || "未命名项目";

    try {
      // 优先复用当前分镜，避免重新解析时删除已有高质量分镜集。
      // state 中没有时再查数据库，避免 AI 失败后残留空分镜、再次点击又创建新空分镜
      let targetStoryboard = storyboard;
      if (!targetStoryboard) {
        const existingList = await storyboardApi.list(projectId);
        targetStoryboard = existingList.find((sb) => sb.scriptId === script.id) ?? null;
      }
      if (!targetStoryboard) {
        targetStoryboard = await storyboardApi.create({
          projectId,
          scriptId: script.id,
          title: script.title?.trim() || project?.name?.trim() || "AI 分镜",
        });
      }

      const pipelineId = addPipeline({
        label: `AI 生成分镜 - ${scriptDisplayTitle}`,
        projectId,
        request: {
          agentType: "script_to_storyboard",
          category: "pipeline",
          title: `AI 生成分镜：${scriptDisplayTitle}`,
          projectId,
          context: { scriptId: script.id, storyboardId: targetStoryboard.id },
        },
        onComplete: () => {
          // pipeline 完成后刷新数据
          loadAllData();
        },
      });

      // 打开任务中心大面板并展开该 pipeline
      setPanelExpanded(true);
      setExpandedTaskId(pipelineId);

      // 跳转到分镜页
      router.push(`/projects/${projectId}/storyboards`);
    } catch (err) {
      console.error("创建分镜记录失败:", err);
      alert("创建分镜记录失败，请重试");
    }
  };

  const status = useMemo(() =>
    script ? parsingStatusMap[script.parsingStatus] || parsingStatusMap[0] : null,
    [script]
  );

  const sbStatus = useMemo(() =>
    storyboard ? storyboardStatusMap[storyboard.status] || storyboardStatusMap[0] : null,
    [storyboard]
  );

  const artStyleName = useMemo(() => {
    if (!project?.artStyle) return "未设置";
    return artPresets.find((p) => p.key === project.artStyle)?.name ?? project.artStyle;
  }, [project?.artStyle, artPresets]);

  if (loading && loadingStoryboard) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* 面包屑 — mockup .pd-breadcrumb */}
      <motion.div variants={itemVariants} className="flex items-center gap-1.5 text-xs text-muted-foreground mb-3">
        <button onClick={() => router.push("/projects")} className="text-cyan-400 hover:text-cyan-300 transition-colors">项目管理</button>
        <span>/</span>
        <span className="text-muted-foreground">{project?.name || "未命名项目"}</span>
      </motion.div>

      {/* 标题行 — mockup .pd-title-row */}
      <motion.div variants={itemVariants} className="flex items-start justify-between mb-5">
        <div>
          <h1 className="text-[26px] font-extrabold tracking-tight">{project?.name || "未命名项目"}</h1>
          <p className="text-[13px] text-muted-foreground mt-1.5">{project?.description || "暂无描述"}</p>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={() => router.push(`/projects/${projectId}/settings`)}
            className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold border border-border-hi bg-white/[0.04] text-muted-foreground hover:bg-white/[0.08] hover:text-foreground transition-all"
          >
            项目设置
          </button>
        </div>
      </motion.div>

      {/* Meta bar */}
      <motion.div variants={itemVariants} className="rounded-2xl border border-border/60 bg-card/60 backdrop-blur-sm px-5 py-3.5 mb-6 flex items-center gap-6 flex-wrap shadow-[0_4px_20px_-8px_rgba(0,0,0,0.15)]">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Type className="h-3.5 w-3.5 text-cyan-400" />
          <span>类型</span>
          <span className={cn(
            "inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full",
            properties.type ? "bg-cyan-500/10 text-cyan-400 border border-cyan-500/20" : "bg-white/5 text-muted-foreground border border-white/5"
          )}>
            {properties.type || "未设置"}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Palette className="h-3.5 w-3.5 text-pink-400" />
          <span>画风</span>
          <span className={cn(
            "inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full",
            project?.artStyle ? "bg-pink-500/10 text-pink-400 border border-pink-500/20" : "bg-white/5 text-muted-foreground border border-white/5"
          )}>
            {artStyleName}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Monitor className="h-3.5 w-3.5 text-violet-400" />
          <span>比例</span>
          <span className={cn(
            "inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full",
            properties.aspectRatio ? "bg-violet-500/10 text-violet-400 border border-violet-500/20" : "bg-white/5 text-muted-foreground border border-white/5"
          )}>
            {properties.aspectRatio || "未设置"}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs text-muted-foreground ml-auto">
          <Clock className="h-3.5 w-3.5" />
          <span>更新于 {formatTime(project?.updateTime || new Date().toISOString())}</span>
        </div>
      </motion.div>

      {/* Tabs */}
      <motion.div variants={itemVariants} className="flex gap-2 border-b border-border/40 pb-4 mb-7">
        {[
          { key: "overview", label: "概览" },
          { key: "scripts", label: "剧本" },
          { key: "storyboards", label: "分镜" },
          { key: "assets", label: "素材" },
        ].map((tab, idx) => (
          <button
            key={tab.key}
            onClick={() => tab.key === "overview" ? undefined : router.push(`/projects/${projectId}/${tab.key}`)}
            className={cn(
              "px-4 py-2 rounded-full text-[13px] font-semibold transition-all",
              idx === 0
                ? "bg-primary/10 text-primary border border-primary/20 shadow-[0_0_12px_rgba(0,229,255,0.08)]"
                : "border border-border/60 text-muted-foreground hover:border-primary/30 hover:text-foreground bg-card/40"
            )}
          >
            {tab.label}
          </button>
        ))}
      </motion.div>

      {/* 制作流程 */}
      <div className="mb-2">
        <h2 className="text-lg font-bold flex items-center gap-2">
          <Sparkles className="h-4 w-4 text-primary" />
          制作流程
        </h2>
        <p className="text-xs text-muted-foreground mt-1">按阶段推进你的 AI 视频创作</p>
      </div>

      <div className="flex flex-col gap-5 pl-6 relative before:absolute before:left-[11px] before:top-4 before:bottom-4 before:w-px before:bg-gradient-to-b before:from-primary/40 before:via-primary/20 before:to-transparent">

      {/* 剧本面板 */}
      <motion.div variants={itemVariants} className="relative rounded-2xl border border-border/60 bg-card/60 backdrop-blur-sm p-5 shadow-[0_4px_20px_-8px_rgba(0,0,0,0.2)] hover:shadow-[0_8px_30px_-8px_rgba(0,229,255,0.08)] transition-all duration-300">
        <div className="absolute -left-6 top-5 h-5 w-5 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-cyan-400 text-[10px] font-bold flex items-center justify-center z-10">1</div>
        <div className="flex items-center justify-between mb-[18px]">
          <div className="text-[15px] font-bold flex items-center gap-2">
            <BookOpen className="h-[18px] w-[18px] text-cyan-400" />
            总剧本
          </div>
          {script && (
            <span className={cn(
              "inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full",
              status?.cls
            )}>
              {status?.label}
            </span>
          )}
        </div>

        {script ? (
          /* 已有剧本 */
          <div className="space-y-3">
            {/* 剧本信息 — ic-box + title */}
            <div className="flex items-center gap-3 pb-3">
              <div className="w-10 h-10 rounded-[10px] bg-cyan-500/10 flex items-center justify-center shrink-0">
                <FileText className="h-5 w-5 text-cyan-400" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="text-[15px] font-bold truncate">{script.title || "未命名剧本"}</div>
                <div className="text-xs text-muted-foreground">{script.genre || script.storySynopsis || "暂无描述"}</div>
              </div>
            </div>

            {/* 元信息 */}
            <div className="flex items-center gap-4 text-xs text-muted-foreground pb-3 border-b border-border">
              {script.totalEpisodes > 0 && <span>{script.totalEpisodes} 集</span>}
              <span>{formatTime(script.updateTime)}</span>
            </div>

            {/* 操作按钮 — .btn-cyan / .btn-ghost */}
            <div className="flex items-center gap-2 pt-1">
              <button
                onClick={() => router.push(`/projects/${projectId}/scripts`)}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold bg-primary text-primary-foreground shadow-[0_0_24px_rgba(0,229,255,0.12)] hover:-translate-y-0.5 transition-all"
              >
                <Eye className="h-3.5 w-3.5" />
                查看剧本
              </button>
              <button
                onClick={async () => {
                  if (!confirm("重新解析将删除当前剧本及其所有分集、场次数据，确定继续？")) return;
                  try {
                    await scriptApi.delete(script.id);
                    setParseMode("reparse");
                    setShowParseDialog(true);
                  } catch (err) {
                    console.error("删除旧剧本失败:", err);
                  }
                }}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold border border-border-hi bg-white/[0.04] text-muted-foreground hover:bg-white/[0.08] hover:text-foreground transition-all"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                AI 重新解析
              </button>
              <button
                onClick={handleDeleteScript}
                disabled={deletingScript}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold border border-destructive/30 text-destructive hover:bg-destructive/10 transition-all ml-auto"
              >
                {deletingScript ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
                删除
              </button>
            </div>
          </div>
        ) : (
          /* 没有剧本：引导创建 */
          <div className="grid grid-cols-2 gap-3">
            <div
              onClick={() => setShowCreateDialog(true)}
              className="rounded-lg border border-dashed border-border-hi p-4 flex flex-col items-center justify-center text-center hover:border-primary hover:bg-primary/5 transition-all cursor-pointer"
            >
              <div className="w-10 h-10 rounded-[10px] bg-cyan-500/10 flex items-center justify-center mb-2">
                <Plus className="h-5 w-5 text-cyan-400" />
              </div>
              <p className="text-[13px] font-semibold">手动创建剧本</p>
              <p className="text-[11px] text-muted-foreground mt-0.5">创建空白剧本</p>
            </div>
            <div
              onClick={() => { setParseMode("create"); setShowParseDialog(true); }}
              className="rounded-lg border border-dashed border-border-hi p-4 flex flex-col items-center justify-center text-center hover:border-violet-400 hover:bg-violet-500/5 transition-all cursor-pointer"
            >
              <div className="w-10 h-10 rounded-[10px] bg-violet-500/10 flex items-center justify-center mb-2">
                <Sparkles className="h-5 w-5 text-violet-400" />
              </div>
              <p className="text-[13px] font-semibold">AI 生成剧本</p>
              <p className="text-[11px] text-muted-foreground mt-0.5">粘贴原文自动解析</p>
            </div>
          </div>
        )}

        <CreateScriptDialog
          open={showCreateDialog}
          projectId={projectId}
          projectName={project?.name}
          onClose={() => setShowCreateDialog(false)}
          onCreated={() => loadAllData()}
        />
        <ParseScriptDialog
          open={showParseDialog}
          projectId={projectId}
          projectName={project?.name}
          mode={parseMode}
          onClose={() => setShowParseDialog(false)}
          onCreated={handleAiScriptCreated}
        />
      </motion.div>

      {/* 分镜面板 */}
      <motion.div variants={itemVariants} className="relative rounded-2xl border border-border/60 bg-card/60 backdrop-blur-sm p-5 shadow-[0_4px_20px_-8px_rgba(0,0,0,0.2)] hover:shadow-[0_8px_30px_-8px_rgba(0,229,255,0.08)] transition-all duration-300">
        <div className="absolute -left-6 top-5 h-5 w-5 rounded-full bg-violet-500/15 border border-violet-500/30 text-violet-400 text-[10px] font-bold flex items-center justify-center z-10">2</div>
        <div className="flex items-center justify-between mb-[18px]">
          <div className="text-[15px] font-bold flex items-center gap-2">
            <Film className="h-[18px] w-[18px] text-violet-400" />
            分镜
          </div>
          {storyboard && (
            <span className={cn(
              "inline-flex items-center text-[11px] font-semibold px-2.5 py-0.5 rounded-full",
              sbStatus?.cls
            )}>
              {sbStatus?.label}
            </span>
          )}
        </div>

        {loadingStoryboard ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : storyboard ? (
          <div className="space-y-3">
            <div className="flex items-center gap-3 pb-3">
              <div className="w-10 h-10 rounded-[10px] bg-violet-500/10 flex items-center justify-center shrink-0">
                <Film className="h-5 w-5 text-violet-400" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="text-[15px] font-bold truncate">{storyboard.title || "未命名分镜"}</div>
                <div className="text-xs text-muted-foreground">{storyboard.description || "基于剧本自动生成"}</div>
              </div>
            </div>
            <div className="flex items-center gap-4 text-xs text-muted-foreground pb-3 border-b border-border">
              {storyboardEpisodes.length > 0 && <span>{storyboardEpisodes.length} 集</span>}
              <span>{storyboardSceneCount} 场</span>
              <span>{storyboardItemCount} 镜头</span>
            </div>
            <div className="flex items-center gap-2 pt-1">
              <button
                onClick={() => router.push(`/projects/${projectId}/storyboards`)}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold bg-primary text-primary-foreground shadow-[0_0_24px_rgba(0,229,255,0.12)] hover:-translate-y-0.5 transition-all"
              >
                <Eye className="h-3.5 w-3.5" />
                查看分镜
              </button>
              <button
                onClick={async () => {
                  if (!script) { alert("请先创建剧本后再生成分镜"); return; }
                  if (!confirm("重新解析将补齐缺失的分镜集，不会删除已有分镜内容。确定继续？")) return;
                  handleAiStoryboard();
                }}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold border border-border-hi bg-white/[0.04] text-muted-foreground hover:bg-white/[0.08] hover:text-foreground transition-all"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                重新生成
              </button>
              <button
                onClick={handleDeleteStoryboard}
                disabled={deletingStoryboard}
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-xs font-semibold border border-destructive/30 text-destructive hover:bg-destructive/10 transition-all ml-auto"
              >
                {deletingStoryboard ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
                删除
              </button>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3">
            <div
              onClick={() => router.push(`/projects/${projectId}/storyboards`)}
              className="rounded-lg border border-dashed border-border-hi p-4 flex flex-col items-center justify-center text-center hover:border-violet-400 hover:bg-violet-500/5 transition-all cursor-pointer"
            >
              <div className="w-10 h-10 rounded-[10px] bg-violet-500/10 flex items-center justify-center mb-2">
                <Plus className="h-5 w-5 text-violet-400" />
              </div>
              <p className="text-[13px] font-semibold">创建分镜</p>
              <p className="text-[11px] text-muted-foreground mt-0.5">手动创建分镜表</p>
            </div>
            <div
              onClick={() => {
                if (!script) { alert("请先创建剧本后再使用 AI 生成分镜"); return; }
                handleAiStoryboard();
              }}
              className="rounded-lg border border-dashed border-border-hi p-4 flex flex-col items-center justify-center text-center hover:border-violet-400 hover:bg-violet-500/5 transition-all cursor-pointer"
            >
              <div className="w-10 h-10 rounded-[10px] bg-violet-500/10 flex items-center justify-center mb-2">
                <Sparkles className="h-5 w-5 text-violet-400" />
              </div>
              <p className="text-[13px] font-semibold">AI 生成分镜</p>
              <p className="text-[11px] text-muted-foreground mt-0.5">基于剧本自动生成</p>
            </div>
          </div>
        )}
      </motion.div>

      {/* 资产面板 */}
          <motion.div variants={itemVariants} className="relative rounded-2xl border border-border/60 bg-card/60 backdrop-blur-sm p-5 shadow-[0_4px_20px_-8px_rgba(0,0,0,0.2)] hover:shadow-[0_8px_30px_-8px_rgba(0,229,255,0.08)] transition-all duration-300">
            <div className="absolute -left-6 top-5 h-5 w-5 rounded-full bg-amber-500/15 border border-amber-500/30 text-amber-400 text-[10px] font-bold flex items-center justify-center z-10">3</div>
            <div className="flex items-center justify-between mb-[18px]">
              <div className="text-[15px] font-bold flex items-center gap-2">
                <Images className="h-[18px] w-[18px] text-amber-400" />
                素材资产
              </div>
              <button
                onClick={() => router.push(`/projects/${projectId}/assets`)}
                className="text-xs text-cyan-400 font-medium hover:text-cyan-300 transition-colors"
              >
                全部 →
              </button>
            </div>

            {loadingAssets ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : assets.length > 0 ? (
              <div>
                {/* 类型统计 — mockup 3列 grid */}
                <div className="grid grid-cols-3 gap-2 mb-3">
                  <div className="text-center">
                    <div className="text-[22px] font-extrabold text-cyan-400">{assetCounts["character"] || 0}</div>
                    <div className="text-[11px] text-muted-foreground">角色</div>
                  </div>
                  <div className="text-center">
                    <div className="text-[22px] font-extrabold text-green-400">{assetCounts["scene"] || 0}</div>
                    <div className="text-[11px] text-muted-foreground">场景</div>
                  </div>
                  <div className="text-center">
                    <div className="text-[22px] font-extrabold text-amber-400">{assetCounts["prop"] || 0}</div>
                    <div className="text-[11px] text-muted-foreground">道具</div>
                  </div>
                </div>

                {/* 资产缩略图条 — mockup .pd-asset-strip */}
                <div className="flex gap-3 overflow-x-auto pb-1">
                  {assets.slice(0, 8).map((asset) => {
                    const typeBg: Record<string, string> = {
                      character: "bg-cyan-500/10",
                      scene: "bg-green-500/10",
                      prop: "bg-amber-500/10",
                    };
                    const typeLabel: Record<string, string> = {
                      character: "角色",
                      scene: "场景",
                      prop: "道具",
                    };
                    return (
                      <div
                        key={asset.id}
                        onClick={() => router.push(`/projects/${projectId}/assets`)}
                        className={cn(
                          "w-20 h-20 rounded-[10px] border border-border-hi shrink-0",
                          "flex items-center justify-center text-[11px] text-muted-foreground cursor-pointer",
                          "hover:border-primary hover:scale-105 transition-all overflow-hidden",
                          typeBg[asset.type] || "bg-muted/30"
                        )}
                      >
                        {asset.coverUrl ? (
                          <SafeImage
                            src={resolveMediaUrl(asset.coverUrl) || ""}
                            alt={asset.name}
                            fallbackType={
                              asset.type === "character" ? "avatar"
                              : asset.type === "scene" ? "scene"
                              : asset.type === "prop" ? "prop" : "image"
                            }
                            className="w-full h-full object-cover"
                          />
                        ) : (
                          <span>{typeLabel[asset.type] || asset.type}</span>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
        ) : (
          /* 没有资产：引导 */
          <div
            onClick={() => router.push(`/projects/${projectId}/assets`)}
            className="rounded-lg border border-dashed border-border-hi p-6 flex flex-col items-center justify-center text-center hover:border-amber-400 hover:bg-amber-500/5 transition-all cursor-pointer"
          >
            <div className="w-10 h-10 rounded-[10px] bg-amber-500/10 flex items-center justify-center mb-2">
              <Images className="h-5 w-5 text-amber-400" />
            </div>
            <p className="text-[13px] font-semibold">管理资产</p>
            <p className="text-[11px] text-muted-foreground mt-0.5">创建角色、场景、道具</p>
          </div>
        )}
          </motion.div>

          {/* 画风面板 */}
          <motion.div variants={itemVariants} className="relative rounded-2xl border border-border/60 bg-card/60 backdrop-blur-sm p-5 shadow-[0_4px_20px_-8px_rgba(0,0,0,0.2)] hover:shadow-[0_8px_30px_-8px_rgba(0,229,255,0.08)] transition-all duration-300">
            <div className="absolute -left-6 top-5 h-5 w-5 rounded-full bg-pink-500/15 border border-pink-500/30 text-pink-400 text-[10px] font-bold flex items-center justify-center z-10">4</div>
            <div className="flex items-center justify-between mb-[18px]">
              <div className="text-[15px] font-bold flex items-center gap-2">
                <Palette className="h-[18px] w-[18px] text-pink-400" />
                画风设置
              </div>
              <button
                onClick={() => router.push(`/projects/${projectId}/settings`)}
                className="text-xs text-cyan-400 font-medium hover:text-cyan-300 transition-colors"
              >
                修改 →
              </button>
            </div>
            <div className="flex items-center gap-3 mb-3.5">
              <div className="w-12 h-12 rounded-[10px] bg-linear-to-br from-pink-500/15 to-violet-500/15 flex items-center justify-center shrink-0">
                <Palette className="h-6 w-6 text-pink-400" />
              </div>
              <div>
                <div className="text-sm font-bold">
                  {artStyleName}
                </div>
                <div className="text-xs text-muted-foreground">点击修改画风预设</div>
              </div>
            </div>
            <div className="flex gap-2">
              {artPresets.slice(0, 4).map((preset) => {
                const isActive = project?.artStyle === preset.key;
                return (
                  <div
                    key={preset.key}
                    onClick={() => router.push(`/projects/${projectId}/settings`)}
                    className={cn(
                      "flex-1 px-3 py-2 rounded-lg text-xs text-center cursor-pointer transition-all",
                      isActive
                        ? "border-2 border-pink-400 bg-pink-500/10 font-semibold text-pink-400"
                        : "border border-border-hi text-muted-foreground hover:border-pink-400/50"
                    )}
                  >
                    {preset.name}
                  </div>
                );
              })}
              {artPresets.length === 0 && (
                <div className="flex-1 px-3 py-2 rounded-lg text-xs text-center text-muted-foreground border border-border-hi">
                  加载中...
                </div>
              )}
            </div>
          </motion.div>
        </div>{/* 概览结束 */}

        <CreateScriptDialog
          open={showCreateDialog}
          projectId={projectId}
          projectName={project?.name}
          onClose={() => setShowCreateDialog(false)}
          onCreated={() => loadAllData()}
        />
        <ParseScriptDialog
          open={showParseDialog}
          projectId={projectId}
          projectName={project?.name}
          mode={parseMode}
          onClose={() => setShowParseDialog(false)}
          onCreated={handleAiScriptCreated}
        />
    </motion.div>
  );
}
