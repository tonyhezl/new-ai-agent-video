"use client";

import { useState, useEffect, useCallback } from "react";
import { motion } from "framer-motion";
import { Plus, Trash2, Edit2, Check, X, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import {
  listModelPrices,
  createModelPrice,
  updateModelPrice,
  deleteModelPrice,
  type ImageModelPrice,
  type PriceReqVO,
} from "@/lib/api/admin-workbench";
import { aiModelApi, type AiModel } from "@/lib/api/ai-model";
import { containerVariants, itemVariants } from "../../_shared";

const RESOLUTION_OPTIONS = [
  { value: "1K", label: "1K" },
  { value: "2K", label: "2K" },
  { value: "3K", label: "3K" },
  { value: "4K", label: "4K" },
];

const formatResolution = (val?: string) => {
  if (!val) return "1K";
  const upper = val.toUpperCase();
  if (upper === "STANDARD" || upper === "FAST") return "1K";
  if (upper === "ULTRA") return "2K";
  return val;
};

export default function ModelPricesPage() {
  const [prices, setPrices] = useState<ImageModelPrice[]>([]);
  const [models, setModels] = useState<AiModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [editForm, setEditForm] = useState<PriceReqVO>({
    modelId: 0,
    qualityLevel: "1K",
    aspectRatio: "1:1",
    creditCost: 1,
    isDefault: false,
  });

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [priceList, modelList] = await Promise.all([
        listModelPrices(),
        aiModelApi.listByType(2),
      ]);
      setPrices(priceList);
      setModels(modelList);
    } catch (err) {
      toast.error("加载数据失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleSave = async (id: number | null) => {
    try {
      if (id) {
        await updateModelPrice(id, editForm);
        toast.success("已更新");
      } else {
        await createModelPrice(editForm);
        toast.success("已创建");
      }
      setEditingId(null);
      setShowAdd(false);
      loadData();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败");
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm("确认删除此价格配置？")) return;
    try {
      await deleteModelPrice(id);
      toast.success("已删除");
      loadData();
    } catch (err) {
      toast.error("删除失败");
    }
  };

  const startEdit = (price: ImageModelPrice) => {
    setEditingId(price.id);
    setEditForm({
      modelId: price.modelId,
      qualityLevel: price.qualityLevel,
      aspectRatio: price.aspectRatio,
      creditCost: price.creditCost,
      upstreamCostCny: price.upstreamCostCny,
      isDefault: price.isDefault === 1,
    });
  };

  const modelName = (modelId: number) =>
    models.find((m) => m.id === modelId)?.name || `#${modelId}`;

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="space-y-6"
    >
      <motion.div variants={itemVariants} className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold">模型价格管理</h1>
          <p className="text-sm text-muted-foreground mt-1">
            配置图片生成模型的创作点计费规则
          </p>
        </div>
        <button
          onClick={() => {
            setShowAdd(true);
            setEditingId(null);
            setEditForm({
              modelId: models[0]?.id || 0,
              qualityLevel: "1K",
              aspectRatio: "1:1",
              creditCost: 1,
              isDefault: false,
            });
          }}
          className="flex items-center gap-1.5 px-3 h-9 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
        >
          <Plus className="h-4 w-4" />
          新增价格
        </button>
      </motion.div>

      {/* 价格列表 */}
      <motion.div
        variants={itemVariants}
        className="rounded-xl border border-border/40 overflow-hidden"
      >
        <table className="w-full text-sm">
          <thead className="bg-muted/30 text-xs text-muted-foreground">
            <tr>
              <th className="text-left px-4 py-3 font-medium">模型</th>
              <th className="text-left px-4 py-3 font-medium">分辨率</th>
              <th className="text-left px-4 py-3 font-medium">比例</th>
              <th className="text-left px-4 py-3 font-medium">创作点</th>
              <th className="text-left px-4 py-3 font-medium">上游成本</th>
              <th className="text-left px-4 py-3 font-medium">默认</th>
              <th className="text-right px-4 py-3 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            {showAdd && (
              <tr className="border-t border-border/40 bg-cyan-500/5">
                <td className="px-4 py-2">
                  <select
                    value={editForm.modelId}
                    onChange={(e) =>
                      setEditForm({ ...editForm, modelId: Number(e.target.value) })
                    }
                    className="w-full bg-background border border-border/40 rounded px-2 py-1 text-xs"
                  >
                    {models.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="px-4 py-2">
                  <select
                    value={editForm.qualityLevel}
                    onChange={(e) =>
                      setEditForm({ ...editForm, qualityLevel: e.target.value })
                    }
                    className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                  >
                    {RESOLUTION_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="px-4 py-2">
                  <input
                    value={editForm.aspectRatio}
                    onChange={(e) =>
                      setEditForm({ ...editForm, aspectRatio: e.target.value })
                    }
                    className="w-20 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                  />
                </td>
                <td className="px-4 py-2">
                  <input
                    type="number"
                    value={editForm.creditCost}
                    onChange={(e) =>
                      setEditForm({ ...editForm, creditCost: Number(e.target.value) })
                    }
                    className="w-16 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                  />
                </td>
                <td className="px-4 py-2">
                  <input
                    type="number"
                    step="0.01"
                    value={editForm.upstreamCostCny || 0}
                    onChange={(e) =>
                      setEditForm({
                        ...editForm,
                        upstreamCostCny: Number(e.target.value),
                      })
                    }
                    className="w-20 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                  />
                </td>
                <td className="px-4 py-2">
                  <input
                    type="checkbox"
                    checked={editForm.isDefault || false}
                    onChange={(e) =>
                      setEditForm({ ...editForm, isDefault: e.target.checked })
                    }
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
            {prices.map((price) => (
              <tr key={price.id} className="border-t border-border/40 hover:bg-muted/20">
                {editingId === price.id ? (
                  <>
                    <td className="px-4 py-2">
                      <select
                        value={editForm.modelId}
                        onChange={(e) =>
                          setEditForm({ ...editForm, modelId: Number(e.target.value) })
                        }
                        className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                      >
                        {models.map((m) => (
                          <option key={m.id} value={m.id}>
                            {m.name}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="px-4 py-2">
                      <select
                        value={editForm.qualityLevel}
                        onChange={(e) =>
                          setEditForm({ ...editForm, qualityLevel: e.target.value })
                        }
                        className="bg-background border border-border/40 rounded px-2 py-1 text-xs"
                      >
                        {RESOLUTION_OPTIONS.map((opt) => (
                          <option key={opt.value} value={opt.value}>
                            {opt.label}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="px-4 py-2">
                      <input
                        value={editForm.aspectRatio}
                        onChange={(e) =>
                          setEditForm({ ...editForm, aspectRatio: e.target.value })
                        }
                        className="w-20 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                      />
                    </td>
                    <td className="px-4 py-2">
                      <input
                        type="number"
                        value={editForm.creditCost}
                        onChange={(e) =>
                          setEditForm({ ...editForm, creditCost: Number(e.target.value) })
                        }
                        className="w-16 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                      />
                    </td>
                    <td className="px-4 py-2">
                      <input
                        type="number"
                        step="0.01"
                        value={editForm.upstreamCostCny || 0}
                        onChange={(e) =>
                          setEditForm({
                            ...editForm,
                            upstreamCostCny: Number(e.target.value),
                          })
                        }
                        className="w-20 bg-background border border-border/40 rounded px-2 py-1 text-xs"
                      />
                    </td>
                    <td className="px-4 py-2">
                      <input
                        type="checkbox"
                        checked={editForm.isDefault || false}
                        onChange={(e) =>
                          setEditForm({ ...editForm, isDefault: e.target.checked })
                        }
                      />
                    </td>
                    <td className="px-4 py-2 text-right">
                      <button
                        onClick={() => handleSave(price.id)}
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
                    <td className="px-4 py-3">{modelName(price.modelId)}</td>
                    <td className="px-4 py-3 text-muted-foreground font-medium">
                      {formatResolution(price.qualityLevel)}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {price.aspectRatio}
                    </td>
                    <td className="px-4 py-3 font-medium text-cyan-400">
                      {price.creditCost}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      ¥{price.upstreamCostCny?.toFixed(2) || "-"}
                    </td>
                    <td className="px-4 py-3">
                      {price.isDefault === 1 && (
                        <span className="text-xs px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-400">
                          默认
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => startEdit(price)}
                        className="p-1 rounded hover:bg-muted/50 text-muted-foreground"
                      >
                        <Edit2 className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(price.id)}
                        className="p-1 rounded hover:bg-muted/50 text-red-400 ml-1"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </td>
                  </>
                )}
              </tr>
            ))}
            {prices.length === 0 && !showAdd && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-muted-foreground">
                  暂无价格配置，点击右上角新增
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </motion.div>
    </motion.div>
  );
}
