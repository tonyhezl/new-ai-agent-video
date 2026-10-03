"use client";

import React, { useState, useRef, useCallback, useEffect } from "react";
import {
  Film,
  Plus,
  Trash2,
  Video,
  Play,
  ZoomIn,
  X,
  GripVertical,
  Camera,
  Users,
  MapPin,
  Wrench,
  Clock,
  MessageSquare,
  Music,
  Volume2,
  Clapperboard,
} from "lucide-react";
import { VideoPreviewDialog } from "@/components/dashboard/video-preview-dialog";
import { cn } from "@/lib/utils";
import { resolveMediaUrl } from "@/lib/api/client";
import type { StoryboardFrameType, StoryboardItem } from "@/lib/api/storyboard";
import { EditableCell } from "./editable-cell";
import { SafeImage } from "@/components/ui/safe-image";
import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
} from "@/components/ui/tooltip";

const TOOLTIP_CONTENT_CLASS =
  "flex flex-col gap-0.5 items-start text-left max-w-[220px] px-2.5 py-1.5 rounded-lg text-[11px] bg-white/85 dark:bg-zinc-900/85 backdrop-blur-md border border-zinc-200/50 dark:border-zinc-800/50 text-zinc-900 dark:text-zinc-50 shadow-lg";

function parseIds(raw: number[] | string | null | undefined): number[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return raw
        .split(",")
        .map((s) => Number(s.trim()))
        .filter((n) => Number.isFinite(n) && n > 0);
    }
  }
  return [];
}

function getAssetDisplayName(
  subItemName: string | null | undefined,
  parentAssetName: string
) {
  const subName = subItemName?.trim();
  const parentName = parentAssetName.trim();
  if (
    !subName ||
    subName === "默认" ||
    subName === "默认变体" ||
    subName === "初始" ||
    subName === parentName
  ) {
    return parentName;
  }
  return `${subName} (${parentName})`;
}

// ========== 单个镜头卡片 ==========

function ShotCard({
  item,
  idx,
  isSelected,
  onSelect,
  onUpdateItemField,
  onVideoGen,
  onDeleteItem,
  onOpenFrameDialog,
  onEditAssets,
  assetLookup = {},
  onPreviewVideo,
  onPreviewImage,
  dragHandleProps,
  isDragging,
}: {
  item: StoryboardItem;
  idx: number;
  isSelected: boolean;
  onSelect: () => void;
  onUpdateItemField: (
    itemId: number,
    field: string,
    value: string | number | null
  ) => void;
  onVideoGen?: (itemId: number) => void;
  onDeleteItem?: (itemId: number) => void;
  onOpenFrameDialog?: (item: StoryboardItem, frameType: StoryboardFrameType) => void;
  onEditAssets?: (item: StoryboardItem) => void;
  assetLookup?: Record<
    number,
    {
      item: import("@/lib/api/asset").AssetItem;
      asset: import("@/lib/api/asset").Asset;
    }
  >;
  onPreviewVideo: (url: string) => void;
  onPreviewImage: (url: string, title: string) => void;
  dragHandleProps?: {
    onDragStart: (e: React.DragEvent) => void;
    onDragEnd: (e: React.DragEvent) => void;
    onDragOver: (e: React.DragEvent) => void;
    draggable: boolean;
  };
  isDragging?: boolean;
}) {
  const framePreviewUrl =
    item.firstFrameImageUrl ||
    item.generatedImageUrl ||
    item.imageUrl ||
    item.referenceImageUrl;
  const hasVideo = item.generatedVideoUrl || item.videoUrl;

  const charIds = parseIds(item.characterIds);
  const sceneId = item.sceneAssetItemId;
  const propIds = parseIds(item.propIds);
  const charItems = charIds.map((id) => assetLookup[id]).filter(Boolean);
  const sceneItem = sceneId ? assetLookup[sceneId] : null;
  const propItems = propIds.map((id) => assetLookup[id]).filter(Boolean);
  const hasAssets = charItems.length > 0 || !!sceneItem || propItems.length > 0;

  return (
    <div
      draggable={dragHandleProps?.draggable}
      onDragStart={dragHandleProps?.onDragStart}
      onDragEnd={dragHandleProps?.onDragEnd}
      onDragOver={dragHandleProps?.onDragOver}
      onClick={onSelect}
      className={cn(
        "relative rounded-2xl border transition-all duration-200 group cursor-pointer overflow-hidden",
        "bg-card/60 backdrop-blur-md shadow-[0_4px_24px_-8px_rgba(0,0,0,0.2)]",
        isSelected
          ? "border-primary/50 shadow-[0_0_30px_rgba(0,229,255,0.1)] ring-1 ring-primary/20"
          : "border-border/50 hover:border-primary/30 hover:bg-card/80 hover:-translate-y-0.5",
        isDragging && "opacity-40"
      )}
    >
      {/* 顶部霓虹渐变条 */}
      <div className="absolute inset-x-0 top-0 h-[3px] bg-gradient-to-r from-cyan-400/60 via-violet-400/60 to-pink-400/60" />
      {/* 主体：左右分栏 */}
      <div className="flex">
        {/* 左侧：媒体区（画面 + 首尾帧 + 视频） */}
        <div className="shrink-0 flex flex-col gap-1.5 p-3 border-r border-border/40">
          {/* 画面缩略图 */}
          <div
            onClick={(e) => {
              if (framePreviewUrl) {
                e.stopPropagation();
                onPreviewImage(
                  framePreviewUrl,
                  `镜头 #${item.shotNumber || item.autoShotNumber || ""} 画面`
                );
              }
            }}
            className={cn(
              "relative w-[160px] aspect-video rounded-lg bg-muted/20 border border-border overflow-hidden shrink-0 group/img",
              framePreviewUrl && "cursor-zoom-in hover:border-primary/40"
            )}
          >
            <SafeImage
              src={resolveMediaUrl(framePreviewUrl)}
              alt="画面"
              fallbackType="image"
              className="w-full h-full object-cover transition-transform group-hover/img:scale-105"
            />
            {framePreviewUrl && (
              <div className="absolute inset-0 bg-black/0 group-hover/img:bg-black/25 flex items-center justify-center opacity-0 group-hover/img:opacity-100 transition-all pointer-events-none">
                <ZoomIn className="h-4 w-4 text-white/90" />
              </div>
            )}
            {/* 镜号 badge */}
            <div className="absolute top-1 left-1 px-1.5 py-0.5 rounded bg-black/60 backdrop-blur-sm text-white text-[10px] font-bold tabular-nums">
              #{item.shotNumber || item.autoShotNumber || idx + 1}
            </div>
          </div>

          {/* 首尾帧 + 视频行 */}
          <div className="flex items-center gap-1.5">
            {/* 首尾帧按钮 */}
            {(["first", "last"] as StoryboardFrameType[]).map((frameType) => {
              const label = frameType === "first" ? "首" : "尾";
              const title =
                frameType === "first" ? "首帧参考图" : "尾帧参考图";
              const hasFrame =
                frameType === "first"
                  ? !!item.firstFrameImageUrl
                  : !!item.lastFrameImageUrl;
              return (
                <Tooltip key={frameType}>
                  <TooltipTrigger
                    render={
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect();
                          onOpenFrameDialog?.(item, frameType);
                        }}
                        disabled={!onOpenFrameDialog}
                        className={cn(
                          "h-7 w-9 rounded-md border text-[10px] font-semibold transition-all",
                          "flex items-center justify-center disabled:opacity-45",
                          hasFrame
                            ? "border-emerald-500/35 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                            : "border-border bg-muted/20 text-muted-foreground hover:border-primary/40 hover:text-primary"
                        )}
                      >
                        {label}
                      </button>
                    }
                  />
                  <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                    {title}：{hasFrame ? "已设置" : "未设置"}
                  </TooltipContent>
                </Tooltip>
              );
            })}

            {/* 视频缩略图 */}
            <div className="relative w-[60px] h-7 rounded-md bg-muted/20 border border-border overflow-hidden shrink-0 group/video">
              {hasVideo ? (
                <>
                  <video
                    src={resolveMediaUrl(item.generatedVideoUrl || item.videoUrl) || ""}
                    className="w-full h-full object-cover"
                    muted
                    preload="metadata"
                    playsInline
                  />
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelect();
                      const rawVideoUrl = item.generatedVideoUrl || item.videoUrl;
                      if (rawVideoUrl) onPreviewVideo(rawVideoUrl);
                    }}
                    className="absolute inset-0 flex items-center justify-center bg-black/20 group-hover/video:bg-black/40 transition-colors"
                  >
                    <Play className="h-3 w-3 text-white/90 fill-white/90" />
                  </button>
                </>
              ) : (
                <Video className="h-3.5 w-3.5 text-muted-foreground/30 absolute inset-0 m-auto" />
              )}
            </div>
          </div>
        </div>

        {/* 中间：所有文本字段 — 玻璃面板布局 */}
        <div className="flex-1 min-w-0 p-3 space-y-2">
          {/* 第一行：景别 / 时长 / 摄像机角度 / 运镜 */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
            <FieldCell
              label="景别"
              value={item.shotType || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "shotType", val)}
              onClick={onSelect}
            />
            <FieldCell
              label="时长"
              value={item.duration ? String(item.duration) : ""}
              placeholder="—"
              suffix="s"
              onSave={(val) =>
                onUpdateItemField(
                  item.id,
                  "duration",
                  val ? Number(val) : null
                )
              }
              onClick={onSelect}
              mono
            />
            <FieldCell
              label="机位"
              value={item.cameraAngle || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "cameraAngle", val)}
              onClick={onSelect}
            />
            <FieldCell
              label="运镜"
              value={item.cameraMovement || ""}
              placeholder="—"
              onSave={(val) =>
                onUpdateItemField(item.id, "cameraMovement", val)
              }
              onClick={onSelect}
            />
          </div>

          {/* 第二行：分镜内容（大区域） */}
          <FieldCell
            label="分镜内容"
            value={item.content || ""}
            placeholder="点击编辑分镜内容…"
            onSave={(val) => onUpdateItemField(item.id, "content", val)}
            onClick={onSelect}
            multiline
            fullWidth
            accent="cyan"
          />

          {/* 第三行：对白 + 声音 + 备注 */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
            <FieldCell
              label="对白"
              value={item.dialogue || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "dialogue", val)}
              onClick={onSelect}
              multiline
            />
            <FieldCell
              label="声音"
              value={item.sound || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "sound", val)}
              onClick={onSelect}
            />
            <FieldCell
              label="备注"
              value={item.remark || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "remark", val)}
              onClick={onSelect}
            />
          </div>

          {/* 第四行：视频提示词 + 关联资产 */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <FieldCell
              label="视频提示词"
              value={item.videoPrompt || ""}
              placeholder="—"
              onSave={(val) => onUpdateItemField(item.id, "videoPrompt", val)}
              onClick={onSelect}
              multiline
              accent="violet"
            />

            {/* 关联资产 */}
            <div
              onClick={(e) => {
                e.stopPropagation();
                onSelect();
                onEditAssets?.(item);
              }}
              className="rounded-xl border border-border/40 bg-white/5 dark:bg-white/[0.03] backdrop-blur-sm p-2.5 transition-all hover:border-amber-400/30 hover:bg-amber-500/5 cursor-pointer min-h-[52px] flex flex-col gap-1"
            >
              <span className="text-[10px] text-amber-400/80 font-bold uppercase tracking-wider shrink-0">
                关联资产
              </span>
              <div className="flex items-center gap-1.5 flex-wrap flex-1">
                {hasAssets ? (
                  <>
                    {/* 角色 */}
                    {charItems.map((ci, i) => (
                      <Tooltip key={`char-${i}`}>
                        <TooltipTrigger
                          render={
                            <div className="flex items-center gap-1 group/asset cursor-pointer shrink-0">
                              <SafeImage
                                src={resolveMediaUrl(ci.item.imageUrl)}
                                alt="avatar"
                                fallbackType="avatar"
                                onClick={(e) => {
                                  if (ci.item.imageUrl) {
                                    e.stopPropagation();
                                    onPreviewImage(
                                      ci.item.imageUrl,
                                      `角色: ${getAssetDisplayName(ci.item.name, ci.asset.name)}`
                                    );
                                  }
                                }}
                                className="h-5 w-5 rounded-full object-cover cursor-zoom-in border border-border/40 shrink-0"
                              />
                              <span className="text-[10px] font-medium text-blue-500 dark:text-blue-400 truncate max-w-[60px]">
                                {getAssetDisplayName(ci.item.name, ci.asset.name)}
                              </span>
                            </div>
                          }
                        />
                        <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                          <span className="font-semibold">角色: {getAssetDisplayName(ci.item.name, ci.asset.name)}</span>
                          {ci.asset.description && (
                            <span className="text-[10px] opacity-80">{ci.asset.description}</span>
                          )}
                        </TooltipContent>
                      </Tooltip>
                    ))}
                    {/* 场景 */}
                    {sceneItem && (
                      <Tooltip>
                        <TooltipTrigger
                          render={
                            <div className="flex items-center gap-1 cursor-pointer shrink-0">
                              <SafeImage
                                src={resolveMediaUrl(sceneItem.item.imageUrl)}
                                alt="scene"
                                fallbackType="scene"
                                onClick={(e) => {
                                  if (sceneItem.item.imageUrl) {
                                    e.stopPropagation();
                                    onPreviewImage(
                                      sceneItem.item.imageUrl,
                                      `场景: ${getAssetDisplayName(sceneItem.item.name, sceneItem.asset.name)}`
                                    );
                                  }
                                }}
                                className="h-5 w-5 rounded-md object-cover cursor-zoom-in border border-border/40 shrink-0"
                              />
                              <span className="text-[10px] font-medium text-green-500 dark:text-green-400 truncate max-w-[60px]">
                                {getAssetDisplayName(sceneItem.item.name, sceneItem.asset.name)}
                              </span>
                            </div>
                          }
                        />
                        <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                          <span className="font-semibold">场景: {getAssetDisplayName(sceneItem.item.name, sceneItem.asset.name)}</span>
                        </TooltipContent>
                      </Tooltip>
                    )}
                    {/* 道具 */}
                    {propItems.map((pi, i) => (
                      <Tooltip key={`prop-${i}`}>
                        <TooltipTrigger
                          render={
                            <div className="flex items-center gap-1 cursor-pointer shrink-0">
                              <SafeImage
                                src={resolveMediaUrl(pi.item.imageUrl)}
                                alt="prop"
                                fallbackType="prop"
                                onClick={(e) => {
                                  if (pi.item.imageUrl) {
                                    e.stopPropagation();
                                    onPreviewImage(
                                      pi.item.imageUrl,
                                      `道具: ${getAssetDisplayName(pi.item.name, pi.asset.name)}`
                                    );
                                  }
                                }}
                                className="h-5 w-5 rounded-md object-cover cursor-zoom-in border border-border/40 shrink-0"
                              />
                              <span className="text-[10px] font-medium text-amber-500 dark:text-amber-400 truncate max-w-[60px]">
                                {getAssetDisplayName(pi.item.name, pi.asset.name)}
                              </span>
                            </div>
                          }
                        />
                        <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                          <span className="font-semibold">道具: {getAssetDisplayName(pi.item.name, pi.asset.name)}</span>
                        </TooltipContent>
                      </Tooltip>
                    ))}
                  </>
                ) : (
                  <span className="text-[10px] text-muted-foreground/35 hover:text-primary transition-colors font-medium flex items-center gap-0.5">
                    <Plus className="h-2.5 w-2.5" />
                    关联资产
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* 右侧：操作按钮列 */}
        <div className="shrink-0 flex flex-col items-center justify-center gap-1.5 p-2 border-l border-border/40">
          {/* 拖拽手柄 */}
          <div className="p-1 text-muted-foreground/40 group-hover:text-muted-foreground/70 cursor-grab active:cursor-grabbing transition-colors">
            <GripVertical className="h-3.5 w-3.5" />
          </div>
          {/* 生成视频 */}
          {onVideoGen && (
            <Tooltip>
              <TooltipTrigger
                render={
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onVideoGen(item.id);
                    }}
                    className="p-1.5 rounded-md text-muted-foreground hover:text-purple-500 hover:bg-purple-500/10 transition-all"
                  >
                    <Video className="h-3.5 w-3.5" />
                  </button>
                }
              />
              <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                生成视频
              </TooltipContent>
            </Tooltip>
          )}
          {/* 删除 */}
          {onDeleteItem && (
            <Tooltip>
              <TooltipTrigger
                render={
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteItem(item.id);
                    }}
                    className="p-1.5 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-all"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                }
              />
              <TooltipContent className={TOOLTIP_CONTENT_CLASS}>
                删除镜头
              </TooltipContent>
            </Tooltip>
          )}
        </div>
      </div>
    </div>
  );
}

// ========== 字段单元格 ==========

function FieldCell({
  label,
  value,
  placeholder,
  onSave,
  onClick,
  multiline,
  fullWidth,
  suffix,
  mono,
  accent,
}: {
  label: string;
  value: string;
  placeholder?: string;
  onSave: (val: string) => void;
  onClick?: () => void;
  multiline?: boolean;
  fullWidth?: boolean;
  suffix?: string;
  mono?: boolean;
  accent?: "cyan" | "violet" | "amber";
}) {
  const accentClasses = {
    cyan: {
      border: "hover:border-cyan-400/30",
      bg: "hover:bg-cyan-500/5",
      label: "text-cyan-400/80",
      glow: "hover:shadow-[0_2px_12px_rgba(0,229,255,0.06)]",
    },
    violet: {
      border: "hover:border-violet-400/30",
      bg: "hover:bg-violet-500/5",
      label: "text-violet-400/80",
      glow: "hover:shadow-[0_2px_12px_rgba(139,92,246,0.06)]",
    },
    amber: {
      border: "hover:border-amber-400/30",
      bg: "hover:bg-amber-500/5",
      label: "text-amber-400/80",
      glow: "hover:shadow-[0_2px_12px_rgba(245,158,11,0.06)]",
    },
  };
  const a = accent ? accentClasses[accent] : {
    border: "hover:border-primary/20",
    bg: "hover:bg-primary/5",
    label: "text-muted-foreground/60",
    glow: "",
  };

  return (
    <div
      className={cn(
        "rounded-xl border border-border/40 bg-white/5 dark:bg-white/[0.03] backdrop-blur-sm p-2.5 transition-all duration-200",
        a.border,
        a.bg,
        a.glow,
        "flex flex-col gap-1.5 min-w-0",
        fullWidth && "w-full"
      )}
    >
      <span className={cn("text-[10px] font-bold uppercase tracking-wider shrink-0", a.label)}>
        {label}
      </span>
      <div className="flex items-baseline gap-0.5">
        <EditableCell
          value={value}
          placeholder={placeholder}
          onSave={onSave}
          onCellClick={onClick}
          className={cn(
            "text-[12px] w-full text-foreground/90 bg-transparent",
            mono && "font-mono tabular-nums text-center"
          )}
          multiline={multiline}
        />
        {suffix && value && (
          <span className="text-[10px] text-muted-foreground/40 shrink-0">
            {suffix}
          </span>
        )}
      </div>
    </div>
  );
}

// ========== 主组件 ==========

export function StoryboardShotListView({
  items,
  selectedItemId,
  onSelectItem,
  onUpdateItemField,
  onAddItem,
  onDeleteItem,
  onReorderItems,
  onVideoGen,
  onOpenFrameDialog,
  assetLookup = {},
  onEditAssets,
}: {
  items: StoryboardItem[];
  selectedItemId: number | null;
  onSelectItem: (id: number | null) => void;
  onUpdateItemField: (
    itemId: number,
    field: string,
    value: string | number | null
  ) => void;
  onAddItem: () => void;
  onDeleteItem: (id: number) => void;
  onReorderItems?: (reorderedItems: StoryboardItem[]) => void;
  onVideoGen?: (itemId: number) => void;
  onOpenFrameDialog?: (item: StoryboardItem, frameType: StoryboardFrameType) => void;
  assetLookup?: Record<
    number,
    {
      item: import("@/lib/api/asset").AssetItem;
      asset: import("@/lib/api/asset").Asset;
    }
  >;
  onEditAssets?: (item: StoryboardItem) => void;
}) {
  const [previewVideoUrl, setPreviewVideoUrl] = useState<string | null>(null);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState<string>("");
  const [dragIdx, setDragIdx] = useState<number | null>(null);
  const [overIdx, setOverIdx] = useState<number | null>(null);
  const dragItemIdx = useRef<number | null>(null);

  const handleDragStart = useCallback(
    (e: React.DragEvent, idx: number) => {
      dragItemIdx.current = idx;
      setDragIdx(idx);
      e.dataTransfer.effectAllowed = "move";
    },
    []
  );

  const handleDragOver = useCallback(
    (e: React.DragEvent, idx: number) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      if (overIdx !== idx) setOverIdx(idx);
    },
    [overIdx]
  );

  const handleDragEnd = useCallback(() => {
    setDragIdx(null);
    setOverIdx(null);
    dragItemIdx.current = null;
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent, idx: number) => {
      e.preventDefault();
      const fromIdx = dragItemIdx.current;
      if (fromIdx === null || fromIdx === idx || !onReorderItems) {
        handleDragEnd();
        return;
      }
      const reordered = [...items];
      const [moved] = reordered.splice(fromIdx, 1);
      reordered.splice(idx, 0, moved);
      onReorderItems(reordered);
      handleDragEnd();
    },
    [items, onReorderItems, handleDragEnd]
  );

  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12">
        <div className="h-12 w-12 rounded-xl bg-muted/20 flex items-center justify-center mb-3 border border-border">
          <Film className="h-5 w-5 text-muted-foreground/30" />
        </div>
        <h3 className="text-sm font-semibold text-foreground mb-1">暂无镜头</h3>
        <p className="text-xs text-muted-foreground/80">点击添加该场次的第一个镜头</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2.5">
      {items.map((item, idx) => (
        <ShotCard
          key={item.id}
          item={item}
          idx={idx}
          isSelected={selectedItemId === item.id}
          onSelect={() => onSelectItem(item.id)}
          onUpdateItemField={onUpdateItemField}
          onVideoGen={onVideoGen}
          onDeleteItem={onDeleteItem}
          onOpenFrameDialog={onOpenFrameDialog}
          onEditAssets={onEditAssets}
          assetLookup={assetLookup}
          onPreviewVideo={setPreviewVideoUrl}
          onPreviewImage={(url, title) => {
            setPreviewImageUrl(url);
            setPreviewImageTitle(title);
          }}
          isDragging={dragIdx === idx}
          dragHandleProps={{
            draggable: true,
            onDragStart: (e) => handleDragStart(e, idx),
            onDragEnd: handleDragEnd,
            onDragOver: (e) => handleDragOver(e, idx),
          }}
        />
      ))}

      {/* 添加按钮 */}
      <button
        onClick={onAddItem}
        className={cn(
          "w-full flex items-center justify-center gap-2 py-3 rounded-xl",
          "border-2 border-dashed border-border hover:border-primary/30",
          "text-muted-foreground hover:text-primary transition-all",
          "text-xs font-medium"
        )}
      >
        <Plus className="h-3.5 w-3.5" />
        添加镜头
      </button>

      {/* 视频预览 */}
      <VideoPreviewDialog
        open={!!previewVideoUrl}
        title="镜头视频预览"
        videoUrl={previewVideoUrl}
        onClose={() => setPreviewVideoUrl(null)}
      />

      {/* 图片大图预览 */}
      {previewImageUrl && (
        <div
          className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/85 backdrop-blur-md p-4 animate-in fade-in duration-200"
          onClick={() => setPreviewImageUrl(null)}
        >
          <div
            className="relative max-w-[90vw] max-h-[90vh] flex flex-col items-center gap-3"
            onClick={(e) => e.stopPropagation()}
          >
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
