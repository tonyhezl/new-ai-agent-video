"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  Boxes,
  CheckCircle2,
  Eye,
  EyeOff,
  LayoutTemplate,
  Loader2,
  Plus,
  Save,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { AssetWithItems } from "@/lib/api/asset";
import {
  storyboardApi,
  type SceneContinuityMode,
  type FixedFurnitureArrangement,
  type FixedFurnitureLayout,
  type SceneLayoutAnchor,
  type SceneObjectState,
  type SceneStateChange,
  type SceneStateOperation,
  type SceneStateSnapshot,
  type StoryboardItem,
  type StoryboardScene,
  type StoryboardSceneStateTimeline,
} from "@/lib/api/storyboard";
import { cn } from "@/lib/utils";

type EditorTab = "layout" | "initial" | "shots";

interface SceneStateTimelineDialogProps {
  open: boolean;
  scene: StoryboardScene | null;
  items: StoryboardItem[];
  assetsList: AssetWithItems[];
  onOpenChange: (open: boolean) => void;
  onSaved?: (timeline: StoryboardSceneStateTimeline) => void;
}

interface ObjectOption {
  value: string;
  label: string;
}

const operationLabels: Record<SceneStateOperation, string> = {
  UPSERT: "写入完整状态",
  ADD: "进入场景",
  MOVE: "移动",
  SET_STATE: "改变状态",
  SHOW: "显示",
  HIDE: "隐藏",
  REMOVE: "移出场景",
};

const anchorKindLabels: Record<string, string> = {
  door: "门",
  window: "窗",
  fixed_furniture: "固定家具",
  zone: "区域",
  fixed: "其他固定结构",
};

const furnitureArrangementLabels: Record<FixedFurnitureArrangement, string> = {
  grid: "规则网格",
  row: "成排排列",
  line: "线性排列",
  cluster: "固定分组",
  custom: "按参考图",
};

const continuityModeLabels: Record<SceneContinuityMode, string> = {
  CUT: "切镜重构图",
  SEAMLESS: "无缝承接",
};

const operations = Object.keys(operationLabels) as SceneStateOperation[];

function selectedOptionLabel(options: ObjectOption[], value: string | null | undefined, fallback: string) {
  return options.find((option) => option.value === value)?.label || fallback;
}

function createAnchorId() {
  return `anchor-${crypto.randomUUID()}`;
}

function createFurnitureLayout(): FixedFurnitureLayout {
  return {
    arrangement: "custom",
    rows: null,
    columns: null,
    aisleDescription: null,
    empty: false,
    prohibitedFeatures: [],
  };
}

function toOptionalPositiveInteger(value: string) {
  if (!value.trim()) return null;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function splitProhibitedFeatures(value: string) {
  return value
    .split(/[、,，]/)
    .map((feature) => feature.trim())
    .filter(Boolean);
}

function assetItemLabel(asset: AssetWithItems, item: AssetWithItems["items"][number], fallback: string) {
  const assetName = asset.name?.trim() || fallback;
  const itemName = item.name?.trim() || "场景设定";
  return `${assetName} · ${itemName}`;
}

function cloneTimeline(value: StoryboardSceneStateTimeline) {
  return structuredClone(value);
}

function objectLabel(object: SceneObjectState) {
  const details = [object.location, object.state].filter(Boolean).join(" · ");
  return `${object.objectName || object.objectId}${details ? ` (${details})` : ""}`;
}

function materializeShots(timeline: StoryboardSceneStateTimeline) {
  let current = structuredClone(timeline.initialState);
  return timeline.shots.map((shot) => {
    const before = structuredClone(current);
    const objects = new Map(before.objects.map((object) => [object.objectId, { ...object }]));
    shot.delta.changes.forEach((change) => {
      const target = objects.get(change.objectId) || {
        objectId: change.objectId,
        objectName: change.objectName || change.objectId,
        location: null,
        state: null,
        containerId: null,
        canContainObjects: false,
        visible: true,
      };
      if (change.objectName) target.objectName = change.objectName;
      if (change.location) target.location = change.location;
      if (change.state) target.state = change.state;
      if (change.containerId !== null) target.containerId = change.containerId;
      if (change.operation === "MOVE" && change.containerId === null) target.containerId = null;
      if (change.canContainObjects !== null) target.canContainObjects = change.canContainObjects;
      if (change.visible !== null) target.visible = change.visible;
      if (change.operation === "SHOW") target.visible = true;
      if (change.operation === "HIDE" || change.operation === "REMOVE") target.visible = false;
      if (change.operation === "REMOVE" && !change.state) target.state = "已移出场景";
      objects.set(change.objectId, target);
    });
    const after = {
      objects: Array.from(objects.values()).sort((left, right) =>
        left.objectId.localeCompare(right.objectId)
      ),
    };
    current = structuredClone(after);
    return { ...shot, before, after };
  });
}

function SnapshotView({ snapshot }: { snapshot: SceneStateSnapshot }) {
  if (!snapshot.objects.length) {
    return <span className="text-xs text-muted-foreground/60">无额外对象状态</span>;
  }
  return (
    <div className="flex flex-wrap gap-1.5">
      {snapshot.objects.map((object) => (
        <span
          key={object.objectId}
          className={cn(
            "inline-flex max-w-full items-center gap-1 rounded-md border px-2 py-1 text-[11px]",
            object.visible
              ? "border-border/50 bg-muted/20 text-foreground"
              : "border-border/30 bg-muted/10 text-muted-foreground line-through"
          )}
        >
          {object.visible ? <Eye className="size-3" /> : <EyeOff className="size-3" />}
          <span className="truncate">{objectLabel(object)}</span>
        </span>
      ))}
    </div>
  );
}

function MediaState({
  label,
  exists,
  stale,
}: {
  label: string;
  exists: boolean;
  stale: boolean;
}) {
  if (!exists) {
    return <span className="text-[10px] text-muted-foreground/60">{label}未生成</span>;
  }
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 text-[10px]",
        stale ? "text-amber-500" : "text-emerald-500"
      )}
    >
      {stale ? <AlertTriangle className="size-3" /> : <CheckCircle2 className="size-3" />}
      {label}{stale ? "已过期" : "有效"}
    </span>
  );
}

export function SceneStateTimelineDialog({
  open,
  scene,
  items,
  assetsList,
  onOpenChange,
  onSaved,
}: SceneStateTimelineDialogProps) {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState<EditorTab>("layout");
  const [draft, setDraft] = useState<StoryboardSceneStateTimeline | null>(null);

  const sceneVariants = useMemo(
    () =>
      assetsList
        .filter((asset) => asset.type === "scene")
        .flatMap((asset) =>
          (asset.items || []).map((item) => ({
            value: String(item.id),
            label: assetItemLabel(asset, item, "未命名场景"),
          }))
        ),
    [assetsList]
  );

  const propOptions = useMemo<ObjectOption[]>(
    () =>
      assetsList
        .filter((asset) => asset.type === "prop")
        .flatMap((asset) =>
          (asset.items || []).map((item) => ({
            value: `assetItem:${item.id}`,
            label: assetItemLabel(asset, item, "未命名道具"),
          }))
        ),
    [assetsList]
  );

  const objectOptions = useMemo<ObjectOption[]>(() => {
    const layoutOptions = (draft?.layoutDefinition.anchors || []).map((anchor) => ({
      value: `layout:${anchor.id}`,
      label: `固定锚点 · ${anchor.name || anchor.id}`,
    }));
    const initialOptions = (draft?.initialState.objects || []).map((object) => ({
      value: object.objectId,
      label: object.objectName || "未命名对象",
    }));
    const merged = new Map<string, ObjectOption>();
    [...propOptions, ...layoutOptions, ...initialOptions].forEach((option) =>
      merged.set(option.value, option)
    );
    return Array.from(merged.values());
  }, [draft?.initialState.objects, draft?.layoutDefinition.anchors, propOptions]);

  const containerOptions = useMemo<ObjectOption[]>(() => {
    const layoutContainers = (draft?.layoutDefinition.anchors || [])
      .filter((anchor) => anchor.canContainObjects)
      .map((anchor) => ({
        value: `layout:${anchor.id}`,
        label: `固定容器 · ${anchor.name || anchor.id}`,
      }));
    const objectContainers = (draft?.initialState.objects || [])
      .filter((object) => object.canContainObjects)
      .map((object) => ({
        value: object.objectId,
        label: `道具容器 · ${object.objectName || object.objectId}`,
      }));
    const merged = new Map<string, ObjectOption>();
    [...layoutContainers, ...objectContainers].forEach((option) => merged.set(option.value, option));
    return Array.from(merged.values());
  }, [draft?.initialState.objects, draft?.layoutDefinition.anchors]);

  const itemMap = useMemo(
    () => new Map(items.map((item) => [item.id, item])),
    [items]
  );
  const materializedShots = useMemo(
    () => (draft ? materializeShots(draft) : []),
    [draft]
  );

  const loadTimeline = useCallback(async () => {
    if (!scene) return;
    setLoading(true);
    try {
      const timeline = await storyboardApi.getSceneStateTimeline(scene.id);
      setDraft(cloneTimeline(timeline));
    } catch (error) {
      console.error("加载场次状态时间线失败:", error);
      toast.error("加载场次状态时间线失败");
    } finally {
      setLoading(false);
    }
  }, [scene]);

  useEffect(() => {
    if (open && scene) {
      setActiveTab("layout");
      void loadTimeline();
    } else if (!open) {
      setDraft(null);
    }
  }, [loadTimeline, open, scene]);

  const updateAnchor = (index: number, patch: Partial<SceneLayoutAnchor>) => {
    setDraft((current) => {
      if (!current) return current;
      const anchors = current.layoutDefinition.anchors.map((anchor, anchorIndex) =>
        anchorIndex === index ? { ...anchor, ...patch } : anchor
      );
      return { ...current, layoutDefinition: { anchors } };
    });
  };

  const updateFurnitureLayout = (index: number, patch: Partial<FixedFurnitureLayout>) => {
    setDraft((current) => {
      if (!current) return current;
      const anchors = current.layoutDefinition.anchors.map((anchor, anchorIndex) =>
        anchorIndex === index
          ? { ...anchor, furnitureLayout: { ...(anchor.furnitureLayout || createFurnitureLayout()), ...patch } }
          : anchor
      );
      return { ...current, layoutDefinition: { anchors } };
    });
  };

  const updateInitialObject = (index: number, patch: Partial<SceneObjectState>) => {
    setDraft((current) => {
      if (!current) return current;
      const objects = current.initialState.objects.map((object, objectIndex) =>
        objectIndex === index ? { ...object, ...patch } : object
      );
      return { ...current, initialState: { objects } };
    });
  };

  const updateShot = (
    shotIndex: number,
    updater: (shot: StoryboardSceneStateTimeline["shots"][number]) => StoryboardSceneStateTimeline["shots"][number]
  ) => {
    setDraft((current) => {
      if (!current) return current;
      return {
        ...current,
        shots: current.shots.map((shot, index) => (index === shotIndex ? updater(shot) : shot)),
      };
    });
  };

  const updateChange = (
    shotIndex: number,
    changeIndex: number,
    patch: Partial<SceneStateChange>
  ) => {
    updateShot(shotIndex, (shot) => ({
      ...shot,
      delta: {
        changes: shot.delta.changes.map((change, index) =>
          index === changeIndex ? { ...change, ...patch } : change
        ),
      },
    }));
  };

  const handleSave = async () => {
    if (!scene || !draft) return;
    const invalidAnchor = draft.layoutDefinition.anchors.find(
      (anchor) => !anchor.name?.trim()
    );
    if (invalidAnchor) {
      toast.error("固定布局锚点必须填写名称");
      setActiveTab("layout");
      return;
    }
    const invalidGridFurniture = draft.layoutDefinition.anchors.find(
      (anchor) =>
        anchor.kind === "fixed_furniture" &&
        anchor.furnitureLayout?.arrangement === "grid" &&
        (!anchor.furnitureLayout.rows || !anchor.furnitureLayout.columns)
    );
    if (invalidGridFurniture) {
      toast.error("规则网格需要填写行数和列数");
      setActiveTab("layout");
      return;
    }
    const invalidObject = draft.initialState.objects.find(
      (object) => !object.objectId || !object.objectName.trim()
    );
    if (invalidObject) {
      toast.error("初始对象必须选择资产或固定锚点");
      setActiveTab("initial");
      return;
    }
    const invalidMove = draft.shots.some((shot) =>
      shot.delta.changes.some(
        (change) => change.operation === "MOVE" && !change.location?.trim()
      )
    );
    if (invalidMove) {
      toast.error("MOVE 操作必须填写目标位置");
      setActiveTab("shots");
      return;
    }

    setSaving(true);
    try {
      const updated = await storyboardApi.updateSceneStateTimeline(scene.id, {
        baseSceneAssetItemId: draft.baseSceneAssetItemId,
        layoutDefinition: {
          anchors: draft.layoutDefinition.anchors.map((anchor) => ({
            ...anchor,
            id: anchor.id || createAnchorId(),
          })),
        },
        initialState: draft.initialState,
        shots: draft.shots.map((shot) => ({
          itemId: shot.itemId,
          sceneAssetItemId: shot.sceneAssetItemId,
          continuityMode: shot.continuityMode,
          delta: shot.delta,
        })),
      });
      setDraft(cloneTimeline(updated));
      onSaved?.(updated);
      toast.success("场次状态时间线已保存");
      onOpenChange(false);
    } catch (error) {
      console.error("保存场次状态时间线失败:", error);
      toast.error(error instanceof Error ? error.message : "保存场次状态时间线失败");
    } finally {
      setSaving(false);
    }
  };

  const addInitialObject = () => {
    const used = new Set(draft?.initialState.objects.map((object) => object.objectId));
    const option = objectOptions.find((candidate) => !used.has(candidate.value));
    if (!option) {
      toast.error("没有可添加的道具或固定锚点");
      return;
    }
    setDraft((current) =>
      current
        ? {
            ...current,
            initialState: {
              objects: [
                ...current.initialState.objects,
                {
                  objectId: option.value,
                  objectName: option.label.replace(/^固定锚点 · /, ""),
                  location: null,
                  state: null,
                  containerId: null,
                  canContainObjects: false,
                  visible: true,
                },
              ],
            },
          }
        : current
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[92vh] flex-col gap-0 overflow-hidden rounded-lg p-0 sm:max-w-6xl">
        <DialogHeader className="border-b px-6 py-5">
          <DialogTitle>场景状态时间线</DialogTitle>
          <DialogDescription>
            {scene?.sceneHeading || `场次 ${scene?.sceneNumber || scene?.id}`} · 固定布局只定义一次，镜头只记录物体变化
          </DialogDescription>
        </DialogHeader>

        {loading || !draft ? (
          <div className="flex min-h-80 items-center justify-center">
            <Loader2 className="size-5 animate-spin text-muted-foreground" />
          </div>
        ) : (
          <>
            <div className="border-b px-6 py-4">
              <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-end">
                <div className="space-y-2">
                  <Label>场次默认场景变体</Label>
                  <Select
                    value={draft.baseSceneAssetItemId ? String(draft.baseSceneAssetItemId) : "none"}
                    onValueChange={(value) =>
                      setDraft((current) =>
                        current
                          ? {
                              ...current,
                              baseSceneAssetItemId:
                                value && value !== "none" ? Number(value) : null,
                            }
                          : current
                      )
                    }
                  >
                    <SelectTrigger className="w-full rounded-md">
                      <SelectValue>
                        {selectedOptionLabel(
                          sceneVariants,
                          draft.baseSceneAssetItemId ? String(draft.baseSceneAssetItemId) : null,
                          draft.baseSceneAssetItemId ? "已选场景变体" : "不指定"
                        )}
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="none">不指定</SelectItem>
                      {sceneVariants.map((variant) => (
                        <SelectItem key={variant.value} value={variant.value}>
                          {variant.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="text-xs text-muted-foreground">
                  时间线版本 <span className="font-mono text-foreground">v{draft.timelineVersion}</span>
                </div>
              </div>
            </div>

            <div className="flex border-b px-6 pt-3" role="tablist">
              {(
                [
                  ["layout", "固定布局", LayoutTemplate],
                  ["initial", "初始对象", Boxes],
                  ["shots", "镜头变化", ArrowRight],
                ] as const
              ).map(([value, label, Icon]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setActiveTab(value)}
                  className={cn(
                    "flex h-10 items-center gap-1.5 border-b-2 px-4 text-sm transition-colors",
                    activeTab === value
                      ? "border-primary text-foreground"
                      : "border-transparent text-muted-foreground hover:text-foreground"
                  )}
                >
                  <Icon className="size-4" />
                  {label}
                </button>
              ))}
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
              {activeTab === "layout" && (
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-sm font-medium">固定空间锚点</h3>
                      <p className="text-xs text-muted-foreground">只填写剧本或参考资产已明确的门窗、固定家具和区域</p>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        setDraft((current) =>
                          current
                            ? {
                                ...current,
                                layoutDefinition: {
                                  anchors: [
                                    ...current.layoutDefinition.anchors,
                                    {
                                      id: createAnchorId(),
                                      name: "",
                                      kind: "fixed",
                                      description: null,
                                      furnitureLayout: null,
                                      canContainObjects: false,
                                    },
                                  ],
                                },
                              }
                            : current
                        )
                      }
                    >
                      <Plus /> 添加锚点
                    </Button>
                  </div>
                  {draft.layoutDefinition.anchors.length === 0 ? (
                    <div className="border-y py-10 text-center text-sm text-muted-foreground">暂无固定布局锚点</div>
                  ) : (
                    <div className="divide-y border-y">
                      {draft.layoutDefinition.anchors.map((anchor, index) => (
                        <div key={`${anchor.id}-${index}`} className="space-y-3 py-4">
                          <div className="grid gap-3 lg:grid-cols-[minmax(180px,1fr)_180px_minmax(240px,2fr)_32px]">
                            <Input
                            value={anchor.name || ""}
                            onChange={(event) => updateAnchor(index, { name: event.target.value })}
                            placeholder="名称"
                            className="rounded-md"
                          />
                            <Select
                              value={anchor.kind || "fixed"}
                              onValueChange={(value) =>
                                updateAnchor(index, {
                                  kind: value || "fixed",
                                  furnitureLayout:
                                    value === "fixed_furniture"
                                      ? anchor.furnitureLayout || createFurnitureLayout()
                                      : null,
                                  canContainObjects:
                                    value === "fixed_furniture" ? anchor.canContainObjects : false,
                                })
                              }
                            >
                            <SelectTrigger className="w-full rounded-md">
                              <SelectValue>{anchorKindLabels[anchor.kind || "fixed"] || "其他固定结构"}</SelectValue>
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="door">门</SelectItem>
                              <SelectItem value="window">窗</SelectItem>
                              <SelectItem value="fixed_furniture">固定家具</SelectItem>
                              <SelectItem value="zone">区域</SelectItem>
                              <SelectItem value="fixed">其他固定结构</SelectItem>
                            </SelectContent>
                          </Select>
                            <Input
                            value={anchor.description || ""}
                            onChange={(event) => updateAnchor(index, { description: event.target.value || null })}
                            placeholder="已知布局事实，未知则留空"
                            className="rounded-md"
                          />
                            <Button
                            size="icon-sm"
                            variant="ghost"
                            title="删除锚点"
                            onClick={() =>
                              setDraft((current) =>
                                current
                                  ? {
                                      ...current,
                                      layoutDefinition: {
                                        anchors: current.layoutDefinition.anchors.filter((_, anchorIndex) => anchorIndex !== index),
                                      },
                                    }
                                  : current
                              )
                            }
                          >
                            <Trash2 />
                            </Button>
                          </div>
                          {anchor.kind === "fixed_furniture" && (
                            <div className="grid gap-3 border-l pl-4 lg:grid-cols-[180px_110px_110px_minmax(180px,1fr)_minmax(200px,1fr)] lg:items-center">
                              <Select
                                value={anchor.furnitureLayout?.arrangement || "custom"}
                                onValueChange={(value) =>
                                  updateFurnitureLayout(index, {
                                    arrangement: value as FixedFurnitureArrangement,
                                  })
                                }
                              >
                                <SelectTrigger className="w-full rounded-md">
                                  <SelectValue>{furnitureArrangementLabels[anchor.furnitureLayout?.arrangement || "custom"]}</SelectValue>
                                </SelectTrigger>
                                <SelectContent>
                                  {(Object.keys(furnitureArrangementLabels) as FixedFurnitureArrangement[]).map((arrangement) => (
                                    <SelectItem key={arrangement} value={arrangement}>
                                      {furnitureArrangementLabels[arrangement]}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                              <Input
                                type="number"
                                min={1}
                                value={anchor.furnitureLayout?.rows ?? ""}
                                onChange={(event) => updateFurnitureLayout(index, { rows: toOptionalPositiveInteger(event.target.value) })}
                                placeholder="行数"
                                className="rounded-md"
                              />
                              <Input
                                type="number"
                                min={1}
                                value={anchor.furnitureLayout?.columns ?? ""}
                                onChange={(event) => updateFurnitureLayout(index, { columns: toOptionalPositiveInteger(event.target.value) })}
                                placeholder="列数"
                                className="rounded-md"
                              />
                              <Input
                                value={anchor.furnitureLayout?.aisleDescription || ""}
                                onChange={(event) => updateFurnitureLayout(index, { aisleDescription: event.target.value || null })}
                                placeholder="已知过道关系"
                                className="rounded-md"
                              />
                              <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                                <Label className="gap-2 text-xs font-normal">
                                  <Checkbox
                                    checked={Boolean(anchor.furnitureLayout?.empty)}
                                    onCheckedChange={(checked) => updateFurnitureLayout(index, { empty: checked === true })}
                                  />
                                  空置
                                </Label>
                                <Label className="gap-2 text-xs font-normal">
                                  <Checkbox
                                    checked={anchor.canContainObjects}
                                    onCheckedChange={(checked) => updateAnchor(index, { canContainObjects: checked === true })}
                                  />
                                  可容纳物体
                                </Label>
                              </div>
                              <Input
                                value={(anchor.furnitureLayout?.prohibitedFeatures || []).join("、")}
                                onChange={(event) => updateFurnitureLayout(index, { prohibitedFeatures: splitProhibitedFeatures(event.target.value) })}
                                placeholder="禁止生成结构，例如抽屉、储物柜"
                                className="rounded-md lg:col-span-5"
                              />
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {activeTab === "initial" && (
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-sm font-medium">场次开始状态</h3>
                      <p className="text-xs text-muted-foreground">只添加需要跨镜保持或会发生变化的对象</p>
                    </div>
                    <Button size="sm" variant="outline" onClick={addInitialObject}>
                      <Plus /> 添加对象
                    </Button>
                  </div>
                  {draft.initialState.objects.length === 0 ? (
                    <div className="border-y py-10 text-center text-sm text-muted-foreground">暂无初始对象状态</div>
                  ) : (
                    <div className="divide-y border-y">
                      {draft.initialState.objects.map((object, index) => (
                        <div key={`${object.objectId}-${index}`} className="grid gap-3 py-4 lg:grid-cols-[minmax(180px,1fr)_minmax(140px,1fr)_minmax(140px,1fr)_minmax(180px,1fr)_150px_32px] lg:items-center">
                          <Select
                            value={object.objectId}
                            onValueChange={(value) => {
                              const option = objectOptions.find((candidate) => candidate.value === value);
                              if (value) {
                                updateInitialObject(index, {
                                  objectId: value,
                                  objectName: option?.label.replace(/^固定锚点 · /, "") || value,
                                  containerId: object.containerId === value ? null : object.containerId,
                                });
                              }
                            }}
                          >
                            <SelectTrigger className="w-full rounded-md">
                              <SelectValue>{selectedOptionLabel(objectOptions, object.objectId, object.objectName || "选择对象")}</SelectValue>
                            </SelectTrigger>
                            <SelectContent>
                              {objectOptions.map((option) => (
                                <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <Input
                            value={object.location || ""}
                            onChange={(event) => updateInitialObject(index, { location: event.target.value || null })}
                            placeholder="已知初始位置"
                            className="rounded-md"
                          />
                          <Input
                            value={object.state || ""}
                            onChange={(event) => updateInitialObject(index, { state: event.target.value || null })}
                            placeholder="初始状态"
                            className="rounded-md"
                          />
                          <Select
                            value={object.containerId || "none"}
                            onValueChange={(value) =>
                              updateInitialObject(index, { containerId: value === "none" ? null : value })
                            }
                          >
                            <SelectTrigger className="w-full rounded-md">
                              <SelectValue>
                                {selectedOptionLabel(
                                  containerOptions.filter((option) => option.value !== object.objectId),
                                  object.containerId,
                                  "不放入容器"
                                )}
                              </SelectValue>
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="none">不放入容器</SelectItem>
                              {containerOptions
                                .filter((option) => option.value !== object.objectId)
                                .map((option) => (
                                  <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                                ))}
                            </SelectContent>
                          </Select>
                          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                            <Label className="gap-2 text-xs font-normal">
                              <Checkbox
                                checked={object.canContainObjects}
                                onCheckedChange={(checked) => updateInitialObject(index, { canContainObjects: checked === true })}
                              />
                              可容纳物体
                            </Label>
                            <Label className="gap-2 text-xs font-normal">
                              <Checkbox
                                checked={object.visible}
                                onCheckedChange={(checked) => updateInitialObject(index, { visible: checked === true })}
                              />
                              可见
                            </Label>
                          </div>
                          <Button
                            size="icon-sm"
                            variant="ghost"
                            title="删除对象"
                            onClick={() =>
                              setDraft((current) =>
                                current
                                  ? {
                                      ...current,
                                      initialState: {
                                        objects: current.initialState.objects.filter((_, objectIndex) => objectIndex !== index),
                                      },
                                    }
                                  : current
                              )
                            }
                          >
                            <Trash2 />
                          </Button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {activeTab === "shots" && (
                <div className="space-y-5">
                  {materializedShots.map((shot, shotIndex) => {
                    const item = itemMap.get(shot.itemId);
                    return (
                      <section key={shot.itemId} className="border-b pb-5 last:border-b-0">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <h3 className="text-sm font-medium">镜头 {shot.shotNumber || shot.itemId}</h3>
                            <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{shot.content || "无画面描述"}</p>
                          </div>
                          <div className="flex flex-wrap items-center gap-3">
                            <MediaState label="首帧" exists={Boolean(item?.firstFrameImageUrl)} stale={shot.firstFrameStale} />
                            <MediaState label="尾帧" exists={Boolean(item?.lastFrameImageUrl)} stale={shot.lastFrameStale} />
                            <MediaState label="视频" exists={Boolean(item?.generatedVideoUrl)} stale={shot.videoStale} />
                          </div>
                        </div>

                        <div className="mt-4 grid gap-4 xl:grid-cols-[minmax(0,1fr)_220px_minmax(0,1fr)]">
                          <div>
                            <p className="mb-2 text-[11px] font-medium text-muted-foreground">镜头开始</p>
                            <SnapshotView snapshot={shot.before} />
                          </div>
                          <div>
                            <p className="mb-2 text-[11px] font-medium text-muted-foreground">场景变体</p>
                            <Select
                              value={shot.sceneAssetItemId ? String(shot.sceneAssetItemId) : "inherit"}
                              onValueChange={(value) =>
                                updateShot(shotIndex, (current) => ({
                                  ...current,
                                  sceneAssetItemId:
                                    value && value !== "inherit" ? Number(value) : null,
                                }))
                              }
                            >
                              <SelectTrigger className="w-full rounded-md">
                                <SelectValue>
                                  {selectedOptionLabel(
                                    sceneVariants,
                                    shot.sceneAssetItemId ? String(shot.sceneAssetItemId) : null,
                                    shot.sceneAssetItemId ? "已选场景变体" : "继承场次默认"
                                  )}
                                </SelectValue>
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="inherit">继承场次默认</SelectItem>
                                {sceneVariants.map((variant) => (
                                  <SelectItem key={variant.value} value={variant.value}>
                                    {variant.label}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                            <p className="mb-2 mt-3 text-[11px] font-medium text-muted-foreground">衔接模式</p>
                            <div className="grid grid-cols-2 rounded-md border p-0.5">
                              {(["CUT", "SEAMLESS"] as SceneContinuityMode[]).map((mode) => (
                                <button
                                  key={mode}
                                  type="button"
                                  onClick={() => updateShot(shotIndex, (current) => ({ ...current, continuityMode: mode }))}
                                  className={cn(
                                    "h-7 rounded-[4px] text-[11px] font-medium transition-colors",
                                    shot.continuityMode === mode
                                      ? "bg-primary text-primary-foreground"
                                      : "text-muted-foreground hover:bg-muted"
                                  )}
                                >
                                  {continuityModeLabels[mode]}
                                </button>
                              ))}
                            </div>
                          </div>
                          <div>
                            <p className="mb-2 text-[11px] font-medium text-muted-foreground">镜头结束</p>
                            <SnapshotView snapshot={shot.after} />
                          </div>
                        </div>

                        <div className="mt-4 space-y-2">
                          <div className="flex items-center justify-between">
                            <p className="text-xs font-medium">本镜头物体变化</p>
                            <Button
                              size="xs"
                              variant="outline"
                              onClick={() => {
                                const option = objectOptions[0];
                                if (!option) {
                                  toast.error("请先添加道具资产或固定布局锚点");
                                  return;
                                }
                                updateShot(shotIndex, (current) => ({
                                  ...current,
                                  delta: {
                                    changes: [
                                      ...current.delta.changes,
                                      {
                                        operation: "MOVE",
                                        objectId: option.value,
                                        objectName: option.label.replace(/^固定锚点 · /, ""),
                                        location: null,
                                        state: null,
                                        containerId: null,
                                        canContainObjects: null,
                                        visible: null,
                                      },
                                    ],
                                  },
                                }));
                              }}
                            >
                              <Plus /> 添加变化
                            </Button>
                          </div>
                          {shot.delta.changes.length === 0 ? (
                            <p className="border-y py-3 text-xs text-muted-foreground">无变化，所有对象保持镜头开始状态</p>
                          ) : (
                            <div className="divide-y border-y">
                              {shot.delta.changes.map((change, changeIndex) => (
                                <div key={`${change.objectId}-${changeIndex}`} className="grid gap-2 py-3 lg:grid-cols-[170px_minmax(190px,1fr)_minmax(140px,1fr)_minmax(140px,1fr)_minmax(180px,1fr)_130px_32px] lg:items-center">
                                  <Select
                                    value={change.operation}
                                    onValueChange={(value) => value && updateChange(shotIndex, changeIndex, { operation: value as SceneStateOperation })}
                                  >
                                    <SelectTrigger className="w-full rounded-md">
                                      <SelectValue>{operationLabels[change.operation]}</SelectValue>
                                    </SelectTrigger>
                                    <SelectContent>
                                      {operations.map((operation) => (
                                        <SelectItem
                                          key={operation}
                                          value={operation}
                                          disabled={change.objectId.startsWith("layout:") && ["MOVE", "ADD", "REMOVE"].includes(operation)}
                                        >
                                          {operationLabels[operation]}
                                        </SelectItem>
                                      ))}
                                    </SelectContent>
                                  </Select>
                                  <Select
                                    value={change.objectId}
                                    onValueChange={(value) => {
                                      const option = objectOptions.find((candidate) => candidate.value === value);
                                      if (value) {
                                        const fixedLayout = value.startsWith("layout:");
                                        updateChange(shotIndex, changeIndex, {
                                          objectId: value,
                                          objectName: option?.label.replace(/^固定锚点 · /, "") || value,
                                          containerId: change.containerId === value ? null : change.containerId,
                                          operation:
                                            fixedLayout && ["MOVE", "ADD", "REMOVE"].includes(change.operation)
                                              ? "SET_STATE"
                                              : change.operation,
                                        });
                                      }
                                    }}
                                  >
                                    <SelectTrigger className="w-full rounded-md">
                                      <SelectValue>{selectedOptionLabel(objectOptions, change.objectId, change.objectName || "选择对象")}</SelectValue>
                                    </SelectTrigger>
                                    <SelectContent>
                                      {objectOptions.map((option) => (
                                        <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                                      ))}
                                    </SelectContent>
                                  </Select>
                                  <Input
                                    value={change.location || ""}
                                    onChange={(event) => updateChange(shotIndex, changeIndex, { location: event.target.value || null })}
                                    placeholder={change.operation === "MOVE" ? "目标位置（必填）" : "位置（可选）"}
                                    className="rounded-md"
                                  />
                                  <Input
                                    value={change.state || ""}
                                    onChange={(event) => updateChange(shotIndex, changeIndex, { state: event.target.value || null })}
                                    placeholder="状态（可选）"
                                    className="rounded-md"
                                  />
                                  <Select
                                    value={change.containerId || "none"}
                                    onValueChange={(value) =>
                                      updateChange(shotIndex, changeIndex, {
                                        containerId: value === "none" ? null : value,
                                      })
                                    }
                                  >
                                    <SelectTrigger className="w-full rounded-md">
                                      <SelectValue>
                                        {selectedOptionLabel(
                                          containerOptions.filter((option) => option.value !== change.objectId),
                                          change.containerId,
                                          "不放入容器"
                                        )}
                                      </SelectValue>
                                    </SelectTrigger>
                                    <SelectContent>
                                      <SelectItem value="none">不放入容器</SelectItem>
                                      {containerOptions
                                        .filter((option) => option.value !== change.objectId)
                                        .map((option) => (
                                          <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                                        ))}
                                    </SelectContent>
                                  </Select>
                                  <Label className="gap-2 text-xs font-normal">
                                    <Checkbox
                                      checked={change.canContainObjects === true}
                                      onCheckedChange={(checked) =>
                                        updateChange(shotIndex, changeIndex, { canContainObjects: checked === true })
                                      }
                                    />
                                    是容器
                                  </Label>
                                  <Button
                                    size="icon-sm"
                                    variant="ghost"
                                    title="删除变化"
                                    onClick={() =>
                                      updateShot(shotIndex, (current) => ({
                                        ...current,
                                        delta: {
                                          changes: current.delta.changes.filter((_, index) => index !== changeIndex),
                                        },
                                      }))
                                    }
                                  >
                                    <Trash2 />
                                  </Button>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </section>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}

        <DialogFooter className="border-t px-6 py-4">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>取消</Button>
          <Button onClick={handleSave} disabled={loading || saving || !draft}>
            {saving ? <Loader2 className="animate-spin" /> : <Save />}
            保存整场时间线
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
