"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import {
  Info,
  Camera,
  Clock,
  Film,
  Hash,
  Image as ImageIcon,
  MessageSquare,
  Move3d,
  Music,
  Volume2,
  FileText,
  Sparkles,
  Loader2,
  Users,
  MapPin,
  Package,
  ExternalLink,
  X,
  ZoomIn,
  SlidersHorizontal,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { resolveMediaUrl } from "@/lib/api/client";
import { assetApi } from "@/lib/api/asset";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import { SafeImage } from "@/components/ui/safe-image";

import type { Asset, AssetItem } from "@/lib/api/asset";
import type { Project } from "@/lib/api/project";
import type { StoryboardItem, Storyboard, StoryboardFrameType, StoryboardScene } from "@/lib/api/storyboard";
import { BatchGenDialog } from "./batch-gen-dialog";
import type { AssetItemWithInfo, SelectedAssetItem } from "./batch-gen-dialog";
import { FrameReferenceSection } from "./storyboard-frame-reference-dialog";
import { usePipelineStore } from "@/lib/store/pipeline-store";

// ========== 类型 ==========

interface SceneWithItems {
  scene: StoryboardScene;
  items: StoryboardItem[];
}

/** 带主资产名称和类型的子资产 */
interface AssetItemWithParent extends AssetItem {
  parentName: string;
  parentType: string;
}

/** 资产分组 */
interface GroupedAssets {
  characters: AssetItemWithParent[];
  scenes: AssetItemWithParent[];
  props: AssetItemWithParent[];
}

// ========== 常量 ==========

const typeConfig = {
  character: {
    label: "角色",
    icon: Users,
    color: "text-blue-400",
    bgColor: "bg-blue-500/10",
  },
  scene: {
    label: "场景",
    icon: MapPin,
    color: "text-green-400",
    bgColor: "bg-green-500/10",
  },
  prop: {
    label: "道具",
    icon: Package,
    color: "text-amber-400",
    bgColor: "bg-amber-500/10",
  },
} as const;

/** 安全解析 ID 数组（兼容后端返回的 JSON 字符串或原生数组） */
function parseIds(raw: number[] | string | null | undefined): number[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

interface ShotStrategy {
  primaryType?: string;
  tags?: string[];
}

interface NarrativeBeat {
  id?: string;
  description?: string;
}

const shotStrategyLabels: Record<string, string> = {
  GENERAL_NARRATIVE: "叙事推进",
  DIALOGUE: "对白交流",
  ACTION: "动作事件",
  CONTINUOUS_VISUAL: "连续视觉",
  MONTAGE: "蒙太奇",
  REACTION: "角色反应",
  OBJECT_OPERATION: "物体操作",
  EMPTY: "空镜",
  TIME_COMPRESSION: "时间压缩",
};

function shotStrategyLabel(value: string) {
  return shotStrategyLabels[value] || "其他拆镜策略";
}

function parseJson<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function parseBeatIds(raw: string | null | undefined): string[] {
  const parsed = parseJson<unknown>(raw, []);
  return Array.isArray(parsed) && parsed.every((value) => typeof value === "string")
    ? parsed
    : [];
}

function ShotPlanPanel({ sceneGroup }: { sceneGroup: SceneWithItems }) {
  const strategy = parseJson<ShotStrategy>(sceneGroup.scene.shotStrategy, {});
  const beats = parseJson<NarrativeBeat[]>(sceneGroup.scene.beats, []);
  const hasPlan = Boolean(strategy.primaryType || beats.length > 0);

  if (!hasPlan) return null;

  return (
    <section className="border-y py-3">
      <div className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
        <Film className="size-3" />
        拆镜计划
      </div>
      {strategy.primaryType && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px]">
          <span className="text-muted-foreground">主策略</span>
          <span className="rounded bg-muted px-1.5 py-0.5 font-medium text-foreground">
            {shotStrategyLabel(strategy.primaryType)}
          </span>
          {(strategy.tags || []).map((tag) => (
            <span key={tag} className="rounded bg-muted/60 px-1.5 py-0.5 text-muted-foreground">
              {shotStrategyLabel(tag)}
            </span>
          ))}
        </div>
      )}
      <div className="mt-3 space-y-2">
        {beats.map((beat, index) => {
          const beatId = beat.id || `B${index + 1}`;
          const coveredBy = sceneGroup.items
            .filter((item) => parseBeatIds(item.beatIds).includes(beatId))
            .map((item) => item.shotNumber || String(item.sortOrder + 1));

          return (
            <div key={beatId} className="flex gap-2 text-[11px] leading-4">
              <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 font-medium text-primary">
                {beatId}
              </span>
              <span className="min-w-0 flex-1 text-foreground/80">{beat.description || "未填写节拍说明"}</span>
              <span className="shrink-0 text-muted-foreground">
                {coveredBy.length > 0 ? `镜头 ${coveredBy.join("、")}` : "未覆盖"}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ========== 场次资产面板 ==========

function SceneAssetPanel({
  sceneGroup,
  projectId,
  onPreviewImage,
  onEditSceneState,
}: {
  sceneGroup: SceneWithItems;
  projectId: number;
  onPreviewImage?: (url: string, title: string) => void;
  onEditSceneState?: (sceneId: number) => void;
}) {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [groupedAssets, setGroupedAssets] = useState<GroupedAssets>({
    characters: [],
    scenes: [],
    props: [],
  });
  const [showBatchGen, setShowBatchGen] = useState(false);

  // 直接从分镜 items 聚合子资产 ID（characterIds / sceneAssetItemId / propIds）
  // 批量查子资产详情，附带主资产名称做辅助展示
  const loadAssets = useCallback(async () => {
    setLoading(true);
    try {
      const characterItemIds = new Set<number>();
      const sceneItemIds = new Set<number>();
      const propItemIds = new Set<number>();

      if (sceneGroup.scene.baseSceneAssetItemId) {
        sceneItemIds.add(sceneGroup.scene.baseSceneAssetItemId);
      }

      for (const item of sceneGroup.items) {
        const charIds = parseIds(item.characterIds);
        charIds.forEach((id) => characterItemIds.add(id));
        if (item.sceneAssetItemId) sceneItemIds.add(item.sceneAssetItemId);
        const pIds = parseIds(item.propIds);
        pIds.forEach((id) => propItemIds.add(id));
      }

      const allItemIds = [
        ...characterItemIds,
        ...sceneItemIds,
        ...propItemIds,
      ];

      if (allItemIds.length === 0) {
        setGroupedAssets({ characters: [], scenes: [], props: [] });
        return;
      }

      // 批量获取子资产详情
      const results = await Promise.all(
        allItemIds.map((id) => assetApi.getItem(id).catch(() => null))
      );

      // 收集主资产ID用于查名称
      const parentAssetIds = new Set<number>();
      const itemMap = new Map<number, AssetItem>();
      for (const r of results) {
        if (!r) continue;
        itemMap.set(r.id, r);
        if (r.assetId) parentAssetIds.add(r.assetId);
      }

      // 批量获取主资产（用于获取名称和类型做辅助标注）
      const parentAssets = await Promise.all(
        Array.from(parentAssetIds).map((id) => assetApi.get(id).catch(() => null))
      );
      const parentInfoMap = new Map<number, { name: string; type: string }>();
      for (const a of parentAssets) {
        if (a) parentInfoMap.set(a.id, { name: a.name, type: a.type });
      }

      // 角色类型主资产：补充加载 three_view 子资产到列表中
      // 分镜编写时 characterIds 只填 initial/variant，three_view 由此处补齐
      const characterAssetIds = Array.from(parentAssetIds).filter((aid) => {
        const info = parentInfoMap.get(aid);
        return info?.type === "character";
      });
      if (characterAssetIds.length > 0) {
        const allItemsResults = await Promise.all(
          characterAssetIds.map((aid) => assetApi.listItems(aid).catch(() => []))
        );
        for (const items of allItemsResults) {
          for (const it of items) {
            if (it.itemType === "three_view" && !itemMap.has(it.id)) {
              itemMap.set(it.id, it);
              characterItemIds.add(it.id);
            }
          }
        }
      }

      // 直接展示子资产，附带主资产名称和类型
      const toItemsWithParent = (ids: Set<number>): AssetItemWithParent[] => {
        const result: AssetItemWithParent[] = [];
        for (const itemId of ids) {
          const item = itemMap.get(itemId);
          if (!item) continue;
          const info = parentInfoMap.get(item.assetId);
          result.push({
            ...item,
            parentName: info?.name || "未知资产",
            parentType: info?.type || "unknown",
          });
        }
        return result;
      };

      setGroupedAssets({
        characters: toItemsWithParent(characterItemIds),
        scenes: toItemsWithParent(sceneItemIds),
        props: toItemsWithParent(propItemIds),
      });
    } catch (err) {
      console.error("加载场次资产失败:", err);
    } finally {
      setLoading(false);
    }
  }, [sceneGroup]);

  useEffect(() => {
    loadAssets();
  }, [loadAssets]);

  const allItems = [
    ...groupedAssets.characters,
    ...groupedAssets.scenes,
    ...groupedAssets.props,
  ];
  const hasAssets = allItems.length > 0;

  // 点击子资产时跳转到其主资产
  const handleItemClick = (item: AssetItemWithParent) => {
    router.push(`/projects/${projectId}/assets?highlight=${item.assetId}`);
  };

  const addPipeline = usePipelineStore((s) => s.addPipeline);
  const setNotificationOpen = usePipelineStore((s) => s.setNotificationOpen);

  // 构建传给 BatchGenDialog 的子资产列表
  const batchGenItems: AssetItemWithInfo[] = allItems.map((ai) => ({
    item: ai,
    parentName: ai.parentName,
    parentType: ai.parentType,
    assetId: ai.assetId,
  }));

  const handleBatchGenConfirm = (selectedItems: SelectedAssetItem[]) => {
    // 提取去重的主资产ID和选中的子资产ID
    const selectedAssetIds = [...new Set(selectedItems.map((s) => s.assetId))];
    const selectedAssetItemIds = selectedItems.map((s) => s.itemId);

    // 触发 Agent Pipeline
    addPipeline({
      label: `批量生图 (${selectedItems.length} 个子资产)`,
      projectId,
      request: {
        agentType: "asset_image_gen",
        projectId,
        context: {
          selectedAssetIds,
          selectedAssetItemIds,
        },
      },
      onComplete: () => {
        loadAssets();
      },
    });

    // 打开通知面板让用户看到进度
    setNotificationOpen(true);
  };

  return (
    <div className="p-4 space-y-4">
      {/* 标题 */}
      <div>
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-1 flex items-center gap-1.5">
          <Camera className="h-3 w-3" /> 场次详情
          {onEditSceneState && (
            <button
              type="button"
              onClick={() => onEditSceneState(sceneGroup.scene.id)}
              className="ml-auto inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              title="编辑场景状态时间线"
            >
              <SlidersHorizontal className="size-3.5" />
            </button>
          )}
        </h4>
        <p className="text-sm font-semibold">
          {sceneGroup.scene.sceneHeading ||
            `场次 ${sceneGroup.scene.sceneNumber || sceneGroup.scene.id}`}
        </p>
        {sceneGroup.scene.location && (
          <p className="text-[10px] text-muted-foreground mt-0.5">
            {sceneGroup.scene.intExt && `${sceneGroup.scene.intExt} `}
            {sceneGroup.scene.location}
            {sceneGroup.scene.timeOfDay && ` · ${sceneGroup.scene.timeOfDay}`}
          </p>
        )}
        <p className="text-[10px] text-muted-foreground/60 mt-0.5">
          {sceneGroup.items.length} 个镜头 · 状态版本 v{sceneGroup.scene.stateTimelineVersion || 0}
        </p>
      </div>

      <ShotPlanPanel sceneGroup={sceneGroup} />

      {/* 批量生图按钮 */}
      {hasAssets && (
        <button
          onClick={() => setShowBatchGen(true)}
          className={cn(
            "w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-xs font-medium transition-all",
            "bg-linear-to-r from-indigo-600 to-blue-600 text-white",
            "hover:shadow-lg hover:shadow-indigo-500/20 hover:scale-[1.02]",
            "active:scale-[0.98]"
          )}
        >
          <Sparkles className="h-3.5 w-3.5" />
          批量生图
        </button>
      )}

      {/* 加载中 */}
      {loading && (
        <div className="flex items-center justify-center py-6">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* 资产列表 */}
      {!loading && (
        <>
          {!hasAssets && (
            <div className="text-center py-6 border-t border-border pt-4">
              <Package className="h-8 w-8 text-muted-foreground/20 mx-auto mb-2" />
              <p className="text-xs text-muted-foreground">
                该场次暂无关联资产
              </p>
              <p className="text-[10px] text-muted-foreground/60 mt-0.5">
                请先在剧本场次中设置角色、场景和道具
              </p>
            </div>
          )}

          {/* 按类型分组展示 */}
          {(
            [
              ["character", groupedAssets.characters],
              ["scene", groupedAssets.scenes],
              ["prop", groupedAssets.props],
            ] as [keyof typeof typeConfig, AssetItemWithParent[]][]
          ).map(
            ([type, items]) =>
              items.length > 0 && (
                <AssetItemGroup
                  key={type}
                  type={type}
                  items={items}
                  onItemClick={handleItemClick}
                  onPreviewImage={onPreviewImage}
                />
              )
          )}
        </>
      )}

      {/* 批量生图弹窗 — 传入子资产列表 */}
      <BatchGenDialog
        key={showBatchGen ? "batch-gen-open" : "batch-gen-closed"}
        open={showBatchGen}
        onClose={() => setShowBatchGen(false)}
        assetItems={batchGenItems}
        onConfirm={handleBatchGenConfirm}
      />

    </div>
  );
}

/** 子资产分组展示 */
function AssetItemGroup({
  type,
  items,
  onItemClick,
  onPreviewImage,
}: {
  type: keyof typeof typeConfig;
  items: AssetItemWithParent[];
  onItemClick: (item: AssetItemWithParent) => void;
  onPreviewImage?: (url: string, title: string) => void;
}) {
  const config = typeConfig[type];
  const Icon = config.icon;

  return (
    <div className="border-t border-border pt-3">
      <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2.5 flex items-center gap-1.5">
        <Icon className={cn("h-3 w-3", config.color)} />
        {config.label}
        <span className="text-[10px] font-normal text-muted-foreground/60 ml-auto">
          {items.length}
        </span>
      </h4>
      <div className="space-y-1.5">
        {items.map((item) => (
          <button
            key={item.id}
            onClick={() => onItemClick(item)}
            className={cn(
              "w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg transition-all text-left group",
              "hover:bg-muted/30"
            )}
          >
            {/* 缩略图 */}
            <div
              onClick={(e) => {
                if (item.imageUrl && onPreviewImage) {
                  e.stopPropagation();
                  onPreviewImage(item.imageUrl, `${item.parentName}: ${item.name || "初始设定"}`);
                }
              }}
              className={cn(
                "h-10 w-10 bg-muted/30 border border-border/10 overflow-hidden shrink-0 flex items-center justify-center relative group/img",
                type === "character" ? "rounded-full" : "rounded-lg",
                item.imageUrl && "cursor-zoom-in hover:border-primary/40 transition-colors"
              )}
            >
              {item.imageUrl ? (
                <>
                  <SafeImage
                    src={resolveMediaUrl(item.imageUrl)}
                    alt={item.name || item.parentName}
                    fallbackType={type === "character" ? "avatar" : type === "scene" ? "scene" : "prop"}
                    className="w-full h-full object-cover transition-transform group-hover/img:scale-105"
                  />
                  <div className="absolute inset-0 bg-black/0 group-hover/img:bg-black/25 flex items-center justify-center opacity-0 group-hover/img:opacity-100 transition-all">
                    <ZoomIn className="h-3.5 w-3.5 text-white/90" />
                  </div>
                </>
              ) : (
                <Icon
                  className={cn("h-4 w-4 text-muted-foreground/30")}
                />
              )}
            </div>
            {/* 信息 */}
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium truncate">
                {item.name || item.parentName}
              </p>
              <p className="text-[10px] text-muted-foreground/60 truncate mt-0.5">
                {item.parentName}
              </p>
            </div>
            {/* 跳转图标 */}
            <ExternalLink className="h-3 w-3 text-muted-foreground/30 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" />
          </button>
        ))}
      </div>
    </div>
  );
}

// ========== 镜头详情（保留原有） ==========

function ItemDetail({
  item,
  projectId,
  project,
  assetLookup,
  onEditAssets,
  onUpdateFrame,
  onUpdateFramePrompt,
  onGenerateFrame,
  onPreviewImage,
}: {
  item: StoryboardItem;
  projectId: number;
  project?: Project | null;
  assetLookup?: Record<number, { item: AssetItem; asset: Asset }>;
  onEditAssets?: () => void;
  onUpdateFrame?: (itemId: number, frameType: StoryboardFrameType, imageUrl: string | null) => Promise<void> | void;
  onUpdateFramePrompt?: (itemId: number, frameType: StoryboardFrameType, prompt: string | null) => Promise<void> | void;
  onGenerateFrame?: (item: StoryboardItem, frameType: StoryboardFrameType, prompt: string) => Promise<void> | void;
  onPreviewImage?: (url: string, title: string) => void;
}) {
  const router = useRouter();
  const detailRows: {
    icon: typeof Info;
    label: string;
    value: string | null;
  }[] = [
    { icon: Hash, label: "镜号", value: item.shotNumber },
    { icon: Camera, label: "景别", value: item.shotType },
    {
      icon: Clock,
      label: "时长",
      value: item.duration ? `${item.duration}s` : null,
    },
    { icon: Move3d, label: "镜头运动", value: item.cameraMovement },
    { icon: Camera, label: "机位角度", value: item.cameraAngle },
    { icon: Camera, label: "焦距", value: item.focalLength },
    { icon: Film, label: "转场", value: item.transition },
  ];

  // ===== 加载镜头关联资产 =====
  const [linkedAssets, setLinkedAssets] = useState<{
    characters: (Asset & { items: AssetItem[] })[];
    scenes: (Asset & { items: AssetItem[] })[];
    props: (Asset & { items: AssetItem[] })[];
  }>({ characters: [], scenes: [], props: [] });
  const [assetsLoading, setAssetsLoading] = useState(false);

  const loadLinkedAssets = useCallback(async () => {
    // 解析各类子资产 ID
    const charItemIds = parseIds(item.characterIds);
    const sceneItemId = item.sceneAssetItemId && item.sceneAssetItemId > 0 ? item.sceneAssetItemId : null;
    const propItemIds = parseIds(item.propIds);

    const allItemIds = [...charItemIds, ...propItemIds];
    if (sceneItemId) allItemIds.push(sceneItemId);

    if (allItemIds.length === 0) {
      setLinkedAssets({ characters: [], scenes: [], props: [] });
      return;
    }

    setAssetsLoading(true);
    try {
      // 批量获取子资产详情
      const items = await Promise.all(
        allItemIds.map((id) => assetApi.getItem(id).catch(() => null))
      );

      // 收集主资产 ID（去重）
      const parentIds = new Set<number>();
      const itemMap = new Map<number, AssetItem>();
      for (const r of items) {
        if (!r) continue;
        itemMap.set(r.id, r);
        if (r.assetId) parentIds.add(r.assetId);
      }

      // 批量获取主资产 + 其子资产列表
      const parentResults = await Promise.all(
        Array.from(parentIds).map(async (id) => {
          try {
            const [asset, subItems] = await Promise.all([
              assetApi.get(id),
              assetApi.listItems(id),
            ]);
            return { ...asset, items: subItems || [] };
          } catch {
            return null;
          }
        })
      );

      const valid = parentResults.filter(
        (r): r is Asset & { items: AssetItem[] } => r !== null
      );

      // 按子资产ID → 主资产分类
      const charParentIds = new Set(charItemIds.map((id) => itemMap.get(id)?.assetId).filter((id): id is number => id != null));
      const sceneParentId = sceneItemId ? itemMap.get(sceneItemId)?.assetId : null;
      const propParentIds = new Set(propItemIds.map((id) => itemMap.get(id)?.assetId).filter((id): id is number => id != null));

      setLinkedAssets({
        characters: valid.filter((a) => charParentIds.has(a.id)),
        scenes: valid.filter((a) => a.id === sceneParentId),
        props: valid.filter((a) => propParentIds.has(a.id)),
      });
    } catch (err) {
      console.error("加载镜头关联资产失败:", err);
    } finally {
      setAssetsLoading(false);
    }
  }, [item.characterIds, item.sceneAssetItemId, item.propIds]);

  useEffect(() => {
    if (assetLookup && Object.keys(assetLookup).length > 0) {
      const charItemIds = parseIds(item.characterIds);
      const sceneItemId = item.sceneAssetItemId && item.sceneAssetItemId > 0 ? item.sceneAssetItemId : null;
      const propItemIds = parseIds(item.propIds);

      const charsMap = new Map<number, Asset & { items: AssetItem[] }>();
      charItemIds.forEach(id => {
        const entry = assetLookup[id];
        if (entry) {
          const { item: subItem, asset } = entry;
          if (!charsMap.has(asset.id)) {
            charsMap.set(asset.id, { ...asset, items: [] });
          }
          if (!charsMap.get(asset.id)!.items.some(x => x.id === subItem.id)) {
            charsMap.get(asset.id)!.items.push(subItem);
          }
        }
      });

      const scenesList: (Asset & { items: AssetItem[] })[] = [];
      if (sceneItemId) {
        const entry = assetLookup[sceneItemId];
        if (entry) {
          const { item: subItem, asset } = entry;
          scenesList.push({ ...asset, items: [subItem] });
        }
      }

      const propsMap = new Map<number, Asset & { items: AssetItem[] }>();
      propItemIds.forEach(id => {
        const entry = assetLookup[id];
        if (entry) {
          const { item: subItem, asset } = entry;
          if (!propsMap.has(asset.id)) {
            propsMap.set(asset.id, { ...asset, items: [] });
          }
          if (!propsMap.get(asset.id)!.items.some(x => x.id === subItem.id)) {
            propsMap.get(asset.id)!.items.push(subItem);
          }
        }
      });

      setLinkedAssets({
        characters: Array.from(charsMap.values()),
        scenes: scenesList,
        props: Array.from(propsMap.values()),
      });
      setAssetsLoading(false);
    } else {
      loadLinkedAssets();
    }
  }, [item.characterIds, item.sceneAssetItemId, item.propIds, assetLookup, loadLinkedAssets]);

  const hasLinkedAssets =
    linkedAssets.characters.length > 0 ||
    linkedAssets.scenes.length > 0 ||
    linkedAssets.props.length > 0;

  return (
    <div className="p-4 space-y-5">
      {/* 标题 */}
      <div>
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3 flex items-center gap-1.5">
          <Info className="h-3 w-3" /> 镜头详情
        </h4>
      </div>

      <FrameReferenceSection
        item={item}
        project={project}
        frameType="first"
        imageUrl={item.firstFrameImageUrl}
        prompt={item.firstFramePrompt}
        effectivePrompt={item.firstFrameEffectivePrompt}
        onUpdateFrame={onUpdateFrame}
        onUpdateFramePrompt={onUpdateFramePrompt}
        onGenerateFrame={onGenerateFrame}
        onPreviewImage={onPreviewImage}
      />

      <FrameReferenceSection
        item={item}
        project={project}
        frameType="last"
        imageUrl={item.lastFrameImageUrl}
        prompt={item.lastFramePrompt}
        effectivePrompt={item.lastFrameEffectivePrompt}
        onUpdateFrame={onUpdateFrame}
        onUpdateFramePrompt={onUpdateFramePrompt}
        onGenerateFrame={onGenerateFrame}
        onPreviewImage={onPreviewImage}
      />

      {/* 预览图 */}
      {(item.imageUrl ||
        item.referenceImageUrl ||
        item.generatedImageUrl) && (
        <div
          onClick={() => {
            const rawUrl = item.generatedImageUrl || item.imageUrl || item.referenceImageUrl;
            if (rawUrl && onPreviewImage) {
              onPreviewImage(rawUrl, `镜头 #${item.shotNumber || item.autoShotNumber || ""} 画面`);
            }
          }}
          className={cn(
            "rounded-lg overflow-hidden border border-border relative group/preview cursor-zoom-in hover:border-primary/40 transition-colors"
          )}
        >
          <SafeImage
            src={
              resolveMediaUrl(item.generatedImageUrl ||
                item.imageUrl ||
                item.referenceImageUrl)
            }
            alt="镜头画面"
            fallbackType="image"
            className="w-full aspect-video object-cover transition-transform group-hover/preview:scale-102"
          />
          <div className="absolute inset-0 bg-black/0 group-hover/preview:bg-black/25 flex items-center justify-center opacity-0 group-hover/preview:opacity-100 transition-all">
            <ZoomIn className="h-5 w-5 text-white/90" />
          </div>
        </div>
      )}

      {/* 基础属性 */}
      <div className="space-y-2">
        {detailRows.map(
          ({ icon: AttrIcon, label, value }) =>
            value && (
              <div key={label} className="flex items-center gap-2 text-xs">
                <AttrIcon className="h-3 w-3 text-muted-foreground shrink-0" />
                <span className="text-muted-foreground">{label}</span>
                <span className="font-medium ml-auto truncate max-w-[140px]">
                  {value}
                </span>
              </div>
            )
        )}
      </div>

      {/* 画面内容 */}
      {item.content && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2 flex items-center gap-1.5">
            <ImageIcon className="h-3 w-3" /> 画面内容
          </h4>
          <p className="text-xs text-foreground/80 leading-relaxed">
            {item.content}
          </p>
        </div>
      )}

      {/* 场景预期 */}
      {item.sceneExpectation && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
            场景预期
          </h4>
          <p className="text-xs text-muted-foreground leading-relaxed italic">
            {item.sceneExpectation}
          </p>
        </div>
      )}

      {/* 对白 / 旁白 */}
      {item.dialogue && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2 flex items-center gap-1.5">
            <MessageSquare className="h-3 w-3" /> 对白 / 旁白
          </h4>
          <p className="text-xs text-foreground/80 leading-relaxed italic">
            「{item.dialogue}」
          </p>
        </div>
      )}

      {/* 音效 & 音乐 */}
      {(item.soundEffect || item.music || item.sound) && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2 flex items-center gap-1.5">
            <Volume2 className="h-3 w-3" /> 声音
          </h4>
          <div className="space-y-1.5">
            {item.sound && (
              <div className="flex items-center gap-1.5 text-xs">
                <span className="text-muted-foreground">声音:</span>
                <span>{item.sound}</span>
              </div>
            )}
            {item.soundEffect && (
              <div className="flex items-center gap-1.5 text-xs">
                <span className="text-muted-foreground">音效:</span>
                <span>{item.soundEffect}</span>
              </div>
            )}
            {item.music && (
              <div className="flex items-center gap-1.5 text-xs">
                <Music className="h-3 w-3 text-muted-foreground shrink-0" />
                <span>{item.music}</span>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 参考图 */}
      {item.referenceImageUrl && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2 flex items-center gap-1.5">
            <ImageIcon className="h-3 w-3" /> 参考图
          </h4>
          <div className="rounded-lg overflow-hidden border border-border">
            <SafeImage
              src={resolveMediaUrl(item.referenceImageUrl)}
              alt="参考图"
              fallbackType="image"
              className="w-full aspect-video object-cover"
            />
          </div>
        </div>
      )}

      {/* 备注 */}
      {item.remark && (
        <div className="border-t border-border pt-4">
          <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2 flex items-center gap-1.5">
            <FileText className="h-3 w-3" /> 备注
          </h4>
          <p className="text-xs text-muted-foreground leading-relaxed">
            {item.remark}
          </p>
        </div>
      )}

      {/* ===== 关联资产 ===== */}
      {assetsLoading && (
        <div className="border-t border-border pt-4 flex items-center justify-center py-4">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
        </div>
      )}

      {!assetsLoading && (
        <div className="border-t border-border pt-4 space-y-4">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
              <Users className="h-3 w-3" /> 关联资产
            </h4>
            {onEditAssets && (
              <button
                onClick={onEditAssets}
                className="text-[10px] text-primary hover:underline font-medium"
              >
                编辑关联
              </button>
            )}
          </div>

          {!hasLinkedAssets ? (
            <p className="text-[11px] text-muted-foreground/50 italic pl-1">
              暂无镜头关联资产，请点击右上角编辑关联
            </p>
          ) : (
            (
              [
                ["character", linkedAssets.characters],
                ["scene", linkedAssets.scenes],
                ["prop", linkedAssets.props],
              ] as [keyof typeof typeConfig, (Asset & { items: AssetItem[] })[]][]
            ).map(
              ([type, assets]) =>
                assets.length > 0 && (
                  <LinkedAssetGroup
                    key={type}
                    type={type}
                    assets={assets}
                    onAssetClick={(id) =>
                      router.push(
                        `/projects/${projectId}/assets?highlight=${id}`
                      )
                    }
                    onPreviewImage={onPreviewImage}
                  />
                )
            )
          )}
        </div>
      )}
    </div>
  );
}

/** 关联资产分组展示（和表格页一致的上下布局，左右排列，无边框） */
function LinkedAssetGroup({
  type,
  assets,
  onAssetClick,
  onPreviewImage,
}: {
  type: keyof typeof typeConfig;
  assets: (Asset & { items: AssetItem[] })[];
  onAssetClick: (id: number) => void;
  onPreviewImage?: (url: string, title: string) => void;
}) {
  const config = typeConfig[type];
  const Icon = config.icon;

  // 将主资产与其关联的子资产打平，展示为独立的项，和表格页完全一致
  const itemsToShow = assets.flatMap((asset) => {
    if (asset.items.length === 0) {
      return [{ asset, sub: null as AssetItem | null }];
    }
    return asset.items.map((sub) => ({ asset, sub: sub as AssetItem | null }));
  });

  return (
    <div className="border-t border-border pt-3">
      <h5 className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-3 flex items-center gap-1.5">
        <Icon className={cn("h-3 w-3", config.color)} />
        {config.label}
        <span className="text-[10px] font-normal text-muted-foreground/60 ml-auto">
          {itemsToShow.length}
        </span>
      </h5>
      
      <div className="flex flex-row flex-wrap gap-x-4 gap-y-3.5 px-1 py-1">
        {itemsToShow.map(({ asset, sub }, index) => {
          const displayName = !sub || !sub.name || sub.name === "初始设定" || sub.name === asset.name
            ? asset.name
            : sub.name;
          
          const imageUrl = sub?.imageUrl || asset.coverUrl;
          const letter = displayName.charAt(0);
          
          // 根据类型定制颜色
          const themeClass = type === "character"
            ? "bg-blue-500/10 text-blue-500 border-blue-500/20 text-blue-500 dark:text-blue-400 group-hover/asset:text-blue-600"
            : type === "scene"
            ? "bg-green-500/10 text-green-500 border-green-500/20 text-green-500 dark:text-green-400 group-hover/asset:text-green-600"
            : "bg-amber-500/10 text-amber-500 border-amber-500/20 text-amber-500 dark:text-amber-400 group-hover/asset:text-amber-600";

          return (
            <Tooltip key={`${asset.id}-${sub?.id || index}`}>
              <TooltipTrigger
                render={
                  <div
                    onClick={() => onAssetClick(asset.id)}
                    className="flex flex-col items-center group/asset cursor-pointer select-none shrink-0"
                  >
                    <div className="relative">
                      {imageUrl ? (
                        <div
                          onClick={(e) => {
                            if (onPreviewImage) {
                              e.stopPropagation();
                              onPreviewImage(imageUrl, `${config.label}: ${displayName}`);
                            }
                          }}
                          className={cn(
                            "h-11 w-11 bg-muted/30 border border-border/10 overflow-hidden shrink-0 flex items-center justify-center relative group/coverimg cursor-zoom-in",
                            type === "character" ? "rounded-full" : "rounded-lg"
                          )}
                        >
                          <SafeImage
                            src={resolveMediaUrl(imageUrl)}
                            alt={displayName}
                            fallbackType={type === "character" ? "avatar" : type === "scene" ? "scene" : "prop"}
                            className="w-full h-full object-cover transition-transform duration-200 group-hover/asset:scale-110"
                          />
                          <div className="absolute inset-0 bg-black/0 group-hover/coverimg:bg-black/25 flex items-center justify-center opacity-0 group-hover/coverimg:opacity-100 transition-all">
                            <ZoomIn className="h-3 w-3 text-white/90" />
                          </div>
                        </div>
                      ) : (
                        <div
                          className={cn(
                            "h-11 w-11 border flex items-center justify-center font-semibold text-xs shrink-0 transition-colors duration-200",
                            type === "character" ? "rounded-full" : "rounded-lg",
                            themeClass.split(" ").slice(0, 3).join(" ") // 只提取 bg, text, border 相关类
                          )}
                        >
                          {letter}
                        </div>
                      )}
                    </div>
                    <span
                      className={cn(
                        "text-[10px] font-semibold truncate max-w-[56px] leading-tight text-center mt-1.5 transition-colors duration-200",
                        themeClass.split(" ").slice(3).join(" ") // 提取文字颜色类
                      )}
                    >
                      {displayName}
                    </span>
                  </div>
                }
              />
              <TooltipContent className="max-w-xs flex flex-col gap-1 text-left items-start px-3.5 py-2.5 rounded-xl text-xs leading-normal bg-white/85 dark:bg-zinc-900/85 backdrop-blur-md border border-zinc-200/50 dark:border-zinc-800/50 text-zinc-900 dark:text-zinc-50 shadow-lg [&_.bg-foreground]:bg-white/85 [&_.fill-foreground]:fill-white/85 dark:[&_.bg-foreground]:bg-zinc-900/85 dark:[&_.fill-foreground]:fill-zinc-900/85">
                <span className="font-semibold text-xs">{config.label}: {displayName}</span>
                {asset.description && (
                  <span className="text-[10px] opacity-80 leading-normal max-w-[200px] break-words mt-0.5">
                    {asset.description}
                  </span>
                )}
              </TooltipContent>
            </Tooltip>
          );
        })}
      </div>
    </div>
  );
}

function StoryboardOverview({
  storyboard,
  items,
}: {
  storyboard: Storyboard;
  items: StoryboardItem[];
}) {
  const totalDuration = items.reduce(
    (sum, item) => sum + (item.duration || 0),
    0
  );
  const withImage = items.filter(
    (i) => i.firstFrameImageUrl || i.lastFrameImageUrl || i.imageUrl || i.generatedImageUrl || i.referenceImageUrl
  ).length;

  return (
    <div className="p-4 space-y-5">
      <div>
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3 flex items-center gap-1.5">
          <Info className="h-3 w-3" /> 分镜概览
        </h4>
        <p className="text-sm font-semibold mb-1">
          {storyboard.title || "分镜表"}
        </p>
        {storyboard.description && (
          <p className="text-xs text-muted-foreground leading-relaxed">
            {storyboard.description}
          </p>
        )}
      </div>

      {/* 统计 */}
      <div className="border-t border-border pt-4">
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">
          统计
        </h4>
        <div className="grid grid-cols-2 gap-3">
          <div className="text-center p-2.5 rounded-lg bg-muted/20">
            <p className="text-lg font-bold text-primary">{items.length}</p>
            <p className="text-[10px] text-muted-foreground">总镜头数</p>
          </div>
          <div className="text-center p-2.5 rounded-lg bg-muted/20">
            <p className="text-lg font-bold text-indigo-400">
              {totalDuration > 0 ? `${totalDuration}s` : "—"}
            </p>
            <p className="text-[10px] text-muted-foreground">总时长</p>
          </div>
          <div className="text-center p-2.5 rounded-lg bg-muted/20">
            <p className="text-lg font-bold text-amber-400">{withImage}</p>
            <p className="text-[10px] text-muted-foreground">有画面</p>
          </div>
          <div className="text-center p-2.5 rounded-lg bg-muted/20">
            <p className="text-lg font-bold text-violet-400">
              {items.length - withImage}
            </p>
            <p className="text-[10px] text-muted-foreground">无画面</p>
          </div>
        </div>
      </div>
    </div>
  );
}

// ========== 主面板 ==========

export function StoryboardRefPanel({
  storyboard,
  items,
  selectedItem,
  activeSceneGroup,
  projectId,
  project,
  assetLookup,
  onUpdateFrame,
  onUpdateFramePrompt,
  onGenerateFrame,
  onEditSceneState,
  onEditAssets,
}: {
  storyboard: Storyboard;
  items: StoryboardItem[];
  selectedItem: StoryboardItem | null;
  activeSceneGroup?: SceneWithItems | null;
  projectId: number;
  project?: Project | null;
  assetLookup?: Record<number, { item: AssetItem; asset: Asset }>;
  onUpdateFrame?: (itemId: number, frameType: StoryboardFrameType, imageUrl: string | null) => Promise<void> | void;
  onUpdateFramePrompt?: (itemId: number, frameType: StoryboardFrameType, prompt: string | null) => Promise<void> | void;
  onGenerateFrame?: (item: StoryboardItem, frameType: StoryboardFrameType, prompt: string) => Promise<void> | void;
  onEditSceneState?: (sceneId: number) => void;
  onEditAssets?: (item: StoryboardItem) => void;
}) {
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState<string>("");

  const handlePreviewImage = useCallback((url: string, title: string) => {
    setPreviewImageUrl(url);
    setPreviewImageTitle(title);
  }, []);

  const showShot = selectedItem;

  return (
    <div className="w-full lg:w-72 border-l border-border flex flex-col shrink-0 bg-card/50 overflow-y-auto h-full relative">
      {showShot ? (
        <>
          <ItemDetail
            item={selectedItem}
            projectId={projectId}
            project={project}
            assetLookup={assetLookup}
            onUpdateFrame={onUpdateFrame}
            onUpdateFramePrompt={onUpdateFramePrompt}
            onGenerateFrame={onGenerateFrame}
            onEditAssets={() => onEditAssets?.(selectedItem)}
            onPreviewImage={handlePreviewImage}
          />
          {activeSceneGroup && (
            <>
              <div className="mx-4 border-t border-border/30" />
              <SceneAssetPanel
                sceneGroup={activeSceneGroup}
                projectId={projectId}
                onPreviewImage={handlePreviewImage}
                onEditSceneState={onEditSceneState}
              />
            </>
          )}
        </>
      ) : activeSceneGroup ? (
        <SceneAssetPanel
          sceneGroup={activeSceneGroup}
          projectId={projectId}
          onPreviewImage={handlePreviewImage}
          onEditSceneState={onEditSceneState}
        />
      ) : (
        <StoryboardOverview
          storyboard={storyboard}
          items={items}
        />
      )}

      {/* 图片大图预览灯箱 */}
      {previewImageUrl && (
        <div 
          className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/85 backdrop-blur-md p-4 animate-in fade-in duration-200"
          onClick={() => setPreviewImageUrl(null)}
        >
          <div className="relative max-w-[90vw] max-h-[90vh] flex flex-col items-center gap-3" onClick={(e) => e.stopPropagation()}>
            <button
              onClick={() => setPreviewImageUrl(null)}
              className="absolute -top-12 right-0 p-1.5 rounded-full bg-white/10 hover:bg-white/20 text-white transition-colors"
              type="button"
            >
              <X className="h-5 w-5" />
            </button>
            <SafeImage
              src={resolveMediaUrl(previewImageUrl)}
              alt={previewImageTitle}
              fallbackType="image"
              className="max-w-full max-h-[80vh] rounded-lg object-contain shadow-2xl border border-white/10 select-none pointer-events-none"
            />
            <p className="text-white/90 text-xs font-medium px-3 py-1.5 rounded-full bg-black/40 backdrop-blur-sm border border-white/5">
              {previewImageTitle}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
