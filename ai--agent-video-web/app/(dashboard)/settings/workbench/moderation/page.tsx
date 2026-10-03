"use client";

import { useState, useEffect, useCallback } from "react";
import { motion } from "framer-motion";
import { Plus, Trash2, Edit2, Check, X, Loader2, RefreshCw, ShieldAlert } from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import {
  listModerationWords,
  createModerationWord,
  updateModerationWord,
  enableModerationWord,
  disableModerationWord,
  reloadModerationWords,
  listModerationHits,
  type ModerationWord,
  type ModerationHitLog,
  type ModerationWordReqVO,
} from "@/lib/api/admin-workbench";
import { containerVariants, itemVariants } from "../../_shared";

const CATEGORIES = ["politics", "porn", "violence", "abuse", "spam", "other"];
const CATEGORY_LABELS: Record<string, string> = {
  politics: "政治",
  porn: "色情",
  violence: "暴力",
  abuse: "辱骂",
  spam: "广告",
  other: "其他",
};

export default function ModerationWordsPage() {
  const [words, setWords] = useState<ModerationWord[]>([]);
  const [hits, setHits] = useState<ModerationHitLog[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNo, setPageNo] = useState(1);
  const [loading, setLoading] = useState(true);
  const [filterCategory, setFilterCategory] = useState("");
  const [filterEnabled, setFilterEnabled] = useState<number | "">("");
  const [filterKeyword, setFilterKeyword] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [editForm, setEditForm] = useState<ModerationWordReqVO>({
    word: "",
    category: "other",
    matchMode: "contains",
    severity: 2,
  });
  const [tab, setTab] = useState<"words" | "hits">("words");

  const loadWords = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await listModerationWords({
        category: filterCategory || undefined,
        enabled: filterEnabled === "" ? undefined : filterEnabled,
        keyword: filterKeyword || undefined,
        pageNo,
        pageSize: 20,
      });
      setWords(resp.list);
      setTotal(resp.total);
    } catch {
      toast.error("加载审核词失败");
    } finally {
      setLoading(false);
    }
  }, [filterCategory, filterEnabled, filterKeyword, pageNo]);

  const loadHits = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await listModerationHits({ pageNo: 1, pageSize: 50 });
      setHits(resp.list);
      setTotal(resp.total);
    } catch {
      toast.error("加载命中日志失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (tab === "words") loadWords();
    else loadHits();
  }, [tab, loadWords, loadHits]);

  const handleSave = async (id: number | null) => {
    if (!editForm.word.trim()) {
      toast.error("审核词不能为空");
      return;
    }
    try {
      if (id) {
        await updateModerationWord(id, editForm);
        toast.success("已更新");
      } else {
        await createModerationWord(editForm);
        toast.success("已创建");
      }
      setEditingId(null);
      setShowAdd(false);
      loadWords();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败");
    }
  };

  const handleToggle = async (word: ModerationWord) => {
    try {
      if (word.enabled === 1) {
        await disableModerationWord(word.id);
        toast.success("已停用");
      } else {
        await enableModerationWord(word.id);
        toast.success("已启用");
      }
      loadWords();
    } catch {
      toast.error("操作失败");
    }
  };

  const handleReload = async () => {
    try {
      await reloadModerationWords();
      toast.success("词表缓存已刷新");
    } catch {
      toast.error("刷新失败");
    }
  };

  const startEdit = (word: ModerationWord) => {
    setEditingId(word.id);
    setEditForm({
      word: word.word,
      category: word.category || "other",
      matchMode: word.matchMode,
      severity: word.severity,
      remark: word.remark,
    });
  };

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="space-y-6"
    >
      <motion.div variants={itemVariants} className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold">审核词库管理</h1>
          <p className="text-sm text-muted-foreground mt-1">
            管理提示词审核敏感词及查看命中记录
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleReload}
            className="flex items-center gap-1.5 px-3 h-9 rounded-lg border border-border/40 text-sm hover:border-border/80 transition-colors"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            刷新缓存
          </button>
          {tab === "words" && (
            <button
              onClick={() => {
                setShowAdd(true);
                setEditingId(null);
                setEditForm({
                  word: "",
                  category: "other",
                  matchMode: "contains",
                  severity: 2,
                });
              }}
              className="flex items-center gap-1.5 px-3 h-9 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
            >
              <Plus className="h-4 w-4" />
              新增审核词
            </button>
          )}
        </div>
      </motion.div>

      {/* Tab 切换 */}
      <div className="flex gap-1 border-b border-border/40">
        <button
          onClick={() => setTab("words")}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 transition-colors",
            tab === "words"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          审核词列表
        </button>
        <button
          onClick={() => setTab("hits")}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 transition-colors",
            tab === "hits"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          命中日志
        </button>
      </div>

      {/* 筛选栏 */}
      {tab === "words" && (
        <div className="flex items-center gap-3 flex-wrap">
          <select
            value={filterCategory}
            onChange={(e) => {
              setFilterCategory(e.target.value);
              setPageNo(1);
            }}
            className="bg-background border border-border/40 rounded-lg px-3 py-2 text-sm"
          >
            <option value="">全部分类</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABELS[c]}
              </option>
            ))}
          </select>
          <select
            value={filterEnabled}
            onChange={(e) => {
              setFilterEnabled(e.target.value === "" ? "" : Number(e.target.value));
              setPageNo(1);
            }}
            className="bg-background border border-border/40 rounded-lg px-3 py-2 text-sm"
          >
            <option value="">全部状态</option>
            <option value="1">已启用</option>
            <option value="0">已停用</option>
          </select>
          <input
            placeholder="搜索关键词..."
            value={filterKeyword}
            onChange={(e) => {
              setFilterKeyword(e.target.value);
              setPageNo(1);
            }}
            className="bg-background border border-border/40 rounded-lg px-3 py-2 text-sm w-48"
          />
        </div>
      )}

      {/* 内容区 */}
      {loading ? (
        <div className="flex items-center justify-center h-64">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : tab === "words" ? (
        <div className="rounded-xl border border-border/40 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-muted/30 text-xs text-muted-foreground">
              <tr>
                <th className="text-left px-4 py-3 font-medium">审核词</th>
                <th className="text-left px-4 py-3 font-medium">分类</th>
                <th className="text-left px-4 py-3 font-medium">匹配模式</th>
                <th className="text-left px-4 py-3 font-medium">严重度</th>
                <th className="text-left px-4 py-3 font-medium">状态</th>
                <th className="text-left px-4 py-3 font-medium">备注</th>
                <th className="text-right px-4 py-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {showAdd && (
                <tr className="border-t border-border/40 bg-cyan-500/5">
                  <td className="px-4 py-2">
                    <input
                      value={editForm.word}
                      onChange={(e) => setEditForm({ ...editForm, word: e.target.value })}
                      placeholder="输入审核词"
                      className="w-full bg-background border border-border/40 rounded px-2 py-1 text-xs"
                    />
                  </td>
                  <td className="px-4 py-2">
                    <select
                      value={editForm.category}
                      onChange={(e) => setEditForm({ ...editForm, category: e.target.value })}
                      className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                    >
                      {CATEGORIES.map((c) => (
                        <option key={c} value={c}>
                          {CATEGORY_LABELS[c]}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="px-4 py-2">
                    <select
                      value={editForm.matchMode}
                      onChange={(e) => setEditForm({ ...editForm, matchMode: e.target.value })}
                      className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                    >
                      <option value="contains">包含</option>
                      <option value="exact">精确</option>
                    </select>
                  </td>
                  <td className="px-4 py-2">
                    <input
                      type="number"
                      min={1}
                      max={3}
                      value={editForm.severity || 2}
                      onChange={(e) =>
                        setEditForm({ ...editForm, severity: Number(e.target.value) })
                      }
                      className="w-16 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                    />
                  </td>
                  <td className="px-4 py-2 text-muted-foreground">—</td>
                  <td className="px-4 py-2">
                    <input
                      value={editForm.remark || ""}
                      onChange={(e) => setEditForm({ ...editForm, remark: e.target.value })}
                      className="w-full bg-background border border-border/40 rounded px-2 py-1 text-xs"
                    />
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => handleSave(null)}
                      className="p-1 rounded hover:bg-muted/50 text-green-400"
                    >
                      <Check className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => setShowAdd(false)}
                      className="p-1 rounded hover:bg-muted/50 text-muted-foreground ml-1"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              )}
              {words.map((word) => (
                <tr key={word.id} className="border-t border-border/40 hover:bg-muted/20">
                  {editingId === word.id ? (
                    <>
                      <td className="px-4 py-2">
                        <input
                          value={editForm.word}
                          onChange={(e) => setEditForm({ ...editForm, word: e.target.value })}
                          className="w-full bg-background border border-border/40 rounded px-2 py-1 text-xs"
                        />
                      </td>
                      <td className="px-4 py-2">
                        <select
                          value={editForm.category}
                          onChange={(e) => setEditForm({ ...editForm, category: e.target.value })}
                          className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                        >
                          {CATEGORIES.map((c) => (
                            <option key={c} value={c}>
                              {CATEGORY_LABELS[c]}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-2">
                        <select
                          value={editForm.matchMode}
                          onChange={(e) => setEditForm({ ...editForm, matchMode: e.target.value })}
                          className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                        >
                          <option value="contains">包含</option>
                          <option value="exact">精确</option>
                        </select>
                      </td>
                      <td className="px-4 py-2">
                        <input
                          type="number"
                          min={1}
                          max={3}
                          value={editForm.severity || 2}
                          onChange={(e) =>
                            setEditForm({ ...editForm, severity: Number(e.target.value) })
                          }
                          className="w-16 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                        />
                      </td>
                      <td className="px-4 py-2 text-muted-foreground">—</td>
                      <td className="px-4 py-2">
                        <input
                          value={editForm.remark || ""}
                          onChange={(e) => setEditForm({ ...editForm, remark: e.target.value })}
                          className="w-full bg-background border border-border/40 rounded px-2 py-1 text-xs"
                        />
                      </td>
                      <td className="px-4 py-2 text-right">
                        <button
                          onClick={() => handleSave(word.id)}
                          className="p-1 rounded hover:bg-muted/50 text-green-400"
                        >
                          <Check className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => setEditingId(null)}
                          className="p-1 rounded hover:bg-muted/50 text-muted-foreground ml-1"
                        >
                          <X className="h-4 w-4" />
                        </button>
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="px-4 py-3 font-mono text-xs">{word.word}</td>
                      <td className="px-4 py-3">
                        <span className="text-xs px-1.5 py-0.5 rounded bg-muted/40">
                          {word.category ? CATEGORY_LABELS[word.category] || word.category : "-"}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground text-xs">
                        {word.matchMode === "exact" ? "精确" : "包含"}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            "text-xs px-1.5 py-0.5 rounded",
                            word.severity >= 3
                              ? "bg-red-500/10 text-red-400"
                              : word.severity >= 2
                              ? "bg-amber-500/10 text-amber-400"
                              : "bg-muted/40 text-muted-foreground"
                          )}
                        >
                          L{word.severity}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <button
                          onClick={() => handleToggle(word)}
                          className={cn(
                            "text-xs px-2 py-0.5 rounded transition-colors",
                            word.enabled === 1
                              ? "bg-green-500/10 text-green-400"
                              : "bg-muted/40 text-muted-foreground"
                          )}
                        >
                          {word.enabled === 1 ? "启用" : "停用"}
                        </button>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground text-xs">
                        {word.remark || "-"}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          onClick={() => startEdit(word)}
                          className="p-1 rounded hover:bg-muted/50 text-muted-foreground"
                        >
                          <Edit2 className="h-4 w-4" />
                        </button>
                      </td>
                    </>
                  )}
                </tr>
              ))}
              {words.length === 0 && !showAdd && (
                <tr>
                  <td colSpan={7} className="px-4 py-12 text-center text-muted-foreground">
                    暂无审核词
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          {/* 分页 */}
          {total > 20 && (
            <div className="flex items-center justify-between px-4 py-3 border-t border-border/40 text-xs text-muted-foreground">
              <span>共 {total} 条</span>
              <div className="flex gap-1">
                <button
                  onClick={() => setPageNo((p) => Math.max(1, p - 1))}
                  disabled={pageNo <= 1}
                  className="px-2 py-1 rounded border border-border/40 disabled:opacity-40"
                >
                  上一页
                </button>
                <span className="px-2 py-1">{pageNo}</span>
                <button
                  onClick={() => setPageNo((p) => p + 1)}
                  disabled={pageNo * 20 >= total}
                  className="px-2 py-1 rounded border border-border/40 disabled:opacity-40"
                >
                  下一页
                </button>
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="rounded-xl border border-border/40 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-muted/30 text-xs text-muted-foreground">
              <tr>
                <th className="text-left px-4 py-3 font-medium">命中词</th>
                <th className="text-left px-4 py-3 font-medium">分类</th>
                <th className="text-left px-4 py-3 font-medium">提示词片段</th>
                <th className="text-left px-4 py-3 font-medium">IP</th>
                <th className="text-left px-4 py-3 font-medium">时间</th>
              </tr>
            </thead>
            <tbody>
              {hits.map((hit) => (
                <tr key={hit.id} className="border-t border-border/40 hover:bg-muted/20">
                  <td className="px-4 py-3 font-mono text-xs text-red-400">
                    {hit.hitWord || "-"}
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-xs px-1.5 py-0.5 rounded bg-muted/40">
                      {hit.hitCategory ? CATEGORY_LABELS[hit.hitCategory] || hit.hitCategory : "-"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs max-w-xs truncate">
                    {hit.promptText || "-"}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs font-mono">
                    {hit.clientIp || "-"}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">
                    {hit.createTime || "-"}
                  </td>
                </tr>
              ))}
              {hits.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center text-muted-foreground">
                    <ShieldAlert className="h-8 w-8 mx-auto mb-2 opacity-30" />
                    暂无命中记录
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </motion.div>
  );
}
