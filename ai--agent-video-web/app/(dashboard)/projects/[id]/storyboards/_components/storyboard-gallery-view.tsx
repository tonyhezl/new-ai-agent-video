"use client";

import React, { memo, useState, useMemo } from "react";
import {
  Film,
  Plus,
  Clock,
  Image as ImageIcon,
  GripVertical,
  Video,
  Play,
  Trash2,
  Camera,
  MessageSquare,
  Volume2,
  Sparkles,
} from "lucide-react";
import { VideoPreviewDialog } from "@/components/dashboard/video-preview-dialog";
import { cn } from "@/lib/utils";
import { resolveMediaUrl } from "@/lib/api/client";
import type { StoryboardFrameType, StoryboardItem } from "@/lib/api/storyboard";
import { SafeImage } from "@/components/ui/safe-image";
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragStartEvent,
  DragEndEvent,
  DragOverlay,
  defaultDropAnimationSideEffects,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  rectSortingStrategy,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

type SortableBindings = {
  attributes?: ReturnType<typeof useSortable>["attributes"];
  listeners?: ReturnType<typeof useSortable>["listeners"];
};

interface AssetLookupEntry {
  asset: { name: string; description?: string | null };
  item: { name?: string | null; imageUrl?: string | null };
}

function parseIds(str?: string | null): number[] {
  if (!str) return [];
  return str
    .split(",")
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isFinite(n) && n > 0);
}

// ========== 单个瀑布流镜头卡片 ==========

const GalleryCard = memo(
  React.forwardRef<
    HTMLDivElement,
    {
      item: StoryboardItem;
      idx: number;
      isSelected: boolean;
      onSelect?: () => void;
      onVideoGen?: (itemId: number) => void;
      onDeleteItem?: (itemId: number) => void;
      onOpenFrameDialog?: (
        item: StoryboardItem,
        frameType: StoryboardFrameType
      ) => void;
      onPreviewVideo?: (videoUrl: string) => void;
      onPreviewImage?: (url: string, title: string) => void;
      onEditAssets?: (item: StoryboardItem) => void;
      assetLookup?: Record<number, AssetLookupEntry>;
      attributes?: SortableBindings["attributes"];
      listeners?: SortableBindings["listeners"];
      style?: React.CSSProperties;
      isDragging?: boolean;
      isOverlay?: boolean;
    }
  >(
    (
      {
        item,
        idx,
        isSelected,
        onSelect,
        onVideoGen,
        onDeleteItem,
        onOpenFrameDialog,
        onPreviewVideo,
        onPreviewImage,
        assetLookup = {},
        attributes,
        listeners,
        style,
        isDragging,
        isOverlay,
      },
      ref
    ) => {
      const hasImage =
        item.firstFrameImageUrl ||
        item.imageUrl ||
        item.referenceImageUrl ||
        item.generatedImageUrl;
      const hasVideo = item.generatedVideoUrl || item.videoUrl;
      const hasBoth = hasImage && hasVideo;

      const [mediaMode, setMediaMode] = useState<"image" | "video">(
        hasVideo ? "video" : "image"
      );

      const rawVideoUrl = (item.generatedVideoUrl || item.videoUrl || "") as string;
      const videoSrc = resolveMediaUrl(item.generatedVideoUrl || item.videoUrl) || "";
      const imageSrc = (item.firstFrameImageUrl || item.generatedImageUrl || item.imageUrl || item.referenceImageUrl) as string;

      // Asset info
      const charIds = parseIds(item.characterIds);
      const charItems = charIds.map((id) => assetLookup[id]).filter(Boolean);

      // 卡片宽度：有图片/视频的大卡，无图的小卡
      const cardWidth = hasImage || hasVideo ? "w-[280px]" : "w-[200px]";

      return (
        <div
          ref={ref}
          style={style}
          onClick={onSelect}
          className={cn(
            "rounded-xl overflow-hidden cursor-pointer relative bg-card flex flex-col",
            "transition-all duration-200 group",
            cardWidth,
            isOverlay
              ? "ring-2 ring-primary shadow-2xl scale-[1.03] z-50 border border-primary/30"
              : isDragging
              ? "opacity-30 z-0 grayscale pointer-events-none"
              : "hover:shadow-lg hover:-translate-y-1",
            !isOverlay && !isDragging && isSelected
              ? "border-2 border-primary ring-4 ring-primary/10"
              : !isOverlay && !isDragging
              ? "border border-border hover:border-primary/40"
              : ""
          )}
        >
          {/* 拖拽手柄 */}
          <div
            {...attributes}
            {...listeners}
            className={cn(
              "absolute top-1.5 left-1.5 z-30 p-1 rounded-md bg-black/50 backdrop-blur-sm",
              "opacity-0 group-hover:opacity-100 cursor-grab hover:bg-black/70 transition-all",
              isOverlay && "cursor-grabbing opacity-100 bg-black/70",
              isDragging && "opacity-0"
            )}
            onClick={(e) => e.stopPropagation()}
          >
            <GripVertical className="h-3 w-3 text-white/90" />
          </div>

          {/* 画面/视频区域 */}
          {(hasImage || hasVideo) && (
            <div className="relative bg-muted/30 overflow-hidden shrink-0 border-b border-border">
              {/* 16:9 比例 */}
              <div className="aspect-video relative">
                {mediaMode === "video" && hasVideo ? (
                  <>
                    <video
                      src={videoSrc}
                      className="w-full h-full object-cover"
                      muted
                      preload="metadata"
                      playsInline
                    />
                    {onPreviewVideo && rawVideoUrl ? (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect?.();
                          onPreviewVideo(rawVideoUrl);
                        }}
                        className="absolute inset-0 flex items-center justify-center bg-black/20 hover:bg-black/40 transition-colors"
                        title="预览视频"
                      >
                        <Play className="h-7 w-7 text-white/90 fill-white/90 drop-shadow-lg" />
                      </button>
                    ) : null}
                  </>
                ) : hasImage ? (
                  <div
                    onClick={(e) => {
                      if (imageSrc && onPreviewImage) {
                        e.stopPropagation();
                        onPreviewImage(imageSrc, `镜头 #${item.shotNumber || idx + 1} 画面`);
                      }
                    }}
                    className={cn("w-full h-full", imageSrc && "cursor-zoom-in")}
                  >
                    <SafeImage
                      src={resolveMediaUrl(imageSrc) || undefined}
                      alt={item.content || `镜头 ${item.shotNumber || idx + 1}`}
                      fallbackType="image"
                      className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
                    />
                  </div>
                ) : (
                  <div className="absolute inset-0 flex flex-col items-center justify-center">
                    <ImageIcon className="h-8 w-8 text-muted-foreground/20 mb-1" />
                    <span className="text-[10px] text-muted-foreground/40">暂无画面</span>
                  </div>
                )}

                {/* 镜号标签 */}
                <div className="absolute top-1.5 right-1.5 px-2 py-0.5 rounded-md bg-black/60 backdrop-blur-sm text-white text-[10px] font-bold z-20 tabular-nums">
                  #{item.shotNumber || item.autoShotNumber || idx + 1}
                </div>

                {/* 时长标签 */}
                {item.duration && (
                  <div className="absolute bottom-1.5 right-1.5 flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-black/60 backdrop-blur-sm text-white text-[10px] z-20">
                    <Clock className="h-2.5 w-2.5 opacity-80" />
                    <span className="font-medium tabular-nums">{item.duration}s</span>
                  </div>
                )}

                {/* 图片/视频切换 */}
                {hasBoth && (
                  <div
                    className="absolute bottom-1.5 left-1.5 flex items-center gap-0.5 p-0.5 rounded-md bg-black/50 backdrop-blur-sm z-20"
                    onClick={(e) => {
                      e.stopPropagation();
                      setMediaMode((m) => (m === "video" ? "image" : "video"));
                    }}
                  >
                    <button
                      className={cn(
                        "p-1 rounded transition-colors",
                        mediaMode === "video"
                          ? "bg-white/20 text-white"
                          : "text-white/50 hover:text-white"
                      )}
                    >
                      <Video className="h-3 w-3" />
                    </button>
                    <button
                      className={cn(
                        "p-1 rounded transition-colors",
                        mediaMode === "image"
                          ? "bg-white/20 text-white"
                          : "text-white/50 hover:text-white"
                      )}
                    >
                      <ImageIcon className="h-3 w-3" />
                    </button>
                  </div>
                )}

                {/* 首尾帧状态指示器 */}
                {onOpenFrameDialog && (item.firstFrameImageUrl || item.lastFrameImageUrl) && (
                  <div className="absolute top-1.5 left-12 flex items-center gap-0.5 z-20">
                    {item.firstFrameImageUrl && (
                      <span className="px-1 py-0.5 rounded bg-emerald-500/80 text-white text-[9px] font-bold">
                        首帧
                      </span>
                    )}
                    {item.lastFrameImageUrl && (
                      <span className="px-1 py-0.5 rounded bg-blue-500/80 text-white text-[9px] font-bold">
                        尾帧
                      </span>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* 内容区域 */}
          <div className="flex flex-col gap-1.5 p-3 flex-1">
            {/* 镜号 + 景别 + 运镜 (无图时显示镜号) */}
            {!hasImage && !hasVideo && (
              <div className="flex items-center gap-1.5 mb-1">
                <span className="px-2 py-0.5 rounded-md bg-muted/40 text-foreground text-[10px] font-bold tabular-nums">
                  #{item.shotNumber || item.autoShotNumber || idx + 1}
                </span>
                {item.duration && (
                  <span className="flex items-center gap-0.5 text-[10px] text-muted-foreground">
                    <Clock className="h-2.5 w-2.5" />
                    {item.duration}s
                  </span>
                )}
              </div>
            )}

            {/* 景别 + 运镜 */}
            {(item.shotType || item.cameraMovement) && (
              <div className="flex items-center gap-1 flex-wrap">
                {item.shotType && (
                  <span className="px-1.5 py-0.5 rounded bg-muted/30 text-[10px] text-muted-foreground">
                    {item.shotType}
                  </span>
                )}
                {item.cameraMovement && (
                  <span className="px-1.5 py-0.5 rounded bg-muted/30 text-[10px] text-muted-foreground">
                    {item.cameraMovement}
                  </span>
                )}
              </div>
            )}

            {/* 画面内容 */}
            {item.content ? (
              <p className="text-[13px] text-foreground/90 leading-snug line-clamp-3">
                {item.content}
              </p>
            ) : (
              !hasImage &&
              !hasVideo && (
                <p className="text-xs text-muted-foreground/30 italic">暂无画面描述</p>
              )
            )}

            {/* 对白 */}
            {item.dialogue && (
              <div className="flex items-start gap-1 text-[11px] text-muted-foreground leading-tight line-clamp-2 pt-1 border-t border-border">
                <MessageSquare className="h-3 w-3 shrink-0 mt-0.5 text-primary/50" />
                <span className="italic">「{item.dialogue}」</span>
              </div>
            )}

            {/* 关联角色 */}
            {charItems.length > 0 && (
              <div className="flex items-center gap-1 flex-wrap pt-1">
                {charItems.slice(0, 3).map((entry, i) => (
                  <span
                    key={i}
                    className="px-1.5 py-0.5 rounded-full bg-primary/8 text-[9px] text-primary/70 font-medium truncate max-w-[80px]"
                  >
                    {entry.item.name || entry.asset.name}
                  </span>
                ))}
                {charItems.length > 3 && (
                  <span className="text-[9px] text-muted-foreground/50">
                    +{charItems.length - 3}
                  </span>
                )}
              </div>
            )}

            {/* 底部操作栏 */}
            <div className="flex items-center gap-1 mt-auto pt-2 border-t border-border opacity-0 group-hover:opacity-100 transition-opacity">
              {onVideoGen && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onVideoGen(item.id);
                  }}
                  className="p-1.5 rounded-md text-muted-foreground hover:text-primary hover:bg-primary/10 transition-colors"
                  title="生成视频"
                >
                  <Video className="h-3.5 w-3.5" />
                </button>
              )}
              {onOpenFrameDialog && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onSelect?.();
                    onOpenFrameDialog(item, "first");
                  }}
                  className="p-1.5 rounded-md text-muted-foreground hover:text-emerald-500 hover:bg-emerald-500/10 transition-colors"
                  title="首尾帧设置"
                >
                  <Camera className="h-3.5 w-3.5" />
                </button>
              )}
              {onDeleteItem && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onDeleteItem(item.id);
                  }}
                  className="p-1.5 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors ml-auto"
                  title="删除镜头"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              )}
            </div>
          </div>
        </div>
      );
    }
  )
);
GalleryCard.displayName = "GalleryCard";

// ========== 可排序包装器 ==========

function SortableGalleryCard(props: {
  item: StoryboardItem;
  idx: number;
  isSelected: boolean;
  onSelect?: () => void;
  onVideoGen?: (itemId: number) => void;
  onDeleteItem?: (itemId: number) => void;
  onOpenFrameDialog?: (item: StoryboardItem, frameType: StoryboardFrameType) => void;
  onPreviewVideo?: (videoUrl: string) => void;
  onPreviewImage?: (url: string, title: string) => void;
  assetLookup?: Record<number, AssetLookupEntry>;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: props.item.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <GalleryCard
      ref={setNodeRef}
      style={style}
      attributes={attributes}
      listeners={listeners}
      isDragging={isDragging}
      {...props}
    />
  );
}

// ========== 主组件 ==========

interface StoryboardGalleryViewProps {
  items: StoryboardItem[];
  selectedItemId?: number | null;
  onSelectItem?: (itemId: number) => void;
  onAddItem?: () => void;
  onDeleteItem?: (itemId: number) => void;
  onReorderItems?: (reordered: StoryboardItem[]) => void;
  onVideoGen?: (itemId: number) => void;
  onOpenFrameDialog?: (item: StoryboardItem, frameType: StoryboardFrameType) => void;
  assetLookup?: Record<number, AssetLookupEntry>;
  onEditAssets?: (item: StoryboardItem) => void;
}

export function StoryboardGalleryView({
  items,
  selectedItemId,
  onSelectItem,
  onAddItem,
  onDeleteItem,
  onReorderItems,
  onVideoGen,
  onOpenFrameDialog,
  assetLookup,
  onEditAssets,
}: StoryboardGalleryViewProps) {
  const [activeItem, setActiveItem] = useState<StoryboardItem | null>(null);
  const [previewVideoUrl, setPreviewVideoUrl] = useState<string | null>(null);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState<string>("");

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const itemIds = useMemo(() => items.map((i) => i.id), [items]);

  const handleDragStart = (event: DragStartEvent) => {
    const item = items.find((i) => i.id === event.active.id);
    if (item) setActiveItem(item);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    setActiveItem(null);
    if (!over || active.id === over.id) return;
    const oldIndex = items.findIndex((i) => i.id === active.id);
    const newIndex = items.findIndex((i) => i.id === over.id);
    if (oldIndex !== -1 && newIndex !== -1 && onReorderItems) {
      onReorderItems(arrayMove(items, oldIndex, newIndex));
    }
  };

  const handleDragCancel = () => setActiveItem(null);

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
    <div className="relative">
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
        onDragCancel={handleDragCancel}
      >
        <SortableContext items={itemIds} strategy={rectSortingStrategy}>
          {/* 瀑布流布局：CSS columns 实现自适应高度 */}
          <div className="gap-3 [column-fill:balance]">
            {items.map((item, idx) => (
              <div key={item.id} className="break-inside-avoid mb-3 inline-block w-full">
                <SortableGalleryCard
                  item={item}
                  idx={idx}
                  isSelected={selectedItemId === item.id}
                  onSelect={() => onSelectItem?.(item.id)}
                  onVideoGen={onVideoGen}
                  onDeleteItem={onDeleteItem}
                  onOpenFrameDialog={onOpenFrameDialog}
                  onPreviewVideo={setPreviewVideoUrl}
                  onPreviewImage={(url, title) => {
                    setPreviewImageUrl(url);
                    setPreviewImageTitle(title);
                  }}
                  assetLookup={assetLookup}
                />
              </div>
            ))}

            {/* 添加卡片 */}
            {onAddItem && (
              <div className="break-inside-avoid mb-3 inline-block w-full">
                <div
                  onClick={onAddItem}
                  className={cn(
                    "rounded-xl border-2 border-dashed border-border",
                    "hover:border-primary/40 hover:bg-primary/5 transition-all duration-200 cursor-pointer",
                    "flex flex-col items-center justify-center text-muted-foreground/40 hover:text-primary group bg-card/30"
                  )}
                  style={{ minHeight: "180px" }}
                >
                  <div className="h-10 w-10 rounded-full bg-muted/30 group-hover:bg-primary/10 flex items-center justify-center transition-colors mb-2">
                    <Plus className="h-5 w-5" />
                  </div>
                  <span className="text-xs font-medium">添加镜头</span>
                </div>
              </div>
            )}
          </div>
        </SortableContext>

        {/* 悬浮拖拽时的替身 */}
        <DragOverlay
          dropAnimation={{
            sideEffects: defaultDropAnimationSideEffects({
              styles: { active: { opacity: "0.5" } },
            }),
          }}
        >
          {activeItem ? (
            <GalleryCard
              item={activeItem}
              idx={items.findIndex((i) => i.id === activeItem.id)}
              isSelected={false}
              isOverlay
            />
          ) : null}
        </DragOverlay>
      </DndContext>

      {/* 视频预览弹窗 */}
      <VideoPreviewDialog
        open={!!previewVideoUrl}
        title="视频预览"
        onClose={() => setPreviewVideoUrl(null)}
        videoUrl={previewVideoUrl || ""}
      />

      {/* 图片预览弹窗 */}
      {previewImageUrl && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm"
          onClick={() => {
            setPreviewImageUrl(null);
            setPreviewImageTitle("");
          }}
        >
          <div className="relative max-w-[90vw] max-h-[90vh]">
            <img
              src={resolveMediaUrl(previewImageUrl) || ""}
              alt={previewImageTitle}
              className="max-w-full max-h-[90vh] object-contain rounded-lg"
            />
            <p className="absolute bottom-2 left-1/2 -translate-x-1/2 px-3 py-1 rounded-md bg-black/60 text-white text-xs">
              {previewImageTitle}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
