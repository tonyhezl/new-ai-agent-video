"use client";

import { useEffect, useState, useMemo, useSyncExternalStore } from "react";
import { useRouter, usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { useAuthStore } from "@/lib/store/auth-store";
import { AppHeader } from "@/components/dashboard/app-header";
import { SidebarNav } from "@/components/dashboard/sidebar-nav";
import { VersionUpdateNotifier } from "@/components/dashboard/version-update-notifier";
import { cn } from "@/lib/utils";
import { projectApi, type Project } from "@/lib/api/project";
import { LayoutContext, useLayoutState } from "@/lib/hooks/use-layout";
import { usePermissionStore } from "@/lib/store/permission-store";

function requiredPermissionForPath(pathname: string): string | null {
  if (/^\/projects\/\d+\/scripts(?:\/|$)/.test(pathname)) return "script:view";
  if (/^\/projects\/\d+\/storyboards(?:\/|$)/.test(pathname)) return "storyboard:view";
  if (/^\/projects\/\d+\/assets(?:\/|$)/.test(pathname)) return "asset:view";
  if (/^\/projects\/\d+\/members(?:\/|$)/.test(pathname)) return "project:member:manage";
  if (/^\/projects\/\d+\/settings(?:\/|$)/.test(pathname)) return "project:update";
  if (pathname === "/projects" || /^\/projects\/\d+(?:\/)?$/.test(pathname)) return "project:view";
  if (pathname === "/assets") return "asset:view";
  if (pathname === "/media") return "media:read";
  if (pathname.startsWith("/pov-stories")) return "pov:story:view";
  if (pathname === "/settings/users") return "system:user:manage";
  if (pathname === "/settings/permissions") return "system:role:manage";
  if (["/settings/ai-models", "/settings/agent-models", "/settings/capability-rules"].includes(pathname)) {
    return "ai:model:manage";
  }
  if (pathname === "/settings/storage") return "ai:config:manage";
  if (pathname.startsWith("/settings/workbench")) return "system:user:manage";
  if (pathname.startsWith("/dashboard")) return "dashboard:view";
  return null;
}

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const fetchPermissions = usePermissionStore((s) => s.fetchPermissions);
  const permissionLoaded = usePermissionStore((s) => s.loaded);
  const contextProjectId = usePermissionStore((s) => s.contextProjectId);
  const hasPermission = usePermissionStore((s) => s.hasPermission);
  const authHydrated = useSyncExternalStore(
    (onStoreChange) => {
      const unsubStart = useAuthStore.persist.onHydrate(onStoreChange);
      const unsubFinish = useAuthStore.persist.onFinishHydration(onStoreChange);
      return () => {
        unsubStart();
        unsubFinish();
      };
    },
    () => useAuthStore.persist.hasHydrated(),
    () => false
  );
  const [projectState, setProjectState] = useState<{ id: number; project: Project } | null>(null);
  const currentProjectId = useMemo(() => {
    const match = pathname.match(/^\/projects\/(\d+)/);
    return match ? Number(match[1]) : null;
  }, [pathname]);
  const currentProject = projectState?.id === currentProjectId ? projectState.project : null;
  const requiredPermission = useMemo(() => requiredPermissionForPath(pathname), [pathname]);

  // 布局宽度控制：子页面通过 useFullWidth(condition) 驱动
  const { fullWidth, setFullWidth } = useLayoutState();
  const layoutCtx = useMemo(
    () => ({ fullWidth, setFullWidth }),
    [fullWidth, setFullWidth]
  );

  // 在 layout 层统一请求 project 数据，供 AppHeader 共享
  useEffect(() => {
    if (currentProjectId === null) {
      return;
    }
    let cancelled = false;
    projectApi.get(currentProjectId)
      .then((project) => {
        if (!cancelled) {
          setProjectState({ id: currentProjectId, project });
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [currentProjectId]);

  // 运行时登出检测：用户主动登出后跳转到登录页
  // 初始进入时的认证保护由 middleware 处理
  useEffect(() => {
    if (authHydrated && !isAuthenticated) {
      router.replace("/login");
    }
  }, [authHydrated, isAuthenticated, router]);

  useEffect(() => {
    if (!authHydrated || !isAuthenticated) return;
    void fetchPermissions(currentProjectId);
  }, [authHydrated, isAuthenticated, currentProjectId, fetchPermissions]);

  const permissionsReady = permissionLoaded && contextProjectId === currentProjectId;
  const routeAllowed = requiredPermission === null || hasPermission(requiredPermission);

  useEffect(() => {
    if (authHydrated && isAuthenticated && permissionsReady && !routeAllowed) {
      router.replace("/403");
    }
  }, [authHydrated, isAuthenticated, permissionsReady, routeAllowed, router]);

  const ready = authHydrated && isAuthenticated && permissionsReady && routeAllowed;

  return (
    <LayoutContext value={layoutCtx}>
      <div className="h-screen overflow-hidden flex flex-col bg-background">
        <VersionUpdateNotifier />

        {/* 顶部导航栏 — sticky 定位，无 motion.div 包裹避免 transform 破坏 sticky */}
        <div
          className={cn(
            "transition-opacity duration-500",
            ready ? "opacity-100" : "opacity-0"
          )}
        >
          <AppHeader project={currentProject} />
        </div>

        {/* 主内容区 */}
        <motion.div
          className="flex flex-1 min-h-0 flex-col"
          initial={{ opacity: 0 }}
          animate={ready ? { opacity: 1 } : { opacity: 0 }}
          transition={{ duration: 0.4, delay: 0.15 }}
        >
          <SidebarNav project={currentProject} />

          {/* 主内容区 */}
          <main className="flex-1 min-w-0 min-h-0 overflow-auto">
            <div className={cn("w-full h-full mx-auto transition-[max-width] duration-300 ease-in-out px-6 lg:px-8 py-6", fullWidth ? "max-w-full" : "max-w-7xl")}>
              {ready ? children : null}
            </div>
          </main>
        </motion.div>
      </div>
    </LayoutContext>
  );
}
