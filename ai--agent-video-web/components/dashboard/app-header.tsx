"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter, usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FolderKanban,
  Images,
  Settings,
  Bell,
  LogOut,
  Menu,
  X,
  Film,
  Eye,
  Sparkles,
} from "lucide-react";
import { MobileMenuPanel } from "@/components/ui/glow-menu";
import { AnimatedThemeToggler } from "@/components/ui/animated-theme-toggler";
import { UserAvatarDropdown } from "@/components/ui/menu";
import { NotificationPanel } from "@/components/dashboard/notification-panel";
import { useAuthStore } from "@/lib/store/auth-store";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import { cn } from "@/lib/utils";
import { usePermissionStore } from "@/lib/store/permission-store";
import { clearAuthCookie } from "@/lib/api/client";
import { type Project } from "@/lib/api/project";

// 全局一级菜单
const globalMenuItems = [
  { icon: LayoutDashboard, label: "仪表盘", href: "/dashboard", iconColor: "text-cyan-400", permissionCode: "dashboard:view", gradient: "" },
  { icon: Sparkles, label: "灵感画布", href: "/workbench", iconColor: "text-violet-400", permissionCode: null, gradient: "" },
  { icon: FolderKanban, label: "项目", href: "/projects", iconColor: "text-pink-400", permissionCode: "project:view", gradient: "" },
  { icon: Eye, label: "猎奇故事", href: "/pov-stories", iconColor: "text-amber-400", permissionCode: "pov:story:view", gradient: "" },
  { icon: Images, label: "资产", href: "/assets", iconColor: "text-orange-400", permissionCode: "asset:view", gradient: "" },
  { icon: Film, label: "媒体库", href: "/media", iconColor: "text-teal-400", permissionCode: "media:read", gradient: "" },
  { icon: Settings, label: "系统设置", href: "/settings", iconColor: "text-slate-400", permissionCode: null, gradient: "" },
];


const routeToLabel: Record<string, string> = {
  "/dashboard": "仪表盘",
  "/workbench": "灵感画布",
  "/projects": "项目",
  "/pov-stories": "猎奇故事",
  "/assets": "资产",
  "/media": "媒体库",
  "/settings": "系统设置",
};

interface AppHeaderProps {
  project?: Project | null;
}

/**
 * 顶部导航栏组件
 */
export function AppHeader({ project }: AppHeaderProps) {
  const router = useRouter();
  const pathname = usePathname();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const tryReconnect = usePipelineStore((s) => s.tryReconnect);
  const hasPermission = usePermissionStore((s) => s.hasPermission);
  const [mobileMenuRoute, setMobileMenuRoute] = useState<string | null>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const bellRef = useRef<HTMLButtonElement>(null);
  const mobileMenuOpen = mobileMenuRoute === pathname;
  const visibleMenuItems = globalMenuItems.filter(
    (item) => item.permissionCode === null || hasPermission(item.permissionCode)
  );

  // Pipeline 通知 — 派生为原始值 selector，避免 tasks 数组每次 flush 都换新引用导致头部每 100ms 重渲染
  const panelExpanded = usePipelineStore((s) => s.panelExpanded);
  const setNotificationOpen = usePipelineStore((s) => s.setNotificationOpen);
  const setPanelExpanded = usePipelineStore((s) => s.setPanelExpanded);
  const runningCount = usePipelineStore((s) => s.tasks.filter((t) => t.status === "running").length);
  const hasAnyTasks = usePipelineStore((s) => s.tasks.length > 0);

  // 根据当前路由确定高亮菜单项
  const activeLabel =
    Object.entries(routeToLabel).find(([route]) =>
      pathname.startsWith(route)
    )?.[1] || "仪表盘";

  // 页面加载时尝试重连 running pipelines
  useEffect(() => {
    tryReconnect();
  }, [tryReconnect]);

  // 点击外部区域关闭移动端菜单
  useEffect(() => {
    if (!mobileMenuOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (headerRef.current && !headerRef.current.contains(e.target as Node)) {
        setMobileMenuRoute(null);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [mobileMenuOpen]);

  // 处理导航菜单点击
  const handleMenuClick = (label: string) => {
    const item = visibleMenuItems.find((m) => m.label === label);
    if (item) {
      router.push(item.href);
    }
    setMobileMenuRoute(null);
  };

  // 处理退出登录（layout 会在退出动画完成后自动跳转到登录页）
  const handleLogout = async () => {
    // 等待下拉菜单关闭动画（~200ms）
    await new Promise((resolve) => setTimeout(resolve, 200));
    clearAuthCookie();
    await logout();
  };

  // 用户头像下拉菜单项
  const userMenuItems = [
    {
      label: "个人设置",
      icon: <Settings className="h-full w-full" />,
      onClick: () => router.push("/settings/profile"),
    },
  ];

  const userLogoutItem = {
    label: "退出登录",
    icon: <LogOut className="h-full w-full" />,
    onClick: handleLogout,
  };

  return (
    <header ref={headerRef} className="sticky top-0 z-50 px-5 pt-3 pb-3 bg-transparent">
      <div className="flex items-center w-full h-[52px] rounded-2xl border border-border/50 bg-[rgba(10,10,15,0.85)] backdrop-blur-[20px] shadow-[0_8px_30px_-8px_rgba(0,0,0,0.4)] px-4 justify-between">
        {/* 左侧：导航 */}
        <nav className="hidden md:flex order-1 items-center rounded-full border border-border/40 bg-black/30 p-1">
          {visibleMenuItems.map((item) => {
                const isActive = activeLabel === item.label;
                const Icon = item.icon;
                return (
                  <button
                    key={item.label}
                    onClick={() => handleMenuClick(item.label)}
                    className={cn(
                      "flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[13px] font-medium whitespace-nowrap transition-all",
                      isActive
                        ? "bg-primary text-primary-foreground shadow-[0_0_20px_rgba(0,229,255,0.2)]"
                        : "text-muted-foreground hover:text-foreground hover:bg-white/[0.06]"
                    )}
                  >
                    <Icon className={cn("h-3.5 w-3.5", item.iconColor)} />
                    {item.label}
                  </button>
                );
              })}
        </nav>

        {/* 中间：Logo */}
        <div className="order-2 flex items-center justify-center shrink-0 min-w-0">
          <span
            className="text-lg font-bold tracking-tight text-transparent bg-[linear-gradient(135deg,#00e5ff_0%,#ff2e88_100%)] bg-clip-text cursor-pointer"
            onClick={() => router.push("/entry")}
          >
            织影
          </span>
        </div>

        {/* 移动端菜单按钮 */}
        <button
          onClick={() => setMobileMenuRoute(mobileMenuOpen ? null : pathname)}
          className={cn(
            "md:hidden order-4 p-1.5 rounded-lg transition-colors",
            "text-muted-foreground hover:text-foreground"
          )}
          aria-label={mobileMenuOpen ? "关闭菜单" : "打开菜单"}
        >
          {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>

        {/* 右侧：主题 + 通知 + 头像 */}
        <div className="order-3 flex items-center gap-2 shrink-0">
          <AnimatedThemeToggler className="text-muted-foreground hover:text-foreground hover:bg-white/[0.04] transition-colors rounded-lg" />

          <button
            ref={bellRef}
            onClick={() => {
              if (panelExpanded) {
                setPanelExpanded(false);
                setNotificationOpen(false);
              } else {
                setPanelExpanded(true);
              }
            }}
            className={cn(
              "relative p-2 rounded-lg transition-colors",
              "text-muted-foreground hover:text-foreground hover:bg-white/[0.04]",
              panelExpanded ? "bg-primary/10 text-primary" : ""
            )}
          >
            <Bell className="h-[18px] w-[18px]" />
            {(runningCount > 0 || hasAnyTasks) && (
              <span
                className={cn(
                  "absolute top-0.5 right-0.5 flex items-center justify-center rounded-full ring-2 ring-background",
                  runningCount > 0
                    ? "h-4 w-4 bg-primary text-[9px] text-primary-foreground font-bold"
                    : "h-2 w-2 bg-muted-foreground/40"
                )}
              >
                {runningCount > 0 ? runningCount : null}
              </span>
            )}
          </button>

          <NotificationPanel anchorRef={bellRef} />

          <UserAvatarDropdown
            user={{
              name: user?.nickname || user?.username || "用户",
              email: user?.email || user?.username,
              avatarUrl: user?.avatar,
            }}
            menuItems={userMenuItems}
            logoutItem={userLogoutItem}
          />
        </div>
      </div>

      {/* 移动端下拉菜单面板 */}
      <MobileMenuPanel
        items={visibleMenuItems}
        activeItem={activeLabel}
        onItemClick={handleMenuClick}
        isOpen={mobileMenuOpen}
      />
    </header>
  );
}
