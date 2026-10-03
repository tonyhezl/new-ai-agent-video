"use client";

import { useEffect, useState, useCallback } from "react";
import { toast } from "sonner";
import {
  Wrench,
  Loader2,
  Plus,
  Trash2,
  Edit2,
  Filter,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import {
  capabilityRuleApi,
  RULE_MODEL_TYPE_OPTIONS,
  RULE_MODEL_TYPE_LABELS,
  type CapabilityRule,
  type CapabilityRuleCreateReq,
} from "@/lib/api/capability-rule";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogClose,
} from "@/components/ui/dialog";
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectGroup,
  SelectItem,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  containerVariants,
  itemVariants,
} from "../_shared";

// ============================================================
// 规则编辑 Dialog
// ============================================================

interface RuleDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  editingRule: CapabilityRule | null;
  onSaved: () => void;
}

function RuleDialog({ open, onOpenChange, editingRule, onSaved }: RuleDialogProps) {
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<CapabilityRuleCreateReq>({
    name: "",
    modelType: 3,
    capabilityConfig: "{}",
  });

  useEffect(() => {
    if (open) {
      if (editingRule) {
        setForm({
          name: editingRule.name,
          modelType: editingRule.modelType,
          platform: editingRule.platform || "",
          codeContains: editingRule.codeContains || "",
          codeStartsWith: editingRule.codeStartsWith || "",
          family: editingRule.family || "",
          protocol: editingRule.protocol || "",
          sortOrder: editingRule.sortOrder,
          status: editingRule.status,
          capabilityConfig: editingRule.capabilityConfig,
        });
      } else {
        setForm({
          name: "",
          modelType: 3,
          platform: "",
          codeContains: "",
          codeStartsWith: "",
          family: "",
          protocol: "",
          sortOrder: 0,
          status: 1,
          capabilityConfig: "{}",
        });
      }
    }
  }, [open, editingRule]);

  const updateField = <K extends keyof CapabilityRuleCreateReq>(
    key: K,
    value: CapabilityRuleCreateReq[K]
  ) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = async () => {
    if (!form.name.trim()) return;
    setSaving(true);
    try {
      const payload: CapabilityRuleCreateReq = {
        ...form,
        platform: form.platform?.trim() || undefined,
        codeContains: form.codeContains?.trim() || undefined,
        codeStartsWith: form.codeStartsWith?.trim() || undefined,
        family: form.family?.trim() || undefined,
        protocol: form.protocol?.trim() || undefined,
      };
      if (editingRule) {
        await capabilityRuleApi.update({ ...payload, id: editingRule.id });
      } else {
        await capabilityRuleApi.create(payload);
      }
      onSaved();
      onOpenChange(false);
    } catch (err) {
      console.error("保存能力规则失败:", err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg max-h-[calc(100vh-2rem)] flex flex-col overflow-hidden">
        <DialogHeader className="shrink-0">
          <DialogTitle>{editingRule ? "编辑能力规则" : "新建能力规则"}</DialogTitle>
          <DialogDescription>
            配置模型能力推断规则，按优先级匹配，首个命中即生效
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 overflow-y-auto min-h-0 px-1 -mx-1">
          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">规则名称</Label>
            <Input
              placeholder="例如：GoogleFlow R2V"
              value={form.name}
              onChange={(e) => updateField("name", e.target.value)}
              className="text-sm"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">模型类型</Label>
              <Select
                value={String(form.modelType)}
                onValueChange={(v) => updateField("modelType", Number(v))}
                items={RULE_MODEL_TYPE_OPTIONS.map((o) => ({ value: String(o.value), label: o.label }))}
              >
                <SelectTrigger className="w-full text-sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="text-sm">
                  <SelectGroup>
                    {RULE_MODEL_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={String(opt.value)} className="text-sm">
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">排序（越小越优先）</Label>
              <Input
                type="number"
                value={form.sortOrder ?? 0}
                onChange={(e) => updateField("sortOrder", Number(e.target.value))}
                className="text-sm font-mono"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">匹配平台（留空=不限）</Label>
            <Input
              placeholder="例如：googleflowreverseapi"
              value={form.platform || ""}
              onChange={(e) => updateField("platform", e.target.value)}
              className="text-sm font-mono"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">Code 包含（留空=不限）</Label>
              <Input
                placeholder="例如：r2v"
                value={form.codeContains || ""}
                onChange={(e) => updateField("codeContains", e.target.value)}
                className="text-sm font-mono"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">Code 前缀（留空=不限）</Label>
              <Input
                placeholder="例如：gpt-image"
                value={form.codeStartsWith || ""}
                onChange={(e) => updateField("codeStartsWith", e.target.value)}
                className="text-sm font-mono"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">Family（留空=不限）</Label>
              <Input
                placeholder="例如：agnes"
                value={form.family || ""}
                onChange={(e) => updateField("family", e.target.value)}
                className="text-sm font-mono"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">Protocol（留空=不限）</Label>
              <Input
                placeholder="例如：jimeng"
                value={form.protocol || ""}
                onChange={(e) => updateField("protocol", e.target.value)}
                className="text-sm font-mono"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">能力配置 JSON</Label>
            <Textarea
              placeholder='{"supportFirstFrame":true,...}'
              value={form.capabilityConfig}
              onChange={(e) => updateField("capabilityConfig", e.target.value)}
              spellCheck={false}
              className="min-h-32 max-h-60 overflow-auto resize-none font-mono text-xs leading-relaxed"
            />
          </div>
        </div>

        <DialogFooter className="shrink-0">
          <DialogClose render={<Button variant="outline" size="sm" />}>
            取消
          </DialogClose>
          <Button size="sm" onClick={handleSave} disabled={saving || !form.name.trim()}>
            {saving && <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" />}
            {editingRule ? "保存" : "创建"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================================
// 能力配置可视化
// ============================================================

function parseConfig(json: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(json);
    return typeof parsed === "object" && parsed !== null ? parsed : {};
  } catch {
    return {};
  }
}

function CapabilityChips({ config }: { config: string }) {
  const cfg = parseConfig(config);
  const chips: { label: string; tone: "positive" | "muted" | "info" }[] = [];

  const getBool = (key: string) => {
    const v = cfg[key];
    return typeof v === "boolean" ? v : typeof v === "string" ? v === "true" : false;
  };
  const getNum = (key: string): number | undefined => {
    const v = cfg[key];
    if (typeof v === "number") return v;
    if (v === null) return undefined;
    return undefined;
  };

  if (cfg.supportReferenceImages !== undefined || cfg.supportFirstFrame !== undefined) {
    // Video config
    chips.push(
      { label: getBool("supportFirstFrame") ? "首帧" : "无首帧", tone: getBool("supportFirstFrame") ? "positive" : "muted" },
      { label: getBool("supportLastFrame") ? "尾帧" : "无尾帧", tone: getBool("supportLastFrame") ? "positive" : "muted" },
      {
        label: getBool("supportReferenceImages")
          ? getNum("maxReferenceImages") ? `参考图 ≤${getNum("maxReferenceImages")}` : "参考图"
          : "无参考图",
        tone: getBool("supportReferenceImages") ? "positive" : "muted",
      },
      {
        label: getBool("supportReferenceVideos") ? "参考视频" : "无参考视频",
        tone: getBool("supportReferenceVideos") ? "positive" : "muted",
      },
      {
        label: getBool("supportReferenceAudios") ? "参考音频" : "无参考音频",
        tone: getBool("supportReferenceAudios") ? "positive" : "muted",
      },
    );
  } else {
    // Image config
    chips.push(
      getBool("supportReferenceImages")
        ? { label: getNum("maxReferenceImages") ? `参考图 ≤${getNum("maxReferenceImages")}` : "支持参考图", tone: "positive" as const }
        : { label: "仅文生图", tone: "muted" as const },
    );
  }

  const getChipClass = (tone: "positive" | "muted" | "info") => {
    switch (tone) {
      case "positive":
        return "bg-emerald-500/10 text-emerald-500 border-emerald-500/20";
      case "info":
        return "bg-sky-500/10 text-sky-500 border-sky-500/20";
      default:
        return "bg-muted/60 text-muted-foreground border-border/40";
    }
  };

  return (
    <div className="flex flex-wrap gap-1">
      {chips.map((chip, i) => (
        <span
          key={i}
          className={cn(
            "inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] leading-none",
            getChipClass(chip.tone)
          )}
        >
          {chip.label}
        </span>
      ))}
    </div>
  );
}

// ============================================================
// 主页面
// ============================================================

export default function CapabilityRulesPage() {
  const [rules, setRules] = useState<CapabilityRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterType, setFilterType] = useState<number | undefined>(undefined);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<CapabilityRule | null>(null);

  const loadRules = useCallback(async () => {
    setLoading(true);
    try {
      const data = await capabilityRuleApi.list(filterType);
      setRules(data);
    } catch (err) {
      console.error("加载能力规则失败:", err);
    } finally {
      setLoading(false);
    }
  }, [filterType]);

  useEffect(() => {
    loadRules();
  }, [loadRules]);

  const handleDelete = async (id: number) => {
    if (!confirm("确定要删除该能力规则吗？")) return;
    try {
      await capabilityRuleApi.delete(id);
      await loadRules();
    } catch (err) {
      console.error("删除规则失败:", err);
    }
  };

  const handleToggleStatus = async (rule: CapabilityRule) => {
    try {
      await capabilityRuleApi.update({
        id: rule.id,
        name: rule.name,
        modelType: rule.modelType,
        platform: rule.platform || undefined,
        codeContains: rule.codeContains || undefined,
        codeStartsWith: rule.codeStartsWith || undefined,
        family: rule.family || undefined,
        protocol: rule.protocol || undefined,
        sortOrder: rule.sortOrder,
        status: rule.status === 1 ? 0 : 1,
        capabilityConfig: rule.capabilityConfig,
      });
      await loadRules();
    } catch (err) {
      console.error("切换状态失败:", err);
    }
  };

  // Group by modelType
  const grouped = [
    { type: 3, label: "视频生成" },
    { type: 2, label: "图片生成" },
  ]
    .map((g) => ({
      ...g,
      rules: rules.filter((r) => r.modelType === g.type),
    }))
    .filter((g) => g.rules.length > 0);

  return (
    <motion.div
      className="max-w-[1000px]"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      <motion.div variants={itemVariants} className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight">能力规则管理</h1>
        <p className="text-muted-foreground mt-1">
          管理模型能力推断规则，按优先级匹配，首个命中即生效
        </p>
      </motion.div>

      <motion.div variants={itemVariants} className="mb-6">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-muted-foreground" />
            <Select
              value={filterType !== undefined ? String(filterType) : "all"}
              onValueChange={(v) => setFilterType(v === "all" ? undefined : Number(v))}
              items={[
                { value: "all", label: "全部" },
                ...RULE_MODEL_TYPE_OPTIONS.map((o) => ({ value: String(o.value), label: o.label })),
              ]}
            >
              <SelectTrigger className="w-[140px] text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="text-sm">
                <SelectGroup>
                  <SelectItem value="all" className="text-sm">全部</SelectItem>
                  {RULE_MODEL_TYPE_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={String(opt.value)} className="text-sm">
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </div>
          <button
            type="button"
            onClick={() => { setEditingRule(null); setDialogOpen(true); }}
            className={cn(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium",
              "border border-dashed border-border/40 hover:border-primary/50",
              "text-muted-foreground hover:text-primary",
              "transition-all duration-200"
            )}
          >
            <Plus className="h-3.5 w-3.5" />
            添加规则
          </button>
        </div>
      </motion.div>

      {loading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </div>
      ) : grouped.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border/30 py-10 text-center bg-card/30">
          <Wrench className="h-8 w-8 text-muted-foreground/20 mx-auto mb-2" />
          <p className="text-sm text-muted-foreground">还没有能力规则</p>
          <p className="text-xs text-muted-foreground/60 mt-1">点击上方「添加规则」开始</p>
        </div>
      ) : (
        <div className="space-y-6">
          {grouped.map((group) => (
            <motion.div key={group.type} variants={itemVariants}>
              <div className="flex items-center gap-2 mb-3 px-1">
                <h3 className="text-sm font-medium text-muted-foreground uppercase tracking-wider">
                  {group.label}
                </h3>
                <div className="flex-1 h-px bg-border/15" />
                <span className="text-[10px] text-muted-foreground/50">{group.rules.length} 条</span>
              </div>

              <div className="space-y-2">
                {group.rules.map((rule) => (
                  <div
                    key={rule.id}
                    className={cn(
                      "flex items-start gap-3 px-4 py-3 rounded-xl border",
                      "bg-card/50 backdrop-blur-sm border-border/50",
                      rule.status !== 1 && "opacity-50"
                    )}
                  >
                    <div className="h-8 w-8 rounded-md bg-amber-500/10 flex items-center justify-center shrink-0 mt-0.5">
                      <Wrench className="h-4 w-4 text-amber-500/60" />
                    </div>

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <p className="text-sm font-medium">{rule.name}</p>
                        <span className="px-1.5 py-0.5 rounded bg-muted/50 text-[10px] text-muted-foreground font-mono">
                          #{rule.sortOrder}
                        </span>
                        <span className="px-1.5 py-0.5 rounded bg-amber-500/10 text-[10px] text-amber-600">
                          {RULE_MODEL_TYPE_LABELS[rule.modelType]}
                        </span>
                        <button
                          onClick={() => handleToggleStatus(rule)}
                          className={cn(
                            "px-1.5 py-0.5 rounded text-[10px] font-medium transition-colors",
                            rule.status === 1
                              ? "bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20"
                              : "bg-muted/40 text-muted-foreground hover:bg-muted/60"
                          )}
                        >
                          {rule.status === 1 ? "启用" : "禁用"}
                        </button>
                      </div>

                      <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground mt-1 flex-wrap">
                        {rule.platform && (
                          <span className="font-mono px-1 py-0.5 rounded bg-muted/40">{rule.platform}</span>
                        )}
                        {rule.codeContains && (
                          <span className="font-mono px-1 py-0.5 rounded bg-sky-500/10 text-sky-500">
                            contains: {rule.codeContains}
                          </span>
                        )}
                        {rule.codeStartsWith && (
                          <span className="font-mono px-1 py-0.5 rounded bg-sky-500/10 text-sky-500">
                            starts: {rule.codeStartsWith}
                          </span>
                        )}
                        {rule.family && (
                          <span className="font-mono px-1 py-0.5 rounded bg-violet-500/10 text-violet-600">
                            family: {rule.family}
                          </span>
                        )}
                        {rule.protocol && (
                          <span className="font-mono px-1 py-0.5 rounded bg-violet-500/10 text-violet-600">
                            protocol: {rule.protocol}
                          </span>
                        )}
                        {!rule.platform && !rule.codeContains && !rule.codeStartsWith && !rule.family && !rule.protocol && (
                          <span className="text-muted-foreground/40">无条件（兜底匹配）</span>
                        )}
                      </div>

                      <div className="mt-1.5">
                        <CapabilityChips config={rule.capabilityConfig} />
                      </div>
                    </div>

                    <div className="flex items-center gap-1 shrink-0 mt-0.5">
                      <button
                        onClick={() => { setEditingRule(rule); setDialogOpen(true); }}
                        className="p-1.5 rounded-md text-emerald-500 hover:text-emerald-600 hover:bg-emerald-500/10 transition-colors"
                        title="编辑规则"
                      >
                        <Edit2 className="h-3.5 w-3.5" />
                      </button>
                      <button
                        onClick={() => handleDelete(rule.id)}
                        className="p-1.5 rounded-md text-rose-500 hover:text-rose-600 hover:bg-rose-500/10 transition-colors"
                        title="删除规则"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </motion.div>
          ))}
        </div>
      )}

      <RuleDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        editingRule={editingRule}
        onSaved={loadRules}
      />
    </motion.div>
  );
}
