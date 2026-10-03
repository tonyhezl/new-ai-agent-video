"use client";

import {
  useEffect,
  useState,
  useCallback,
  useMemo,
  memo,
} from "react";
import { useRouter } from "next/navigation";
import {
  Plus,
  Search,
  Trash2,
  Loader2,
  Calendar,
  Film,
  Clapperboard,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { projectApi, type Project } from "@/lib/api/project";
import { CreateProjectDialog } from "@/components/dashboard/create-project-dialog";
import { usePermissionStore } from "@/lib/store/permission-store";
import { SafeImage } from "@/components/ui/safe-image";

// 动画
const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.1 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 20 },
  visible: {
    opacity: 1,
    y: 0,
    transition: {
      duration: 0.5,
      ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number],
    },
  },
};

const statusConfig: Record<string, { label: string; color: string; bg: string; glow: string }> = {
  进行中: { label: "进行中", color: "text-cyan-400", bg: "bg-cyan-500/10", glow: "shadow-[0_0_12px_rgba(34,211,238,0.15)]" },
  已完成: { label: "已完成", color: "text-emerald-400", bg: "bg-emerald-500/10", glow: "shadow-[0_0_12px_rgba(52,211,153,0.15)]" },
  剧本阶段: { label: "剧本阶段", color: "text-amber-400", bg: "bg-amber-500/10", glow: "shadow-[0_0_12px_rgba(251,191,36,0.15)]" },
  分镜阶段: { label: "分镜阶段", color: "text-violet-400", bg: "bg-violet-500/10", glow: "shadow-[0_0_12px_rgba(167,139,250,0.15)]" },
};

const gradients = [
  "from-violet-600/25 via-cyan-600/15 to-transparent",
  "from-pink-600/25 via-violet-600/15 to-transparent",
  "from-emerald-600/20 via-cyan-600/15 to-transparent",
  "from-amber-600/25 via-pink-600/15 to-transparent",
];

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

function getProjectStatus(project: Project) {
  return ((project.properties as Record<string, string>)?.status as string) || "进行中";
}

function getProjectStats(project: Project) {
  const props = (project.properties as Record<string, string | number>) || {};
  return {
    episodeCount: Number(props.totalEpisodes || 0),
    sceneCount: Number(props.totalScenes || 0),
    shotCount: Number(props.totalShots || 0),
    progress: Math.min(100, Math.max(0, Number(props.progress || 0))),
  };
}

const filterChips = ["全部", "进行中", "剧本阶段", "分镜阶段", "已完成"];

interface ProjectRowProps {
  project: Project;
  index: number;
  isDeleting: boolean;
  canDelete: boolean;
  onDelete: (e: React.MouseEvent, id: number) => void;
  onClick: () => void;
}

const ProjectRow = memo(function ProjectRow({
  project,
  index,
  isDeleting,
  canDelete,
  onDelete,
  onClick,
}: ProjectRowProps) {
  const status = getProjectStatus(project);
  const cfg = statusConfig[status] ?? statusConfig["进行中"];
  const stats = useMemo(() => getProjectStats(project), [project]);
  const gradient = gradients[index % gradients.length];
  const hasCover = project.coverUrl;

  return (
    <motion.div
      variants={itemVariants}
      whileHover={{ scale: 1.005, transition: { duration: 0.2 } }}
      onClick={onClick}
      className={cn(
        "group relative flex items-stretch rounded-2xl border cursor-pointer overflow-hidden",
        "bg-card/60 backdrop-blur-sm",
        "border-border/60 hover:border-primary/30",
        "hover:shadow-[0_12px_40px_-8px_rgba(0,229,255,0.10)]",
        "transition-all duration-300"
      )}
    >
      {/* 左侧海报 */}
      <div className={cn(
        "relative w-[140px] sm:w-[180px] shrink-0 overflow-hidden",
        !hasCover && ["bg-linear-to-br", gradient]
      )}>
        {hasCover ? (
          <SafeImage
            src={project.coverUrl!}
            alt={project.name}
            className="absolute inset-0 w-full h-full object-cover"
            fallbackType="image"
          />
        ) : (
          <div className={cn("absolute inset-0 bg-linear-to-br", gradient)} />
        )}
        <div className="absolute inset-0 bg-gradient-to-r from-transparent to-card/90" />
        <div className="absolute bottom-3 left-3 right-3">
          <span className={cn(
            "inline-flex items-center text-[10px] font-bold px-2 py-1 rounded-full border backdrop-blur-md",
            cfg.color,
            cfg.bg,
            "border-white/5",
            cfg.glow
          )}>
            {cfg.label}
          </span>
        </div>
      </div>

      {/* 右侧内容 */}
      <div className="flex-1 min-w-0 p-4 pr-3 flex flex-col justify-between gap-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="font-bold text-[16px] leading-tight line-clamp-1 mb-1.5">
              {project.name}
            </h3>
            <p className="text-xs text-muted-foreground/80 line-clamp-2 leading-relaxed max-w-[500px]">
              {project.description || "暂无描述"}
            </p>
          </div>
          <div className="shrink-0 flex items-center gap-2">
            {project.artStyle && (
              <span className="hidden sm:inline-flex text-[10px] text-muted-foreground/70 bg-muted/30 px-2 py-1 rounded border border-border/40">
                {project.artStyle}
              </span>
            )}
            {canDelete && (
              <button
                onClick={(e) => onDelete(e, project.id)}
                disabled={isDeleting}
                className="p-1.5 rounded-lg border border-border/60 bg-card/40 text-muted-foreground hover:text-destructive hover:border-destructive/30 hover:bg-destructive/10 transition-all"
              >
                {isDeleting ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Trash2 className="h-3.5 w-3.5" />
                )}
              </button>
            )}
          </div>
        </div>

        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          {/* 进度 */}
          {stats.progress > 0 ? (
            <div className="w-full sm:max-w-[220px]">
              <div className="flex items-center justify-between text-[10px] text-muted-foreground mb-1">
                <span>制作进度</span>
                <span className="font-medium text-foreground">{stats.progress}%</span>
              </div>
              <div className="h-1.5 w-full rounded-full bg-muted/40 overflow-hidden">
                <div
                  className={cn("h-full rounded-full transition-all duration-500", cfg.color.replace("text-", "bg-"))}
                  style={{ width: `${stats.progress}%` }}
                />
              </div>
            </div>
          ) : (
            <span className="text-[11px] text-muted-foreground/50">暂未开始</span>
          )}

          {/* 统计 + 时间 */}
          <div className="flex items-center gap-4 text-[11px] text-muted-foreground/70 shrink-0">
            {stats.episodeCount > 0 && (
              <span className="flex items-center gap-1">
                <Film className="h-3 w-3" />
                {stats.episodeCount} 集
              </span>
            )}
            {stats.sceneCount > 0 && (
              <span className="flex items-center gap-1">
                <Clapperboard className="h-3 w-3" />
                {stats.sceneCount} 场
              </span>
            )}
            <span className="flex items-center gap-1 text-[10px]">
              <Calendar className="h-3 w-3" />
              {formatTime(project.updateTime)}
            </span>
          </div>
        </div>
      </div>
    </motion.div>
  );
});

export default function ProjectsPage() {
  const router = useRouter();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("全部");
  const [showCreate, setShowCreate] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const canCreate = usePermissionStore((state) => state.hasPermission("project:create"));
  const canDelete = usePermissionStore((state) => state.hasPermission("project:delete"));

  const fetchProjects = useCallback(async () => {
    try {
      setLoading(true);
      const data = await projectApi.list();
      setProjects(data);
    } catch (err) {
      console.error("获取项目列表失败:", err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchProjects();
  }, [fetchProjects]);

  const handleDelete = useCallback(async (e: React.MouseEvent, id: number) => {
    e.stopPropagation();
    if (!confirm("确定要删除该项目吗？此操作不可撤销。")) return;
    setDeletingId(id);
    try {
      await projectApi.delete(id);
      await fetchProjects();
    } catch (err) {
      console.error("删除项目失败:", err);
    } finally {
      setDeletingId(null);
    }
  }, [fetchProjects]);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return projects.filter((p) => {
      const matchesSearch = !term ||
        p.name.toLowerCase().includes(term) ||
        p.description?.toLowerCase().includes(term);
      if (!matchesSearch) return false;
      if (filter === "全部") return true;
      return getProjectStatus(p) === filter;
    });
  }, [projects, search, filter]);

  const stats = useMemo(() => ({
    total: projects.length,
    inProgress: projects.filter((p) => getProjectStatus(p) === "进行中").length,
    inScript: projects.filter((p) => getProjectStatus(p) === "剧本阶段").length,
    inStoryboard: projects.filter((p) => getProjectStatus(p) === "分镜阶段").length,
    completed: projects.filter((p) => getProjectStatus(p) === "已完成").length,
  }), [projects]);

  const handleCardClick = useCallback((id: number) => {
    router.push(`/projects/${id}`);
  }, [router]);

  return (
    <>
      <motion.div
        className="max-w-[1200px]"
        variants={containerVariants}
        initial="hidden"
        animate="visible"
      >
        {/* 页面标题 */}
        <motion.div
          variants={itemVariants}
          className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 mb-6"
        >
          <div>
            <h1 className="text-[28px] font-extrabold tracking-tight bg-clip-text text-transparent bg-linear-to-r from-foreground to-foreground/70">
              项目管理
            </h1>
            <p className="text-[13px] text-muted-foreground mt-1">
              管理你的所有视频创作项目
            </p>
          </div>
          <div className="flex items-center gap-3">
            <div className="hidden sm:flex items-center gap-4 text-xs text-muted-foreground px-3 py-2 rounded-lg border border-border/50 bg-card/40">
              <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-cyan-400" />{stats.inProgress} 进行中</span>
              <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-violet-400" />{stats.inStoryboard} 分镜</span>
              <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />{stats.completed} 完成</span>
            </div>
            {canCreate && <button
              onClick={() => setShowCreate(true)}
              className={cn(
                "flex items-center gap-1.5 px-[18px] py-2.5 rounded-xl text-[13px] font-semibold",
                "bg-primary text-primary-foreground",
                "shadow-[0_0_24px_rgba(0,229,255,0.12)]",
                "hover:-translate-y-0.5 hover:shadow-[0_4px_20px_rgba(0,229,255,0.25)]",
                "active:scale-[0.98] transition-all duration-200"
              )}
            >
              <Plus className="h-4 w-4" />
              新建项目
            </button>}
          </div>
        </motion.div>

        {/* 搜索栏 */}
        <motion.div variants={itemVariants} className="mb-6 flex items-center gap-2.5 flex-wrap">
          <div
            className={cn(
              "flex items-center gap-2 px-4 py-2.5 rounded-xl",
              "border border-border/60 bg-card/50 backdrop-blur-sm transition-colors",
              "flex-1 max-w-[360px]",
              "focus-within:border-primary/60 focus-within:bg-card"
            )}
          >
            <Search className="h-4 w-4 text-muted-foreground" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索项目名称或描述..."
              className="flex-1 bg-transparent text-[13px] outline-none placeholder:text-muted-foreground/60"
            />
          </div>
          {filterChips.map((chip) => (
            <button
              key={chip}
              onClick={() => setFilter(chip)}
              className={cn(
                "px-4 py-[7px] rounded-full text-xs font-medium transition-all",
                filter === chip
                  ? "bg-primary text-primary-foreground shadow-[0_0_12px_rgba(0,229,255,0.15)]"
                  : "border border-border/60 bg-card/40 text-muted-foreground hover:border-primary hover:text-primary hover:bg-card/60"
              )}
            >
              {chip}
            </button>
          ))}
        </motion.div>

        {/* 加载状态 */}
        {loading ? (
          <motion.div
            variants={itemVariants}
            className="flex items-center justify-center py-20"
          >
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            <span className="ml-2 text-muted-foreground text-sm">
              加载中...
            </span>
          </motion.div>
        ) : filtered.length === 0 ? (
          /* 空状态 */
          <motion.div variants={itemVariants} className="mt-8">
            <div
              onClick={() => canCreate && setShowCreate(true)}
              className={cn(
                "rounded-2xl border border-dashed border-border/40 p-16",
                "flex flex-col items-center justify-center text-center",
                "bg-card/20 hover:border-primary/30 transition-colors",
                canCreate && "cursor-pointer"
              )}
            >
              <div className="h-16 w-16 rounded-2xl bg-cyan-500/10 flex items-center justify-center mb-4 shadow-[0_0_24px_rgba(6,182,212,0.12)]">
                <Plus className="h-7 w-7 text-cyan-400" />
              </div>
              <p className="text-muted-foreground text-sm">
                {projects.length === 0
                  ? canCreate ? "还没有项目，点击创建第一个视频项目" : "暂无可访问的项目"
                  : "没有找到匹配的项目"}
              </p>
            </div>
          </motion.div>
        ) : (
          /* 项目卡片列表 */
          <motion.div
            variants={containerVariants}
            initial="hidden"
            animate="visible"
            className="flex flex-col gap-4"
          >
            {filtered.map((project, idx) => (
              <ProjectRow
                key={project.id}
                project={project}
                index={idx}
                isDeleting={deletingId === project.id}
                canDelete={canDelete}
                onDelete={handleDelete}
                onClick={() => handleCardClick(project.id)}
              />
            ))}
          </motion.div>
        )}
      </motion.div>

      {canCreate && (
        <CreateProjectDialog
          open={showCreate}
          onClose={() => setShowCreate(false)}
          onCreated={fetchProjects}
        />
      )}
    </>
  );
}
