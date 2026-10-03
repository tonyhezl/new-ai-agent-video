import { http } from "./client";

// ========== 类型定义 ==========

export interface RoleAssignmentVO {
  id: number;
  userId: number;
  roleId: number;
  scopeType: string;
  scopeId: number | null;
  status: string;
  expiresAt: string | null;
  createdAt: string;
}

export interface AssignReqVO {
  userId: number;
  roleId: number;
  scopeType: string;
  scopeId?: number | null;
  expiresAt?: string | null;
}

export interface RoleVO {
  id: number;
  name: string;
  code: string;
  sort: number;
  status: number;
  remark: string | null;
}

export interface ScopeOption {
  id: number;
  name: string;
}

export interface MenuTreeNode {
  id: number;
  name: string;
  title: string;
  path: string | null;
  icon: string | null;
  type: string;
  permissionCode: string | null;
  visible: number;
  status: number;
  sort: number;
  children: MenuTreeNode[];
}

export interface PermissionAuditVO {
  id: number;
  operatorId: number;
  targetUserId: number;
  action: string;
  roleId: number | null;
  scopeType: string | null;
  scopeId: number | null;
  detail: string | null;
  createTime: string;
}

export interface PermissionMetrics {
  permissionVersion: number;
  deniedCount: number;
  cacheHit: number;
  cacheMiss: number;
  cacheHitRate: string;
  expiredAssignments: number;
}

export interface ScanResult {
  orphanedProjectMembers: number;
  orphanedTeamMembers: number;
  expiredRoleAssignments: number;
  disabledUsersWithActiveRoles: number;
  warnings: string[];
}

// ========== API ==========

export const permissionApi = {
  // ========== 角色分配 ==========
  assignRole: (data: AssignReqVO) =>
    http.post<never, RoleAssignmentVO>("/api/system/role-assignment/assign", data),

  revokeRole: (data: AssignReqVO) =>
    http.post<never, boolean>("/api/system/role-assignment/revoke", data),

  listAssignments: (userId: number, scopeType?: string, scopeId?: number) =>
    http.get<never, RoleAssignmentVO[]>("/api/system/role-assignment/list", {
      params: { userId, scopeType, scopeId },
    }),

  listTeamScopes: () =>
    http.get<never, ScopeOption[]>("/api/system/role-assignment/scopes/teams"),

  listProjectScopes: () =>
    http.get<never, ScopeOption[]>("/api/system/role-assignment/scopes/projects"),

  // ========== 菜单管理 ==========
  getMenuTree: () =>
    http.get<never, MenuTreeNode[]>("/api/system/menu/tree"),

  assignRoleMenus: (roleId: number, menuIds: number[]) =>
    http.post<never, boolean>(`/api/system/menu/role/${roleId}/assign`, menuIds),

  getRoleMenuIds: (roleId: number) =>
    http.get<never, number[]>(`/api/system/menu/role/${roleId}`),

  listRoles: () =>
    http.get<never, RoleVO[]>("/api/system/role/list"),

  // ========== 用户角色 ==========
  assignUserRole: (userId: number, roleId: number) =>
    http.post<never, boolean>("/api/system/user/assign-role", null, {
      params: { userId, roleId },
    }),

  removeUserRole: (userId: number, roleId: number) =>
    http.post<never, boolean>("/api/system/user/remove-role", null, {
      params: { userId, roleId },
    }),

  // ========== 监控与审计 ==========
  getMetrics: () =>
    http.get<never, PermissionMetrics>("/api/system/permission-monitor/metrics"),

  scan: () =>
    http.post<never, ScanResult>("/api/system/permission-monitor/scan"),

  cleanupExpired: () =>
    http.post<never, number>("/api/system/permission-monitor/cleanup-expired"),

  auditPage: (pageNo: number = 1, pageSize: number = 20) =>
    http.get<never, { records: PermissionAuditVO[]; total: number; current: number; size: number }>(
      "/api/system/permission-monitor/audit/page",
      { params: { pageNo, pageSize } }
    ),
};
