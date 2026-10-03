import { create } from "zustand";
import * as authApi from "@/lib/api/auth";
import type { MenuVO } from "@/lib/api/types";

interface PermissionState {
  permissions: Set<string>;
  menus: MenuVO[];
  dataScope: Record<string, string>;
  permissionVersion: number | null;
  contextProjectId: number | null;
  loaded: boolean;

  fetchPermissions: (projectId?: number | null) => Promise<void>;
  hasPermission: (code: string) => boolean;
  hasAnyPermission: (...codes: string[]) => boolean;
  reset: () => void;
}

export const usePermissionStore = create<PermissionState>()((set, get) => ({
  permissions: new Set<string>(),
  menus: [],
  dataScope: {},
  permissionVersion: null,
  contextProjectId: null,
  loaded: false,

  fetchPermissions: async (projectId = null) => {
    const requestId = ++latestPermissionRequestId;
    set({ loaded: false, contextProjectId: projectId });
    try {
      const resp = await authApi.getPermissions(projectId);
      if (requestId !== latestPermissionRequestId) return;
      set({
        permissions: new Set(resp.permissions),
        menus: resp.menus,
        dataScope: resp.dataScope,
        permissionVersion: resp.permissionVersion,
        contextProjectId: projectId,
        loaded: true,
      });
    } catch {
      if (requestId !== latestPermissionRequestId) return;
      // 权限加载失败时保持拒绝状态，避免短暂展示未授权功能。
      set({ permissions: new Set(), menus: [], dataScope: {}, loaded: true });
    }
  },

  hasPermission: (code: string) => {
    const perms = get().permissions;
    return perms.has("*") || perms.has(code);
  },

  hasAnyPermission: (...codes: string[]) => {
    const perms = get().permissions;
    if (perms.has("*")) return true;
    return codes.some((c) => perms.has(c));
  },

  reset: () => {
    set({
      permissions: new Set<string>(),
      menus: [],
      dataScope: {},
      permissionVersion: null,
      contextProjectId: null,
      loaded: false,
    });
  },
}));

let latestPermissionRequestId = 0;
