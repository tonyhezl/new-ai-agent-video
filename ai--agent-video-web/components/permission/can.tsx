"use client";

import { ReactNode } from "react";
import { usePermissionStore } from "@/lib/store/permission-store";

interface CanProps {
  permissionCode: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/**
 * 按钮级权限控制组件
 * <Can permissionCode="project:delete"><Button>删除</Button></Can>
 */
export function Can({ permissionCode, children, fallback = null }: CanProps) {
  const hasPermission = usePermissionStore((s) => s.hasPermission);
  if (!hasPermission(permissionCode)) return <>{fallback}</>;
  return <>{children}</>;
}

/**
 * 任意一个权限码匹配即渲染
 */
export function CanAny({ permissionCodes, children, fallback = null }: {
  permissionCodes: string[];
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const hasAnyPermission = usePermissionStore((s) => s.hasAnyPermission);
  if (!hasAnyPermission(...permissionCodes)) return <>{fallback}</>;
  return <>{children}</>;
}
