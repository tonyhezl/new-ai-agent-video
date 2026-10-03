"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { usePermissionStore } from "@/lib/store/permission-store";
import { useAuthStore } from "@/lib/store/auth-store";

/**
 * 路由级权限守卫 Hook
 * <p>
 * 在页面组件中调用，校验当前用户是否拥有指定权限码。
 * 无权限时自动跳转 /403。
 */
export function usePermissionGuard(permissionCode: string): boolean {
  const router = useRouter();
  const hasPermission = usePermissionStore((s) => s.hasPermission);
  const loaded = usePermissionStore((s) => s.loaded);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const allowed = loaded && hasPermission(permissionCode);

  useEffect(() => {
    if (!isAuthenticated) return;
    if (!loaded) return;
    if (!allowed) {
      router.replace("/403");
    }
  }, [isAuthenticated, loaded, allowed, router]);

  return allowed;
}

/**
 * 任意一个权限码匹配即通过
 */
export function useAnyPermissionGuard(...permissionCodes: string[]): boolean {
  const router = useRouter();
  const hasAnyPermission = usePermissionStore((s) => s.hasAnyPermission);
  const loaded = usePermissionStore((s) => s.loaded);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const allowed = loaded && hasAnyPermission(...permissionCodes);

  useEffect(() => {
    if (!isAuthenticated) return;
    if (!loaded) return;
    if (!allowed) {
      router.replace("/403");
    }
  }, [isAuthenticated, loaded, allowed, router]);

  return allowed;
}
