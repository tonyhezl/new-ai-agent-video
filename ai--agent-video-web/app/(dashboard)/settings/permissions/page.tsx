"use client";

import { useState, useEffect, useCallback } from "react";
import {
  Shield,
  Loader2,
  Search,
  Plus,
  X,
  Activity,
  AlertTriangle,
  Trash2,
  Clock,
  Save,
  ChevronRight,
} from "lucide-react";
import { toast } from "sonner";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { userApi } from "@/lib/api/user";
import { permissionApi } from "@/lib/api/permission";
import type { MenuTreeNode, PermissionMetrics, RoleVO, ScopeOption, ScanResult, PermissionAuditVO } from "@/lib/api/permission";
import { Checkbox } from "@/components/ui/checkbox";
import { usePermissionStore } from "@/lib/store/permission-store";
import type { UserRespVO } from "@/lib/api/types";
import { containerVariants, itemVariants } from "../_shared";

type Tab = "modules" | "users" | "audit" | "monitor";

export default function PermissionsPage() {
  const [tab, setTab] = useState<Tab>("users");
  const canManageMenus = usePermissionStore((state) => state.hasPermission("system:menu:manage"));
  const canMonitor = usePermissionStore((state) => state.hasPermission("system:config:manage"));
  const tabs: { key: Tab; label: string }[] = [
    ...(canManageMenus ? [{ key: "modules" as const, label: "模块权限" }] : []),
    { key: "users", label: "用户角色" },
    ...(canMonitor ? [
      { key: "audit" as const, label: "审计日志" },
      { key: "monitor" as const, label: "监控指标" },
    ] : []),
  ];
  const activeTab = tabs.some((item) => item.key === tab) ? tab : tabs[0].key;

  return (
    <motion.div
      className="max-w-[1100px]"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      <motion.div variants={itemVariants} className="mb-8">
        <h1 className="text-2xl font-bold tracking-tight">权限管理</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          管理用户角色、作用域授权、审计日志和权限监控。
        </p>
      </motion.div>

      <motion.div variants={itemVariants} className="mb-6 flex gap-2">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={cn(
              "rounded-lg px-4 py-2 text-sm font-medium transition-colors",
              activeTab === t.key
                ? "bg-primary/10 text-primary border border-primary/30"
                : "text-muted-foreground hover:bg-muted/30 border border-transparent"
            )}
          >
            {t.label}
          </button>
        ))}
      </motion.div>

      {activeTab === "modules" && <ModulesTab />}
      {activeTab === "users" && <UsersTab />}
      {activeTab === "audit" && <AuditTab />}
      {activeTab === "monitor" && <MonitorTab />}
    </motion.div>
  );
}

// ========== 模块权限 Tab ==========

function flattenMenuTree(nodes: MenuTreeNode[]): MenuTreeNode[] {
  return nodes.flatMap((node) => [node, ...flattenMenuTree(node.children || [])]);
}

function ModulesTab() {
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [tree, setTree] = useState<MenuTreeNode[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<number | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [roleList, menuTree] = await Promise.all([
          permissionApi.listRoles(),
          permissionApi.getMenuTree(),
        ]);
        if (cancelled) return;
        setRoles(roleList);
        setTree(menuTree);
        setSelectedRoleId(roleList[0]?.id ?? null);
      } catch (error) {
        if (!cancelled) toast.error(error instanceof Error ? error.message : "权限目录加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (selectedRoleId === null) return;
    let cancelled = false;
    void (async () => {
      try {
        const ids = await permissionApi.getRoleMenuIds(selectedRoleId);
        if (!cancelled) setSelectedIds(new Set(ids));
      } catch (error) {
        if (!cancelled) toast.error(error instanceof Error ? error.message : "角色权限加载失败");
      }
    })();
    return () => { cancelled = true; };
  }, [selectedRoleId]);

  const allNodes = flattenMenuTree(tree);
  const parentById = new Map<number, number>();
  for (const node of allNodes) {
    for (const child of node.children || []) parentById.set(child.id, node.id);
  }

  const toggleNode = (node: MenuTreeNode, checked: boolean) => {
    const next = new Set(selectedIds);
    const descendants = flattenMenuTree([node]).map((item) => item.id);
    if (checked) {
      descendants.forEach((id) => next.add(id));
      let parentId = parentById.get(node.id);
      while (parentId !== undefined) {
        next.add(parentId);
        parentId = parentById.get(parentId);
      }
    } else {
      descendants.forEach((id) => next.delete(id));
    }
    setSelectedIds(next);
  };

  const handleSave = async () => {
    if (selectedRoleId === null) return;
    setSaving(true);
    try {
      await permissionApi.assignRoleMenus(selectedRoleId, Array.from(selectedIds));
      toast.success("模块权限已保存");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="flex justify-center py-16"><Loader2 className="h-5 w-5 animate-spin text-muted-foreground" /></div>;
  }

  return (
    <motion.div variants={itemVariants} className="grid gap-6 lg:grid-cols-[280px_minmax(0,1fr)]">
      <section className="rounded-xl border border-border/30 bg-card/50 p-4">
        <h2 className="mb-3 text-sm font-semibold">角色</h2>
        <div className="space-y-1">
          {roles.map((role) => (
            <button
              key={role.id}
              type="button"
              onClick={() => setSelectedRoleId(role.id)}
              className={cn(
                "flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm transition-colors",
                selectedRoleId === role.id ? "bg-primary/10 text-primary" : "hover:bg-muted/20"
              )}
            >
              <span>{role.name}</span>
              <span className="text-xs text-muted-foreground">{role.code}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="rounded-xl border border-border/30 bg-card/50 overflow-hidden">
        <div className="flex items-center justify-between border-b border-border/20 px-5 py-4">
          <div>
            <h2 className="text-sm font-semibold">模块与动作权限</h2>
            <p className="mt-1 text-xs text-muted-foreground">勾选页面和动作后保存，权限缓存会立即失效。</p>
          </div>
          <button
            type="button"
            onClick={handleSave}
            disabled={selectedRoleId === null || saving}
            className="flex items-center gap-2 rounded-lg border border-primary/30 bg-primary/10 px-3 py-2 text-sm font-medium text-primary disabled:opacity-50"
          >
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            保存
          </button>
        </div>
        <div className="max-h-[620px] overflow-y-auto p-4">
          {tree.length === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">暂无权限目录</p>
          ) : (
            <div className="space-y-2">
              {tree.map((node) => (
                <PermissionTreeNode
                  key={node.id}
                  node={node}
                  level={0}
                  selectedIds={selectedIds}
                  onToggle={toggleNode}
                />
              ))}
            </div>
          )}
        </div>
      </section>
    </motion.div>
  );
}

function PermissionTreeNode({
  node,
  level,
  selectedIds,
  onToggle,
}: {
  node: MenuTreeNode;
  level: number;
  selectedIds: Set<number>;
  onToggle: (node: MenuTreeNode, checked: boolean) => void;
}) {
  return (
    <div>
      <label
        className="flex min-h-10 items-center gap-3 rounded-lg px-3 py-2 hover:bg-muted/15"
        style={{ paddingLeft: `${12 + level * 22}px` }}
      >
        {level > 0 ? <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/50" /> : null}
        <Checkbox
          checked={selectedIds.has(node.id)}
          onCheckedChange={(checked) => onToggle(node, checked)}
        />
        <span className="min-w-0 flex-1 text-sm">{node.title}</span>
        <span className="rounded border border-border/20 bg-muted/10 px-2 py-0.5 text-[11px] text-muted-foreground">
          {node.type === "BUTTON" ? "动作" : node.type === "PAGE" ? "页面" : "模块"}
        </span>
        {node.permissionCode && <code className="text-[11px] text-muted-foreground">{node.permissionCode}</code>}
      </label>
      {(node.children || []).map((child) => (
        <PermissionTreeNode
          key={child.id}
          node={child}
          level={level + 1}
          selectedIds={selectedIds}
          onToggle={onToggle}
        />
      ))}
    </div>
  );
}

// ========== 用户角色 Tab ==========

function UsersTab() {
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(true);
  const [users, setUsers] = useState<UserRespVO[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserRespVO | null>(null);

  const loadUsers = useCallback(() => {
    userApi
      .page({ pageNo: 1, pageSize: 100, username: keyword.trim() || undefined })
      .then((result) => setUsers(result.list))
      .catch(() => setUsers([]))
      .finally(() => setLoading(false));
  }, [keyword]);

  useEffect(() => {
    loadUsers();
  }, [loadUsers]);

  return (
    <motion.div variants={itemVariants} className="grid gap-6 md:grid-cols-[1fr_400px]">
      {/* 用户列表 */}
      <div className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm overflow-hidden">
        <div className="border-b border-border/20 p-4">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground/60" />
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索用户名"
              className="w-full rounded-lg border border-border/30 bg-card/40 py-2 pl-10 pr-4 text-sm outline-none focus:border-primary/40"
            />
          </div>
        </div>
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : users.length === 0 ? (
          <div className="py-12 text-center text-sm text-muted-foreground">暂无用户</div>
        ) : (
          <div className="max-h-[500px] overflow-y-auto">
            {users.map((user) => (
              <button
                key={user.id}
                onClick={() => setSelectedUser(user)}
                className={cn(
                  "flex w-full items-center justify-between border-b border-border/10 px-4 py-3 text-left transition-colors hover:bg-muted/20",
                  selectedUser?.id === user.id && "bg-primary/5"
                )}
              >
                <div>
                  <p className="text-sm font-medium">{user.username}</p>
                  <p className="text-xs text-muted-foreground">{user.nickname || "--"}</p>
                </div>
                <div className="flex gap-1">
                  {user.roles.map((role) => (
                    <span
                      key={role}
                      className={cn(
                        "rounded-full px-2 py-0.5 text-xs font-medium border",
                        role === "admin"
                          ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-600"
                          : "border-border/30 bg-muted/20 text-foreground/70"
                      )}
                    >
                      {role}
                    </span>
                  ))}
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* 角色分配面板 */}
      <div className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-5">
        {selectedUser ? (
          <RoleAssignmentPanel user={selectedUser} />
        ) : (
          <div className="flex h-full flex-col items-center justify-center py-12 text-muted-foreground">
            <Shield className="mb-3 h-8 w-8 opacity-40" />
            <p className="text-sm">选择左侧用户以管理角色</p>
          </div>
        )}
      </div>
    </motion.div>
  );
}

function RoleAssignmentPanel({ user }: { user: UserRespVO }) {
  const [assignments, setAssignments] = useState<{ roleId: number; scopeType: string; scopeId: number | null; status: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAssign, setShowAssign] = useState(false);
  const [roleId, setRoleId] = useState("");
  const [scopeType, setScopeType] = useState("SYSTEM");
  const [scopeId, setScopeId] = useState("");
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [teams, setTeams] = useState<ScopeOption[]>([]);
  const [projects, setProjects] = useState<ScopeOption[]>([]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const results = await Promise.allSettled([
        permissionApi.listRoles(),
        permissionApi.listTeamScopes(),
        permissionApi.listProjectScopes(),
      ]);
      if (cancelled) return;
      if (results[0].status === "fulfilled") setRoles(results[0].value);
      if (results[1].status === "fulfilled") setTeams(results[1].value);
      if (results[2].status === "fulfilled") setProjects(results[2].value);
    })();
    return () => { cancelled = true; };
  }, []);

  const loadAssignments = useCallback(() => {
    permissionApi
      .listAssignments(user.id)
      .then((list) =>
        setAssignments(
          list.map((a) => ({
            roleId: a.roleId,
            scopeType: a.scopeType,
            scopeId: a.scopeId,
            status: a.status,
          }))
        )
      )
      .catch(() => setAssignments([]))
      .finally(() => setLoading(false));
  }, [user.id]);

  useEffect(() => {
    loadAssignments();
  }, [loadAssignments]);

  const handleAssign = () => {
    const rid = Number(roleId);
    if (!rid || (scopeType !== "SYSTEM" && !scopeId)) {
      toast.error("请选择完整的角色和作用范围");
      return;
    }
    permissionApi
      .assignRole({
        userId: user.id,
        roleId: rid,
        scopeType,
        scopeId: scopeId ? Number(scopeId) : null,
      })
      .then(() => {
        setShowAssign(false);
        setRoleId("");
        setScopeId("");
        loadAssignments();
      })
      .then(() => toast.success("角色已分配"))
      .catch((err) => toast.error(`分配失败: ${err.message}`));
  };

  const handleRevoke = (rId: number, sType: string, sId: number | null) => {
    permissionApi
      .revokeRole({ userId: user.id, roleId: rId, scopeType: sType, scopeId: sId })
      .then(() => loadAssignments())
      .then(() => toast.success("角色已撤销"))
      .catch((err) => toast.error(`撤销失败: ${err.message}`));
  };

  const roleName = (id: number) => roles.find((role) => role.id === id)?.name ?? `角色 #${id}`;
  const scopeName = (type: string, id: number | null) => {
    if (type === "SYSTEM") return "全系统";
    if (type === "TEAM") return `团队：${teams.find((team) => team.id === id)?.name ?? `#${id}`}`;
    return `项目：${projects.find((project) => project.id === id)?.name ?? `#${id}`}`;
  };
  const scopeOptions: Array<{ id: number; name: string }> = scopeType === "TEAM" ? teams : projects;

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold">{user.username}</h3>
          <p className="text-xs text-muted-foreground">{user.nickname || "--"}</p>
        </div>
        <button
          onClick={() => setShowAssign(!showAssign)}
          className="flex items-center gap-1 rounded-lg bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary border border-primary/30 transition-colors hover:bg-primary/20"
        >
          <Plus className="h-3.5 w-3.5" />
          分配角色
        </button>
      </div>

      {showAssign && (
        <div className="mb-4 rounded-lg border border-border/30 bg-muted/10 p-3 space-y-2">
          <div>
            <label className="text-xs text-muted-foreground">角色</label>
            <select
              value={roleId}
              onChange={(e) => setRoleId(e.target.value)}
              className="mt-1 w-full rounded border border-border/30 bg-card/40 px-2 py-1.5 text-sm outline-none focus:border-primary/40"
            >
              <option value="">请选择角色</option>
              {roles.map((role) => <option key={role.id} value={role.id}>{role.name}（{role.code}）</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-muted-foreground">作用域</label>
            <select
              value={scopeType}
              onChange={(e) => {
                setScopeType(e.target.value);
                setScopeId("");
              }}
              className="mt-1 w-full rounded border border-border/30 bg-card/40 px-2 py-1.5 text-sm outline-none focus:border-primary/40"
            >
              <option value="SYSTEM">全系统</option>
              <option value="TEAM">指定团队</option>
              <option value="PROJECT">指定项目</option>
            </select>
          </div>
          {scopeType !== "SYSTEM" && (
            <div>
              <label className="text-xs text-muted-foreground">{scopeType === "TEAM" ? "团队" : "项目"}</label>
              <select
                value={scopeId}
                onChange={(e) => setScopeId(e.target.value)}
                className="mt-1 w-full rounded border border-border/30 bg-card/40 px-2 py-1.5 text-sm outline-none focus:border-primary/40"
              >
                <option value="">请选择{scopeType === "TEAM" ? "团队" : "项目"}</option>
                {scopeOptions.map((scope) => (
                  <option key={scope.id} value={scope.id}>{scope.name}</option>
                ))}
              </select>
            </div>
          )}
          <div className="flex gap-2 pt-1">
            <button
              onClick={handleAssign}
              className="flex-1 rounded-lg bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary border border-primary/30 hover:bg-primary/25"
            >
              确认分配
            </button>
            <button
              onClick={() => setShowAssign(false)}
              className="rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted/20"
            >
              取消
            </button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-6">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
        </div>
      ) : assignments.length === 0 ? (
        <p className="py-6 text-center text-xs text-muted-foreground">暂无作用域角色分配</p>
      ) : (
        <div className="space-y-2">
          {assignments.map((a, idx) => (
            <div
              key={idx}
              className="flex items-center justify-between rounded-lg border border-border/20 bg-muted/10 px-3 py-2"
            >
              <div className="text-xs">
                <span className="font-medium">{roleName(a.roleId)}</span>
                <span className="ml-2 text-muted-foreground">
                  {scopeName(a.scopeType, a.scopeId)}
                </span>
                <span
                  className={cn(
                    "ml-2 rounded-full px-1.5 py-0.5 text-[10px] font-medium",
                    a.status === "ACTIVE"
                      ? "bg-emerald-500/10 text-emerald-600"
                      : "bg-rose-500/10 text-rose-600"
                  )}
                >
                  {a.status}
                </span>
              </div>
              <button
                onClick={() => handleRevoke(a.roleId, a.scopeType, a.scopeId)}
                className="text-muted-foreground hover:text-rose-500"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ========== 审计日志 Tab ==========

function AuditTab() {
  const [loading, setLoading] = useState(true);
  const [records, setRecords] = useState<PermissionAuditVO[]>([]);
  const [pageNo, setPageNo] = useState(1);
  const [total, setTotal] = useState(0);
  const pageSize = 20;

  const load = useCallback(() => {
    permissionApi
      .auditPage(pageNo, pageSize)
      .then((resp) => {
        setRecords(resp.records);
        setTotal(resp.total);
      })
      .catch(() => setRecords([]))
      .finally(() => setLoading(false));
  }, [pageNo]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = Math.ceil(total / pageSize);

  return (
    <motion.div variants={itemVariants} className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm overflow-hidden">
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </div>
      ) : records.length === 0 ? (
        <div className="py-12 text-center text-sm text-muted-foreground">暂无审计日志</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-muted/20 text-muted-foreground">
              <tr>
                <th className="px-4 py-3 text-left font-medium">操作者</th>
                <th className="px-4 py-3 text-left font-medium">目标用户</th>
                <th className="px-4 py-3 text-left font-medium">动作</th>
                <th className="px-4 py-3 text-left font-medium">角色</th>
                <th className="px-4 py-3 text-left font-medium">作用域</th>
                <th className="px-4 py-3 text-left font-medium">时间</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r) => (
                <tr key={r.id} className="border-t border-border/20">
                  <td className="px-4 py-3 text-muted-foreground">#{r.operatorId}</td>
                  <td className="px-4 py-3 text-muted-foreground">#{r.targetUserId}</td>
                  <td className="px-4 py-3">
                    <span
                      className={cn(
                        "rounded-full px-2 py-0.5 text-xs font-medium",
                        r.action === "ASSIGN"
                          ? "bg-emerald-500/10 text-emerald-600"
                          : "bg-rose-500/10 text-rose-600"
                      )}
                    >
                      {r.action}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{r.roleId ? `#${r.roleId}` : "--"}</td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {r.scopeType}
                    {r.scopeId ? ` #${r.scopeId}` : ""}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">
                    {new Date(r.createTime).toLocaleString("zh-CN")}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-border/20 px-4 py-3">
          <span className="text-xs text-muted-foreground">
            共 {total} 条，第 {pageNo}/{totalPages} 页
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => setPageNo((p) => Math.max(1, p - 1))}
              disabled={pageNo <= 1}
              className="rounded-lg border border-border/30 px-3 py-1 text-xs disabled:opacity-40"
            >
              上一页
            </button>
            <button
              onClick={() => setPageNo((p) => Math.min(totalPages, p + 1))}
              disabled={pageNo >= totalPages}
              className="rounded-lg border border-border/30 px-3 py-1 text-xs disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </motion.div>
  );
}

// ========== 监控指标 Tab ==========

function MonitorTab() {
  const [metrics, setMetrics] = useState<PermissionMetrics | null>(null);
  const [scanResult, setScanResult] = useState<ScanResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [scanning, setScanning] = useState(false);
  const [cleaning, setCleaning] = useState(false);

  const loadMetrics = useCallback(() => {
    permissionApi
      .getMetrics()
      .then(setMetrics)
      .catch(() => setMetrics(null))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    loadMetrics();
  }, [loadMetrics]);

  const handleScan = () => {
    setScanning(true);
    permissionApi
      .scan()
      .then(setScanResult)
      .catch((err) => alert(`扫描失败: ${err.message}`))
      .finally(() => setScanning(false));
  };

  const handleCleanup = () => {
    setCleaning(true);
    permissionApi
      .cleanupExpired()
      .then((count) => {
        alert(`已清理 ${count} 条过期分配`);
        loadMetrics();
      })
      .catch((err) => alert(`清理失败: ${err.message}`))
      .finally(() => setCleaning(false));
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div variants={itemVariants} className="space-y-6">
      {/* 指标卡片 */}
      <div className="grid gap-4 md:grid-cols-3">
        <MetricCard
          icon={<Shield className="h-4 w-4 text-cyan-400" />}
          label="权限版本"
          value={metrics?.permissionVersion?.toString() ?? "--"}
        />
        <MetricCard
          icon={<X className="h-4 w-4 text-rose-500" />}
          label="403 拒绝次数"
          value={metrics?.deniedCount?.toString() ?? "--"}
        />
        <MetricCard
          icon={<Activity className="h-4 w-4 text-emerald-500" />}
          label="缓存命中率"
          value={metrics?.cacheHitRate ?? "--"}
        />
        <MetricCard
          icon={<Clock className="h-4 w-4 text-amber-500" />}
          label="过期角色分配"
          value={metrics?.expiredAssignments?.toString() ?? "--"}
        />
        <MetricCard
          icon={<Activity className="h-4 w-4 text-violet-400" />}
          label="缓存命中"
          value={metrics?.cacheHit?.toString() ?? "--"}
        />
        <MetricCard
          icon={<Activity className="h-4 w-4 text-orange-500" />}
          label="缓存未命中"
          value={metrics?.cacheMiss?.toString() ?? "--"}
        />
      </div>

      {/* 操作按钮 */}
      <div className="flex gap-3">
        <button
          onClick={handleScan}
          disabled={scanning}
          className="flex items-center gap-2 rounded-lg border border-border/30 bg-card/40 px-4 py-2 text-sm font-medium transition-colors hover:bg-muted/20 disabled:opacity-50"
        >
          {scanning ? <Loader2 className="h-4 w-4 animate-spin" /> : <AlertTriangle className="h-4 w-4 text-amber-500" />}
          数据完整性扫描
        </button>
        <button
          onClick={handleCleanup}
          disabled={cleaning}
          className="flex items-center gap-2 rounded-lg border border-border/30 bg-card/40 px-4 py-2 text-sm font-medium transition-colors hover:bg-muted/20 disabled:opacity-50"
        >
          {cleaning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4 text-rose-500" />}
          清理过期分配
        </button>
      </div>

      {/* 扫描结果 */}
      {scanResult && (
        <div className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-5">
          <h3 className="mb-3 text-sm font-semibold">扫描结果</h3>
          <div className="grid gap-3 md:grid-cols-2">
            <ScanItem label="孤儿项目成员" value={scanResult.orphanedProjectMembers} />
            <ScanItem label="孤儿团队成员" value={scanResult.orphanedTeamMembers} />
            <ScanItem label="过期角色分配" value={scanResult.expiredRoleAssignments} />
            <ScanItem label="禁用用户仍有活跃角色" value={scanResult.disabledUsersWithActiveRoles} />
          </div>
          {scanResult.warnings.length > 0 && (
            <div className="mt-4 space-y-1">
              <p className="text-xs font-medium text-amber-500">警告 ({scanResult.warnings.length})</p>
              {scanResult.warnings.slice(0, 10).map((w, idx) => (
                <p key={idx} className="text-xs text-muted-foreground">• {w}</p>
              ))}
            </div>
          )}
        </div>
      )}
    </motion.div>
  );
}

function MetricCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-5">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        {icon}
        {label}
      </div>
      <p className="mt-3 text-2xl font-semibold tracking-tight">{value}</p>
    </div>
  );
}

function ScanItem({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-border/20 bg-muted/10 px-3 py-2">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={cn("text-sm font-semibold", value > 0 ? "text-amber-500" : "text-emerald-500")}>
        {value}
      </span>
    </div>
  );
}
