"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import {
  Film,
  Plus,
  Trash2,
  GripVertical,
  PanelLeftClose,
  PanelLeftOpen,
  AlertCircle,
  Sparkles,
  Layers,
} from "lucide-react";
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import type { DragEndEvent } from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import {
  storyboardApi,
  type StoryboardEpisode,
  StoryboardScene,
} from "@/lib/api/storyboard";
import type { ScriptEpisode } from "@/lib/api/script";

// ========== 可拖拽场次项 ==========

function SortableSceneItem({
  scene,
  isActive,
  onSelect,
  onDelete,
}: {
  scene: StoryboardScene;
  isActive: boolean;
  onSelect: () => void;
  onDelete: (e: React.MouseEvent) => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: scene.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        "group/scene rounded-lg transition-all overflow-hidden mb-1",
        isDragging && "opacity-50 z-10",
        isActive
          ? "bg-primary/10 border border-primary/20"
          : "border border-transparent hover:border-border hover:bg-muted/20"
      )}
    >
      <div className="flex items-stretch">
        {/* 左侧彩色竖条 */}
        <div className={cn(
          "w-0.5 shrink-0 transition-colors",
          isActive ? "bg-primary" : "bg-transparent group-hover/scene:bg-border"
        )} />
        {/* 拖拽手柄 */}
        <span
          {...attributes}
          {...listeners}
          className="px-1 self-center opacity-0 group-hover/scene:opacity-40 hover:opacity-100! text-muted-foreground cursor-grab active:cursor-grabbing transition-opacity shrink-0"
          onPointerDown={(e) => {
            e.stopPropagation();
            listeners?.onPointerDown?.(e);
          }}
        >
          <GripVertical className="h-3 w-3" />
        </span>
        {/* 主内容 */}
        <button
          onClick={onSelect}
          className={cn(
            "flex-1 flex flex-col gap-0.5 py-2 pr-1.5 pl-1 text-left outline-none min-w-0"
          )}
        >
          {/* 第一行：编号 + 标题 */}
          <div className="flex items-center gap-1.5 min-w-0">
            <span className={cn(
              "text-[9px] font-mono font-bold tabular-nums px-1 py-0.5 rounded shrink-0",
              isActive ? "bg-primary/15 text-primary" : "bg-muted/40 text-muted-foreground"
            )}>
              {scene.sceneNumber || "?"}
            </span>
            <span className={cn(
              "text-[11px] truncate",
              isActive ? "text-primary font-medium" : "text-foreground/80"
            )}>
              {scene.sceneHeading || `场次 ${scene.sceneNumber || "?"}`}
            </span>
          </div>
          {/* 第二行：场景信息 */}
          {(scene.location || scene.intExt || scene.timeOfDay) && (
            <div className="flex items-center gap-1 text-[9px] text-muted-foreground/60 pl-0.5">
              {scene.intExt && <span className="shrink-0">{scene.intExt}</span>}
              {scene.location && <span className="truncate">{scene.location}</span>}
              {scene.timeOfDay && <span className="shrink-0">· {scene.timeOfDay}</span>}
            </div>
          )}
        </button>
        {/* 删除按钮 */}
        <button
          onClick={onDelete}
          className="p-1.5 mr-1 self-center flex items-center shrink-0 rounded-md opacity-0 group-hover/scene:opacity-100 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-all"
          title="删除场次"
        >
          <Trash2 className="h-3 w-3" />
        </button>
      </div>
    </div>
  );
}

interface SidebarSelection {
  type: "all" | "episode" | "scene";
  episodeId?: number;
  sceneId?: number;
}

interface StoryboardSidebarProps {
  storyboardId: number;
  selection: SidebarSelection;
  activeSceneId?: number | null;
  /** 是否以桌面端窄栏形态收起目录 */
  collapsed?: boolean;
  onSelect: (selection: SidebarSelection) => void;
  /** 切换桌面端目录收起状态 */
  onCollapsedChange?: (collapsed: boolean) => void;
  /** 初始加载完成时调用，传递第一集 episodeId（避免通过 onSelect 触发 page 的重复加载） */
  onInitialLoad?: (firstEpisodeId: number) => void;
  onDeleteEpisode?: (id: number) => Promise<boolean | void> | boolean | void;
  onDeleteScene?: (sceneId: number, episodeId: number) => Promise<boolean | void> | boolean | void;
  onReorderScenes?: (episodeId: number, sortedScenes: StoryboardScene[]) => Promise<void>;
  scriptEpisodes?: ScriptEpisode[];
  onBindScriptEpisode?: (storyboardEpisodeId: number, scriptEpisodeId: number) => Promise<StoryboardEpisode>;
  onGenerateEpisodeStoryboard?: (episode: StoryboardEpisode) => Promise<void> | void;
}

export function StoryboardSidebar(props: StoryboardSidebarProps) {
  const {
    storyboardId,
    selection,
    activeSceneId,
    collapsed = false,
    onSelect,
    onCollapsedChange,
    onDeleteEpisode,
    onDeleteScene,
    scriptEpisodes = [],
    onBindScriptEpisode,
    onGenerateEpisodeStoryboard,
  } = props;
  const [episodes, setEpisodes] = useState<StoryboardEpisode[]>([]);
  const [scenesMap, setScenesMap] = useState<
    Record<number, StoryboardScene[]>
  >({});
  const [activeEpisodeId, setActiveEpisodeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [bindingEpisodeIds, setBindingEpisodeIds] = useState<Set<number>>(new Set());
  const initialLoadDoneRef = useRef(false);

  // 监听 invalidation 自动强刷
  const storyboardsInvalidation = usePipelineStore((s) => s.invalidation.storyboards);
  const storyboardsInvRef = useRef(storyboardsInvalidation);
  const sidebarRefreshDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = (episodeId: number) => (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const scenes = scenesMap[episodeId] || [];
    const oldIndex = scenes.findIndex((s) => s.id === active.id);
    const newIndex = scenes.findIndex((s) => s.id === over.id);
    if (oldIndex !== -1 && newIndex !== -1 && props.onReorderScenes) {
      const prevScenes = scenes;
      const sorted = [...prevScenes];
      const [moved] = sorted.splice(oldIndex, 1);
      sorted.splice(newIndex, 0, moved);
      setScenesMap((prev) => ({ ...prev, [episodeId]: sorted }));
      
      props.onReorderScenes(episodeId, sorted).catch(() => {
        setScenesMap((prev) => ({ ...prev, [episodeId]: prevScenes }));
      });
    }
  };

  // 加载分镜集
  const loadEpisodes = useCallback(async () => {
    try {
      setLoading(true);
      const eps = await storyboardApi.listEpisodes(storyboardId);
      setEpisodes(eps);
      if (eps.length > 0) {
        setActiveEpisodeId(eps[0].id);
        const newScenesMap: Record<number, StoryboardScene[]> = {};
        await Promise.all(
          eps.map(async (e) => {
            try {
              const scenes = await storyboardApi.listScenesByEpisode(e.id);
              newScenesMap[e.id] = scenes;
            } catch (err) {
              console.error(`加载分集 ${e.id} 的场次失败:`, err);
            }
          })
        );
        setScenesMap(newScenesMap);
        if (!initialLoadDoneRef.current) {
          initialLoadDoneRef.current = true;
          props.onInitialLoad?.(eps[0].id);
        }
      } else {
        setScenesMap({});
      }
    } catch (err) {
      console.error("加载分镜集失败:", err);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyboardId]);

  useEffect(() => {
    loadEpisodes();
  }, [loadEpisodes]);

  useEffect(() => {
    if (storyboardsInvRef.current === storyboardsInvalidation) return;
    storyboardsInvRef.current = storyboardsInvalidation;
    if (sidebarRefreshDebounceRef.current) clearTimeout(sidebarRefreshDebounceRef.current);
    sidebarRefreshDebounceRef.current = setTimeout(() => {
      sidebarRefreshDebounceRef.current = null;
      loadEpisodes();
    }, 500);
  }, [storyboardsInvalidation, loadEpisodes]);

  // 加载某集下的场次
  const loadScenes = async (episodeId: number) => {
    if (scenesMap[episodeId]) return; // 已加载
    try {
      const scenes = await storyboardApi.listScenesByEpisode(episodeId);
      setScenesMap((prev) => ({ ...prev, [episodeId]: scenes }));
    } catch (err) {
      console.error("加载场次失败:", err);
    }
  };

  // 切换分集
  const selectEpisode = async (epId: number) => {
    setActiveEpisodeId(epId);
    await loadScenes(epId);
    onSelect({ type: "episode", episodeId: epId });
  };

  // 添加分镜集
  const handleAddEpisode = async () => {
    try {
      const ep = await storyboardApi.createEpisode({
        storyboardId,
        episodeNumber: episodes.length + 1,
        title: `第 ${episodes.length + 1} 集`,
        sortOrder: episodes.length,
      });
      setEpisodes([...episodes, ep]);
      setActiveEpisodeId(ep.id);
      setScenesMap((prev) => ({ ...prev, [ep.id]: [] }));
      onSelect({ type: "episode", episodeId: ep.id });
    } catch (err) {
      console.error("创建分镜集失败:", err);
    }
  };

  // 添加分镜场次
  const handleAddScene = async (episodeId: number) => {
    const existingScenes = scenesMap[episodeId] || [];
    try {
      const scene = await storyboardApi.createScene({
        episodeId,
        storyboardId,
        sceneNumber: String(existingScenes.length + 1),
        sortOrder: existingScenes.length,
      });
      setScenesMap((prev) => ({
        ...prev,
        [episodeId]: [...(prev[episodeId] || []), scene],
      }));
      onSelect({ type: "scene", episodeId, sceneId: scene.id });
    } catch (err) {
      console.error("创建场次失败:", err);
    }
  };

  const handleBindScriptEpisode = async (storyboardEpisodeId: number, value: string) => {
    if (!onBindScriptEpisode || !value) return;
    const scriptEpisodeId = Number(value);
    if (!Number.isFinite(scriptEpisodeId)) return;

    setBindingEpisodeIds((prev) => new Set(prev).add(storyboardEpisodeId));
    try {
      const updated = await onBindScriptEpisode(storyboardEpisodeId, scriptEpisodeId);
      setEpisodes((prev) =>
        prev.map((episode) => (episode.id === updated.id ? updated : episode))
      );
    } catch (err) {
      console.error("绑定剧本集失败:", err);
      alert(err instanceof Error ? err.message : "绑定剧本集失败，请重试");
    } finally {
      setBindingEpisodeIds((prev) => {
        const next = new Set(prev);
        next.delete(storyboardEpisodeId);
        return next;
      });
    }
  };

  if (collapsed) {
    return (
      <div className="w-12 border-r border-border flex flex-col shrink-0 bg-card h-full transition-[width] duration-200">
        <div className="px-2 py-3 border-b border-primary/8 flex flex-col items-center gap-2">
          <button
            type="button"
            onClick={() => onCollapsedChange?.(false)}
            className="h-8 w-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary hover:bg-primary/8 transition-colors"
            title="展开分镜目录"
          >
            <PanelLeftOpen className="h-4 w-4" />
          </button>
          <Film className="h-4 w-4 text-primary/70" />
        </div>
      </div>
    );
  }

  return (
    <div className="w-full lg:w-64 border-r border-border flex flex-col shrink-0 bg-card h-full transition-[width] duration-200">
      {/* 标题栏 */}
      <div className="px-4 py-3 border-b border-primary/8 flex items-center justify-between">
        <h3 className="text-xs font-semibold text-primary/80 uppercase tracking-wider">
          分镜目录
        </h3>
        <div className="flex items-center gap-1">
          <span className="text-[10px] text-primary/50 bg-primary/6 px-1.5 py-0.5 rounded-full tabular-nums font-medium">
            {episodes.length} 集
          </span>
          {onCollapsedChange && (
            <button
              type="button"
              onClick={() => onCollapsedChange(true)}
              className="h-6 w-6 rounded-md flex items-center justify-center text-muted-foreground/70 hover:text-primary hover:bg-primary/8 transition-colors"
              title="收起分镜目录"
            >
              <PanelLeftClose className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* 分集标签栏 — 水平滚动 pill 风格 */}
      {!loading && episodes.length > 0 && (
        <div className="px-2 pt-2 pb-1.5 border-b border-border/50">
          <div className="flex items-center gap-1 overflow-x-auto [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]">
            {episodes.map((ep) => {
              const isActiveEp = activeEpisodeId === ep.id;
              const epScenes = scenesMap[ep.id] || [];
              return (
                <button
                  key={ep.id}
                  onClick={() => selectEpisode(ep.id)}
                  className={cn(
                    "shrink-0 flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-medium transition-all",
                    isActiveEp
                      ? "bg-primary/12 text-primary"
                      : "text-muted-foreground hover:text-foreground hover:bg-muted/30"
                  )}
                >
                  <span className={cn(
                    "text-[9px] font-mono font-bold tabular-nums",
                    isActiveEp ? "text-primary" : "text-muted-foreground/50"
                  )}>
                    {ep.episodeNumber || "?"}
                  </span>
                  <span className="truncate max-w-[80px]">
                    {ep.title?.replace(/^第\s*\d+\s*集\s*/, "") || `第${ep.episodeNumber || "?"}集`}
                  </span>
                  {epScenes.length > 0 && (
                    <span className={cn(
                      "text-[8px] tabular-nums px-1 rounded-full",
                      isActiveEp ? "bg-primary/15 text-primary" : "bg-muted/30 text-muted-foreground/50"
                    )}>
                      {epScenes.length}
                    </span>
                  )}
                </button>
              );
            })}
            <button
              onClick={handleAddEpisode}
              className="shrink-0 w-7 h-7 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-primary hover:bg-primary/8 transition-all"
              title="添加分镜集"
            >
              <Plus className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* 当前分集操作栏 */}
      {!loading && activeEpisodeId && (() => {
        const ep = episodes.find((e) => e.id === activeEpisodeId);
        if (!ep) return null;
        const epScenes = scenesMap[ep.id] || [];
        const boundScriptEpisodeIds = new Set(
          episodes
            .filter((item) => item.id !== ep.id && item.scriptEpisodeId != null)
            .map((item) => item.scriptEpisodeId)
        );
        const suggestedScriptEpisode = scriptEpisodes.find(
          (item) => item.episodeNumber === ep.episodeNumber
        );
        return (
          <div className="px-2.5 py-2 border-b border-border/50">
            <div className="flex items-center justify-between gap-1">
              <div className="flex items-center gap-1.5 min-w-0">
                <Layers className="h-3 w-3 text-primary/60 shrink-0" />
                <span className="text-[11px] font-semibold text-foreground truncate">
                  {ep.title || `第 ${ep.episodeNumber || "?"} 集`}
                </span>
                <span className="text-[9px] text-muted-foreground/50 tabular-nums shrink-0">
                  {epScenes.length} 场
                </span>
              </div>
              <div className="flex items-center gap-0.5 shrink-0">
                {ep.scriptEpisodeId != null && onGenerateEpisodeStoryboard && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onGenerateEpisodeStoryboard(ep);
                    }}
                    className="p-1 rounded-md text-indigo-400 hover:text-indigo-300 hover:bg-indigo-500/10 transition-all"
                    title="AI 重新生成本集分镜"
                  >
                    <Sparkles className="h-3 w-3" />
                  </button>
                )}
                <button
                  onClick={() => handleAddScene(ep.id)}
                  className="p-1 rounded-md text-muted-foreground hover:text-primary hover:bg-primary/10 transition-all"
                  title="添加场次"
                >
                  <Plus className="h-3 w-3" />
                </button>
                <button
                  onClick={async (e) => {
                    e.stopPropagation();
                    if (onDeleteEpisode) {
                      const success = await onDeleteEpisode(ep.id);
                      if (success) {
                        const remaining = episodes.filter((o) => o.id !== ep.id);
                        setEpisodes(remaining);
                        setScenesMap((prev) => {
                          const next = { ...prev };
                          delete next[ep.id];
                          return next;
                        });
                        if (remaining.length > 0) {
                          selectEpisode(remaining[0].id);
                        } else {
                          setActiveEpisodeId(null);
                        }
                      }
                    }
                  }}
                  className="p-1 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-all"
                  title="删除分集"
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </div>
            </div>

            {ep.scriptEpisodeId == null && onBindScriptEpisode && (
              <div className="mt-1.5 rounded-md border border-amber-500/20 bg-amber-500/8 px-2 py-1.5">
                <div className="flex items-start gap-1.5 text-[10px] text-amber-300">
                  <AlertCircle className="h-3 w-3 mt-0.5 shrink-0" />
                  <span>未绑定剧本集</span>
                </div>
                <select
                  value=""
                  disabled={bindingEpisodeIds.has(ep.id)}
                  onChange={(event) => handleBindScriptEpisode(ep.id, event.target.value)}
                  className="mt-1 w-full rounded-md border border-border/40 bg-background px-2 py-1 text-[11px] text-foreground outline-none focus:border-primary/50"
                >
                  <option value="">
                    {suggestedScriptEpisode
                      ? `建议绑定第 ${suggestedScriptEpisode.episodeNumber} 集`
                      : "选择剧本集绑定"}
                  </option>
                  {scriptEpisodes.map((scriptEpisode) => (
                    <option
                      key={scriptEpisode.id}
                      value={scriptEpisode.id}
                      disabled={boundScriptEpisodeIds.has(scriptEpisode.id)}
                    >
                      第 {scriptEpisode.episodeNumber} 集 {scriptEpisode.title || ""}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>
        );
      })()}

      {/* 场次列表 — 扁平卡片 */}
      <div className="flex-1 overflow-y-auto py-1.5 px-1.5">
        {loading ? (
          <div className="px-3 py-4 text-[10px] text-muted-foreground/50 text-center">
            加载中…
          </div>
        ) : episodes.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-8">
            <div className="h-10 w-10 rounded-xl bg-muted/20 flex items-center justify-center border border-border">
              <Film className="h-4 w-4 text-muted-foreground/30" />
            </div>
            <p className="text-[10px] text-muted-foreground/50">暂无分镜集</p>
            <button
              onClick={handleAddEpisode}
              className={cn(
                "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-medium",
                "text-primary/60 bg-primary/5 transition-colors",
                "border border-dashed border-primary/20 hover:text-primary hover:bg-primary/10 hover:border-primary/40"
              )}
            >
              <Plus className="h-3 w-3" />
              添加分镜集
            </button>
          </div>
        ) : !activeEpisodeId ? null : (() => {
          const ep = episodes.find((e) => e.id === activeEpisodeId);
          if (!ep) return null;
          const epScenes = scenesMap[ep.id] || [];
          if (epScenes.length === 0) {
            return (
              <div className="flex flex-col items-center gap-2 py-6">
                <p className="text-[10px] text-muted-foreground/40 italic">暂无场次</p>
                <button
                  onClick={() => handleAddScene(ep.id)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-[10px] text-muted-foreground/50 hover:text-muted-foreground rounded-md hover:bg-muted/20 transition-colors border border-dashed border-border/50"
                >
                  <Plus className="h-2.5 w-2.5" />
                  添加场次
                </button>
              </div>
            );
          }
          return (
            <>
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={handleDragEnd(ep.id)}
              >
                <SortableContext
                  items={epScenes.map((s) => s.id)}
                  strategy={verticalListSortingStrategy}
                >
                  {epScenes.map((scene) => (
                    <SortableSceneItem
                      key={scene.id}
                      scene={scene}
                      isActive={activeSceneId === scene.id}
                      onSelect={() =>
                        onSelect({
                          type: "scene",
                          episodeId: ep.id,
                          sceneId: scene.id,
                        })
                      }
                      onDelete={async (e) => {
                        e.stopPropagation();
                        if (onDeleteScene) {
                          const success = await onDeleteScene(scene.id, ep.id);
                          if (success) {
                            setScenesMap((prev) => ({
                              ...prev,
                              [ep.id]: (prev[ep.id] || []).filter((s) => s.id !== scene.id),
                            }));
                          }
                        }
                      }}
                    />
                  ))}
                </SortableContext>
              </DndContext>
              <button
                onClick={() => handleAddScene(ep.id)}
                className="w-full flex items-center gap-1.5 px-2 py-1.5 mt-1 text-[10px] text-muted-foreground/40 hover:text-muted-foreground rounded-md hover:bg-muted/20 transition-colors"
              >
                <Plus className="h-2.5 w-2.5" />
                添加场次
              </button>
            </>
          );
        })()}
      </div>
    </div>
  );
}
