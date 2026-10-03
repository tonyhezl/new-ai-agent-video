"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import {
  LayoutDashboard,
  BarChart3,
  FolderKanban,
  BookOpen,
  Film,
  Images,
  Users,
  Settings,
  ArrowLeft,
  Bot,
  HardDrive,
  Wrench,
  Coins,
  Clapperboard,
  Network,
  Eye,
  Shield,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { projectApi, type Project } from "@/lib/api/project";
import { usePermissionStore } from "@/lib/store/permission-store";

// ========== 各模块的二级菜单配置 ==========

interface SidebarItem {
  key: string;
  label: string;
  icon: typeof LayoutDashboard;
  href: string;
  iconColor: string;
  accentColor: string;
  permissionCode?: string;
}

const dashboardItems: SidebarItem[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, href: "/dashboard", iconColor: "text-cyan-400", accentColor: "bg-cyan-500", permissionCode: "dashboard:view" },
  { key: "token-usage", label: "Token 用量", icon: Coins, href: "/dashboard/token-usage", iconColor: "text-cyan-400", accentColor: "bg-cyan-500", permissionCode: "dashboard:view" },
  { key: "analytics", label: "数据分析", icon: BarChart3, href: "/dashboard/analytics", iconColor: "text-cyan-400", accentColor: "bg-cyan-500", permissionCode: "dashboard:view" },
];

const projectListItems: SidebarItem[] = [
  { key: "list", label: "项目列表", icon: FolderKanban, href: "/projects", iconColor: "text-pink-400", accentColor: "bg-pink-500", permissionCode: "project:view" },
];

const projectDetailItems: SidebarItem[] = [
  { key: "", label: "概览", icon: LayoutDashboard, href: "", iconColor: "text-cyan-400", accentColor: "bg-cyan-500", permissionCode: "project:view" },
  { key: "scripts", label: "剧本", icon: BookOpen, href: "/scripts", iconColor: "text-amber-400", accentColor: "bg-amber-500", permissionCode: "script:view" },
  { key: "storyboards", label: "分镜", icon: Film, href: "/storyboards", iconColor: "text-violet-400", accentColor: "bg-violet-500", permissionCode: "storyboard:view" },
  { key: "assets", label: "资产", icon: Images, href: "/assets", iconColor: "text-orange-400", accentColor: "bg-orange-500", permissionCode: "asset:view" },
  { key: "settings", label: "设置", icon: Settings, href: "/settings", iconColor: "text-slate-400", accentColor: "bg-slate-500", permissionCode: "project:update" },
];

const assetItems: SidebarItem[] = [
  { key: "list", label: "全部资产", icon: Images, href: "/assets", iconColor: "text-orange-400", accentColor: "bg-orange-500", permissionCode: "asset:view" },
];

const mediaItems: SidebarItem[] = [
  { key: "list", label: "媒体库", icon: Clapperboard, href: "/media", iconColor: "text-teal-400", accentColor: "bg-teal-500", permissionCode: "media:read" },
];

const povStoryItems: SidebarItem[] = [
  { key: "list", label: "猎奇故事", icon: Eye, href: "/pov-stories", iconColor: "text-amber-400", accentColor: "bg-amber-500", permissionCode: "pov:story:view" },
];

interface SidebarNavProps {
  /** 导航后回调，移动端抽屉用于关闭面板 */
  onNavigate?: () => void;
  /** 当前项目，未传入时组件会按路由自行加载 */
  project?: Project | null;
  /** 桌面端是否以图标栏形态收起 */
  collapsed?: boolean;
  /** 切换桌面端侧栏收起状态 */
  onCollapsedChange?: (collapsed: boolean) => void;
}

// ========== 侧边栏组件 ==========

export function SidebarNav({
  onNavigate,
  project: projectProp,
  collapsed = false,
}: SidebarNavProps) {
  const router = useRouter();
  const pathname = usePathname();
  const hasPermission = usePermissionStore((state) => state.hasPermission);

  const projectMatch = pathname.match(/^\/projects\/(\d+)/);
  const projectId = projectMatch ? Number(projectMatch[1]) : null;
  const [projectLocalState, setProjectLocalState] = useState<{
    id: number;
    project: Project;
  } | null>(null);

  const settingsItems: SidebarItem[] = [
    { key: "general", label: "通用设置", icon: Settings, href: "/settings/general", iconColor: "text-cyan-400", accentColor: "bg-cyan-500" },
    { key: "profile", label: "个人设置", icon: Users, href: "/settings/profile", iconColor: "text-violet-400", accentColor: "bg-violet-500" },
  ];

  if (hasPermission("system:user:manage")) {
    settingsItems.push({ key: "users", label: "用户列表", icon: Users, href: "/settings/users", iconColor: "text-amber-400", accentColor: "bg-amber-500" });
  }
  if (hasPermission("system:role:manage")) {
    settingsItems.push({ key: "permissions", label: "权限管理", icon: Shield, href: "/settings/permissions", iconColor: "text-pink-400", accentColor: "bg-pink-500" });
  }
  if (hasPermission("ai:model:manage")) {
    settingsItems.push({ key: "ai-models", label: "AI 模型", icon: Bot, href: "/settings/ai-models", iconColor: "text-teal-400", accentColor: "bg-teal-500" });
    settingsItems.push({ key: "agent-models", label: "Agent 模型", icon: Network, href: "/settings/agent-models", iconColor: "text-teal-400", accentColor: "bg-teal-500" });
    settingsItems.push({ key: "capability-rules", label: "能力规则", icon: Wrench, href: "/settings/capability-rules", iconColor: "text-orange-400", accentColor: "bg-orange-500" });
  }
  if (hasPermission("ai:config:manage")) {
    settingsItems.push({ key: "storage", label: "存储配置", icon: HardDrive, href: "/settings/storage", iconColor: "text-slate-400", accentColor: "bg-slate-500" });
  }
  // 工作台管理（管理员可见）
  if (hasPermission("system:user:manage")) {
    settingsItems.push({ key: "wb-model-prices", label: "模型价格", icon: Coins, href: "/settings/workbench/model-prices", iconColor: "text-cyan-400", accentColor: "bg-cyan-500" });
    settingsItems.push({ key: "wb-moderation", label: "审核词库", icon: Shield, href: "/settings/workbench/moderation", iconColor: "text-red-400", accentColor: "bg-red-500" });
    settingsItems.push({ key: "wb-reports", label: "对账报表", icon: BarChart3, href: "/settings/workbench/reports", iconColor: "text-violet-400", accentColor: "bg-violet-500" });
  }

  // 若外部已传入 project，则不在组件内自行请求
  const project =
    projectProp !== undefined
      ? projectProp
      : projectLocalState?.id === projectId
        ? projectLocalState.project
        : null;

  useEffect(() => {
    if (projectProp !== undefined) return; // 由外部管理，跳过
    if (!projectId) return;

    let cancelled = false;
    projectApi.get(projectId)
      .then((projectData) => {
        if (!cancelled) {
          setProjectLocalState({ id: projectId, project: projectData });
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [projectId, projectProp]);

  let items: SidebarItem[] = [];
  let sectionTitle = "";
  let backAction: (() => void) | null = null;

  if (projectId) {
    sectionTitle = project?.name || "加载中...";
    items = projectDetailItems
      .filter((item) => !item.permissionCode || hasPermission(item.permissionCode))
      .map((item) => ({
        ...item,
        href: `/projects/${projectId}${item.href}`,
      }));
    backAction = () => {
      router.push("/projects");
      onNavigate?.();
    };
  } else if (pathname.startsWith("/dashboard")) {
    sectionTitle = "仪表盘";
    items = dashboardItems;
  } else if (pathname.startsWith("/projects")) {
    sectionTitle = "项目";
    items = projectListItems;
  } else if (pathname.startsWith("/pov-stories")) {
    sectionTitle = "猎奇故事";
    items = povStoryItems;
  } else if (pathname.startsWith("/assets")) {
    sectionTitle = "资产";
    items = assetItems;
  } else if (pathname.startsWith("/media")) {
    sectionTitle = "媒体库";
    items = mediaItems;
  } else if (pathname.startsWith("/settings")) {
    sectionTitle = "系统设置";
    items = settingsItems;
  } else if (pathname.startsWith("/workbench")) {
    sectionTitle = "灵感画布";
    items = [];
  }

  items = items.filter((item) => !item.permissionCode || hasPermission(item.permissionCode));

  const getIsActive = (href: string) => {
    if (projectId) {
      const basePath = `/projects/${projectId}`;
      if (href === basePath) return pathname === basePath;
      return pathname.startsWith(href);
    }
    return pathname === href;
  };

  const handleNav = (href: string) => {
    router.push(href);
    onNavigate?.();
  };

  return (
    <div
      className={cn(
        "h-14 w-full border-b border-border/50 bg-card/80 backdrop-blur-sm",
        "flex items-center justify-between gap-4 px-4 shrink-0"
      )}
    >
      <div className="flex items-center gap-3 shrink-0">
        {backAction ? (
          <button
            onClick={backAction}
            className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            返回项目列表
          </button>
        ) : null}
        {project ? (
          <div className="flex items-center gap-2">
            <span className="text-sm font-bold text-foreground truncate max-w-[160px]">
              {project?.name || "加载中…"}
            </span>
            {project?.artStyle && (
              <span className="hidden sm:inline text-[10px] text-muted-foreground/60 px-1.5 py-0.5 rounded-full bg-muted/30">
                {project.artStyle}
              </span>
            )}
          </div>
        ) : (
          <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground/40">
            {sectionTitle}
          </span>
        )}
      </div>
      <nav className="flex items-center gap-2 overflow-x-auto scrollbar-none">
        {items.map((item) => {
          const Icon = item.icon;
          const isActive = getIsActive(item.href);

          return (
            <button
              key={item.key}
              onClick={() => handleNav(item.href)}
              title={item.label}
              aria-label={item.label}
              className={cn(
                "shrink-0 inline-flex items-center gap-2 px-3 py-1.5 rounded-full border text-[13px] font-medium transition-all",
                isActive
                  ? "bg-cyan-500/10 border-cyan-400/40 text-cyan-400 shadow-[0_0_12px_rgba(0,229,255,0.12)]"
                  : "border-border/40 text-muted-foreground hover:text-foreground hover:border-border/80"
              )}
            >
              <Icon className="h-4 w-4" />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>
    </div>
  );
}
