"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { useParams } from "next/navigation";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import {
  Film,
  Plus,
  X,
  Loader2,
  Sparkles,
  Camera,
  Info,
  Clapperboard,
  PlayCircle,
  AlertCircle,
  Mic,
  SlidersHorizontal,
} from "lucide-react";
import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import { VideoPreviewDialog } from "@/components/dashboard/video-preview-dialog";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";
import {
  storyboardApi,
  type Storyboard,
  type StoryboardEpisode,
  type StoryboardFrameType,
  type StoryboardItem,
  type StoryboardScene,
  type ComposeSegmentReq,
} from "@/lib/api/storyboard";
import { StoryboardShotListView } from "./_components/storyboard-shot-list-view";
import {
  StoryboardRefPanel,
} from "./_components/storyboard-ref-panel";
import { StoryboardFrameReferenceDialog } from "./_components/storyboard-frame-reference-dialog";
import { CreateStoryboardDialog } from "./_components/create-dialog";
import { ComposeConfigDialog } from "./_components/compose-config-dialog";
import { EditItemAssetsDialog } from "./_components/edit-assets-dialog";
import { VoiceConfigDialog } from "./_components/voice-config-dialog";
import { VoiceoverPanelDialog } from "./_components/voiceover-panel-dialog";
import { SceneStateTimelineDialog } from "./_components/scene-state-timeline-dialog";
import { assetApi } from "@/lib/api/asset";
import { useFullWidth } from "@/lib/hooks/use-layout";
import { useProject } from "../project-context";
import { Can } from "@/components/permission/can";

interface SidebarSelection {
  type: "all" | "episode" | "scene";
  episodeId?: number;
  sceneId?: number;
}

/** 场次及其条目 */
interface SceneWithItems {
  scene: StoryboardScene;
  items: StoryboardItem[];
}

export default function StoryboardTabPage() {
  const params = useParams();
  const projectId = Number(params.id);
  const { project } = useProject();
  // 使用独立 selector 订阅 action 函数（引用稳定，不会因 store 变化触发重渲染）
  const addPipeline = usePipelineStore((s) => s.addPipeline);
  const attachTaskStream = usePipelineStore((s) => s.attachTaskStream);
  const setPanelExpanded = usePipelineStore((s) => s.setPanelExpanded);
  const setExpandedTaskId = usePipelineStore((s) => s.setExpandedTaskId);
  const setNotificationOpen = usePipelineStore((s) => s.setNotificationOpen);

  // 分镜页始终占满 layout 宽度
  useFullWidth(true);

  const [loading, setLoading] = useState(true);
  const [storyboard, setStoryboard] = useState<Storyboard | null>(null);
  const [scriptEpisodes, setScriptEpisodes] = useState<ScriptEpisode[]>([]);
  const [episodes, setEpisodes] = useState<StoryboardEpisode[]>([]);
  const [showCreateDialog, setShowCreateDialog] = useState(false);

  // 关联资产状态
  const [assetsList, setAssetsList] = useState<import("@/lib/api/asset").AssetWithItems[]>([]);
  const [assetLookup, setAssetLookup] = useState<Record<number, { item: import("@/lib/api/asset").AssetItem; asset: import("@/lib/api/asset").Asset }>>({});
  const [editAssetsOpen, setEditAssetsOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<StoryboardItem | null>(null);

  const loadProjectAssets = useCallback(async () => {
    try {
      const list = await assetApi.listWithItems(projectId);
      setAssetsList(list);

      const lookup: Record<number, { item: import("@/lib/api/asset").AssetItem; asset: import("@/lib/api/asset").Asset }> = {};
      list.forEach((asset) => {
        if (asset.items && Array.isArray(asset.items)) {
          asset.items.forEach((item) => {
            lookup[item.id] = { item, asset };
          });
        }
      });
      setAssetLookup(lookup);
    } catch (err) {
      console.error("加载资产失败:", err);
    }
  }, [projectId]);

  useEffect(() => {
    loadProjectAssets();
  }, [loadProjectAssets]);

  const [selectedItemId, setSelectedItemId] = useState<number | null>(null);
  const [frameDialogItemId, setFrameDialogItemId] = useState<number | null>(null);
  const [frameDialogInitialType, setFrameDialogInitialType] =
    useState<StoryboardFrameType>("first");
  const [sidebarSelection, setSidebarSelection] = useState<SidebarSelection>({
    type: "episode",
  });
  const sidebarSelectionRef = useRef(sidebarSelection);
  useEffect(() => {
    sidebarSelectionRef.current = sidebarSelection;
  }, [sidebarSelection]);

  // 移动端侧边栏状态
  const [rightSheetOpen, setRightSheetOpen] = useState(false);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(min-width: 1024px)");
    const handleResize = (e: MediaQueryListEvent | MediaQueryList) => {
      if (e.matches) {
        setRightSheetOpen(false);
      }
    };
    handleResize(mediaQuery);
    mediaQuery.addEventListener("change", handleResize);
    return () => mediaQuery.removeEventListener("change", handleResize);
  }, []);

  // 按场次分组数据
  const [sceneGroups, setSceneGroups] = useState<SceneWithItems[]>([]);
  const sceneGroupsRef = useRef<SceneWithItems[]>([]);
  const [loadingScenes, setLoadingScenes] = useState(false);

  // 当前选中集的合成状态
  const [currentEpisode, setCurrentEpisode] = useState<StoryboardEpisode | null>(null);

  // 当前选中的 episodeId（episode 或 scene 选择都会有）
  const currentEpisodeId =
    sidebarSelection.type === "episode" || sidebarSelection.type === "scene"
      ? sidebarSelection.episodeId ?? null
      : null;

  // 拉取当前集详情（含合成状态）
  const refreshCurrentEpisode = useCallback(async () => {
    if (!currentEpisodeId) {
      setCurrentEpisode(null);
      return;
    }
    try {
      const ep = await storyboardApi.getEpisode(currentEpisodeId);
      setCurrentEpisode(ep);
    } catch (err) {
      console.error("加载集详情失败:", err);
    }
  }, [currentEpisodeId]);

  const [composedPreviewUrl, setComposedPreviewUrl] = useState<string | null>(null);
  const [runningComposeEpisodeIds, setRunningComposeEpisodeIds] = useState<number[]>([]);
  const [submittingComposeEpisodeIds, setSubmittingComposeEpisodeIds] = useState<number[]>([]);
  const [composeConfigOpen, setComposeConfigOpen] = useState(false);
  const [voiceoverPanelOpen, setVoiceoverPanelOpen] = useState(false);
  const [voiceConfigOpen, setVoiceConfigOpen] = useState(false);
  const [stateTimelineSceneId, setStateTimelineSceneId] = useState<number | null>(null);

  // 滚动定位 refs
  const sceneRefs = useRef<Record<number, HTMLDivElement | null>>({});
  const scrollContainerRef = useRef<HTMLDivElement>(null);

  // 滚动时当前可见的场次 ID（用于侧边栏高亮）
  const [activeSceneId, setActiveSceneId] = useState<number | null>(null);
  // 标记是否由用户点击触发的滚动（此时不要通过 observer 覆盖）
  const isUserScrollRef = useRef(false);

  // ========== 派生数据 ==========

  const allItems = sceneGroups.flatMap((g) => g.items);
  const selectedItem = selectedItemId
    ? allItems.find((i) => i.id === selectedItemId) || null
    : null;
  const frameDialogItem = frameDialogItemId
    ? allItems.find((i) => i.id === frameDialogItemId) || null
    : null;

  // 当前激活场次的分组（用于右侧面板展示场次资产）
  const activeSceneGroup = activeSceneId
    ? sceneGroups.find((g) => g.scene.id === activeSceneId) || null
    : null;
  const stateTimelineSceneGroup = stateTimelineSceneId
    ? sceneGroups.find((group) => group.scene.id === stateTimelineSceneId) || null
    : null;

  useEffect(() => {
    sceneGroupsRef.current = sceneGroups;
  }, [sceneGroups]);

  // 处理镜头选择，同时静默同步定位该镜头所属的场次
  const handleSelectItem = useCallback((itemId: number | null) => {
    setSelectedItemId(itemId);
    if (itemId) {
      const group = sceneGroups.find((g) => g.items.some((item) => item.id === itemId));
      if (group) {
        setActiveSceneId(group.scene.id);
      }
    }
  }, [sceneGroups]);

  // 加载分镜
  const loadStoryboard = useCallback(async () => {
    try {
      setLoading(true);
      const list = await storyboardApi.list(projectId);
      if (list.length > 0) {
        const activeStoryboard = list[0];
        setStoryboard(activeStoryboard);
        if (activeStoryboard.scriptId) {
          const scriptEpisodeList = await scriptApi.listEpisodes(activeStoryboard.scriptId);
          setScriptEpisodes(scriptEpisodeList);
        } else {
          setScriptEpisodes([]);
        }
        const episodeList = await storyboardApi.listEpisodes(activeStoryboard.id);
        setEpisodes(episodeList);
        if (episodeList.length > 0) {
          setSidebarSelection({ type: "episode", episodeId: episodeList[0].id });
        }
      } else {
        setStoryboard(null);
        setScriptEpisodes([]);
        setEpisodes([]);
        setSceneGroups([]);
      }
    } catch (err) {
      console.error("加载分镜失败:", err);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadStoryboard();
  }, [loadStoryboard]);

  const handleAiStoryboard = useCallback(async () => {
    try {
      const scripts = await scriptApi.list(projectId);
      const currentScript = scripts[0] ?? null;

      if (!currentScript) {
        alert("请先创建剧本后再使用 AI 生成分镜");
        return;
      }

      const storyboardTitle =
        currentScript.title?.trim() || project?.name?.trim() || "AI 分镜";
      const scriptDisplayTitle =
        currentScript.title?.trim() || project?.name?.trim() || "未命名项目";

      // 优先复用已有分镜（避免 AI 失败后残留空分镜无法重新生成）
      const existingList = await storyboardApi.list(projectId);
      const existingForScript = existingList.find(
        (sb) => sb.scriptId === currentScript.id
      );
      const targetStoryboard = existingForScript ?? await storyboardApi.create({
        projectId,
        scriptId: currentScript.id,
        title: storyboardTitle,
      });

      const pipelineId = addPipeline({
        label: `AI 生成分镜 - ${scriptDisplayTitle}`,
        projectId,
        request: {
          agentType: "script_to_storyboard",
          category: "pipeline",
          title: `AI 生成分镜：${scriptDisplayTitle}`,
          projectId,
          context: {
            scriptId: currentScript.id,
            storyboardId: targetStoryboard.id,
          },
        },
        onComplete: () => {
          loadStoryboard();
        },
      });

      setPanelExpanded(true);
      setExpandedTaskId(pipelineId);
      await loadStoryboard();
    } catch (err) {
      console.error("创建分镜记录失败:", err);
      alert("创建分镜记录失败，请重试");
    }
  }, [
    addPipeline,
    loadStoryboard,
    project?.name,
    projectId,
    setExpandedTaskId,
    setPanelExpanded,
  ]);

  const refreshStoryboardData = useCallback(async () => {
    try {
      void loadProjectAssets();
      void refreshCurrentEpisode();

      const list = await storyboardApi.list(projectId);
      let activeStoryboard = storyboard;
      if (list.length > 0) {
        activeStoryboard = list[0];
        setStoryboard(list[0]);
        if (activeStoryboard.scriptId) {
          const episodes = await scriptApi.listEpisodes(activeStoryboard.scriptId);
          setScriptEpisodes(episodes);
        } else {
          setScriptEpisodes([]);
        }
      } else {
        setStoryboard(null);
        setScriptEpisodes([]);
        setSceneGroups([]);
        return;
      }

      const selection = sidebarSelectionRef.current;
      let scenes: StoryboardScene[];
      if (selection.type === "episode" || selection.type === "scene") {
        if (selection.episodeId) {
          scenes = await storyboardApi.listScenesByEpisode(selection.episodeId);
        } else {
          scenes = [];
        }
      } else {
        scenes = await storyboardApi.listScenesByStoryboard(activeStoryboard.id);
      }

      const groups = await Promise.all(
        scenes.map(async (scene) => {
          const items = await storyboardApi.listItemsByScene(scene.id);
          return { scene, items };
        })
      );
      setSceneGroups(groups);
    } catch (err) {
      console.error("完整刷新分镜页数据失败:", err);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, loadProjectAssets, refreshCurrentEpisode]);

  const handleBindScriptEpisode = useCallback(async (
    storyboardEpisodeId: number,
    scriptEpisodeId: number
  ) => {
    const updated = await storyboardApi.bindScriptEpisode(storyboardEpisodeId, scriptEpisodeId);
    await refreshStoryboardData();
    return updated;
  }, [refreshStoryboardData]);

  const handleGenerateEpisodeStoryboard = useCallback(async (episode: StoryboardEpisode) => {
    if (!storyboard) return;
    if (!storyboard.scriptId) {
      alert("当前分镜未关联剧本，无法按单集重新生成");
      return;
    }
    if (!episode.scriptEpisodeId) {
      alert("请先绑定剧本集后再重新生成本集分镜");
      return;
    }

    const scriptEpisode = scriptEpisodes.find((item) => item.id === episode.scriptEpisodeId);
    const displayNumber = scriptEpisode?.episodeNumber ?? episode.episodeNumber ?? "?";
    const confirmed = confirm(`将覆盖第 ${displayNumber} 集已有分镜内容，不影响其它集。确定继续？`);
    if (!confirmed) return;

    try {
      await storyboardApi.clearEpisodeContent(episode.id);
      const pipelineId = addPipeline({
        label: `AI 分镜 · 第 ${displayNumber} 集`,
        projectId,
        request: {
          agentType: "episode_storyboard_writer",
          category: "pipeline",
          title: `AI 分镜 · 第 ${displayNumber} 集`,
          message: `请为剧本分集（scriptEpisodeId: ${episode.scriptEpisodeId}）生成分镜，并保存到分镜脚本 ${storyboard.id}。`,
          projectId,
          context: {
            scriptId: storyboard.scriptId,
            storyboardId: storyboard.id,
            scriptEpisodeId: episode.scriptEpisodeId,
          },
        },
        onComplete: () => {
          refreshStoryboardData();
        },
      });

      setPanelExpanded(true);
      setExpandedTaskId(pipelineId);
    } catch (err) {
      console.error("启动单集分镜生成失败:", err);
      alert(err instanceof Error ? err.message : "启动单集分镜生成失败，请重试");
    }
  }, [
    addPipeline,
    projectId,
    refreshStoryboardData,
    scriptEpisodes,
    setExpandedTaskId,
    setPanelExpanded,
    storyboard,
  ]);

  // AI 工具执行后自动刷新（debounce，避免 pipeline 高频事件导致刷新风暴）
  const storyboardsInvalidation = usePipelineStore((s) => s.invalidation.storyboards);
  const storyboardsInvRef = useRef(storyboardsInvalidation);
  const refreshDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (storyboardsInvRef.current === storyboardsInvalidation) return;
    storyboardsInvRef.current = storyboardsInvalidation;
    if (refreshDebounceRef.current) clearTimeout(refreshDebounceRef.current);
    refreshDebounceRef.current = setTimeout(() => {
      refreshDebounceRef.current = null;
      refreshStoryboardData();
    }, 500);
  }, [storyboardsInvalidation, refreshStoryboardData]);

  // 加载场次分组数据
  const loadSceneGroups = useCallback(
    async (episodeId?: number) => {
      if (!storyboard) return;
      setLoadingScenes(true);
      try {
        let scenes: StoryboardScene[];
        if (episodeId) {
          scenes = await storyboardApi.listScenesByEpisode(episodeId);
        } else {
          scenes = await storyboardApi.listScenesByStoryboard(storyboard.id);
        }

        // 并行加载每个场次的条目
        const groups = await Promise.all(
          scenes.map(async (scene) => {
            const items = await storyboardApi.listItemsByScene(scene.id);
            return { scene, items };
          })
        );

        setSceneGroups(groups);
      } catch (err) {
        console.error("加载场次失败:", err);
      } finally {
        setLoadingScenes(false);
      }
    },
    [storyboard]
  );

  // 追踪当前已加载的集ID，避免同集内切换场次重复加载
  const loadedEpisodeIdRef = useRef<number | null>(null);

  // 当侧边栏选择变化时加载数据
  useEffect(() => {
    if (!storyboard) return;

    if (sidebarSelection.type === "all") {
      setActiveSceneId(null);
      loadedEpisodeIdRef.current = null;
      loadSceneGroups();
    } else if (sidebarSelection.type === "episode" && sidebarSelection.episodeId) {
      setActiveSceneId(null);
      loadedEpisodeIdRef.current = sidebarSelection.episodeId;
      loadSceneGroups(sidebarSelection.episodeId);
    } else if (
      sidebarSelection.type === "scene" &&
      sidebarSelection.sceneId
    ) {
      const sceneExists = sceneGroupsRef.current.some(
        (g) => g.scene.id === sidebarSelection.sceneId
      );
      // 同一集且场次已存在于数据中：直接滚动
      if (
        sidebarSelection.episodeId &&
        loadedEpisodeIdRef.current === sidebarSelection.episodeId &&
        sceneExists
      ) {
        setTimeout(() => {
          scrollToScene(sidebarSelection.sceneId!);
        }, 50);
      } else if (sidebarSelection.episodeId) {
        // 不同集 / 首次加载 / 新添加的场次：重新加载数据再滚动
        loadedEpisodeIdRef.current = sidebarSelection.episodeId;
        loadSceneGroups(sidebarSelection.episodeId).then(() => {
          setTimeout(() => {
            scrollToScene(sidebarSelection.sceneId!);
          }, 100);
        });
      }
    }
  }, [sidebarSelection, storyboard, loadSceneGroups]);

  // 滚动到指定场次
  const scrollToScene = (sceneId: number) => {
    isUserScrollRef.current = true;
    setActiveSceneId(sceneId);
    const el = sceneRefs.current[sceneId];
    const container = scrollContainerRef.current;
    if (el && container) {
      const containerTop = container.getBoundingClientRect().top;
      const elTop = el.getBoundingClientRect().top;
      const relativeTop = elTop - containerTop + container.scrollTop;
      container.scrollTo({ top: Math.max(0, relativeTop - 8), behavior: "smooth" });
    }
    // 滚动动画完成后恢复 observer
    setTimeout(() => {
      isUserScrollRef.current = false;
    }, 600);
  };

  // ========== 滚动监听：更新当前可视场次 ==========
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || sceneGroups.length === 0) return;

    let ticking = false;

    const handleScroll = () => {
      if (isUserScrollRef.current) return;

      if (!ticking) {
        window.requestAnimationFrame(() => {
          const scrollTop = container.scrollTop;
          const scrollHeight = container.scrollHeight;
          const clientHeight = container.clientHeight;

          // 1. 如果已滚动到最顶部，直接激活第一个场次
          if (scrollTop === 0) {
            setActiveSceneId(sceneGroups[0].scene.id);
            ticking = false;
            return;
          }

          // 2. 如果已滚动到最底部（解决短场次或大屏幕下，最末尾场次无法卷到顶部触发激活线的问题）
          if (scrollTop + clientHeight >= scrollHeight - 15) {
            setActiveSceneId(sceneGroups[sceneGroups.length - 1].scene.id);
            ticking = false;
            return;
          }

          // 3. 普通滚动过程中，使用较为灵敏的激活线（容器高度的 35%，最大不超过 300px）
          const containerRect = container.getBoundingClientRect();
          let activeId: number | null = null;
          let minDiff = Infinity;
          const triggerY = Math.min(300, containerRect.height * 0.35);

          for (const { scene } of sceneGroups) {
            const el = sceneRefs.current[scene.id];
            if (!el) continue;
            const rect = el.getBoundingClientRect();
            const relativeTop = rect.top - containerRect.top;
            const relativeBottom = rect.bottom - containerRect.top;

            // 判断该场次是否跨越容器顶部的激活线
            if (relativeTop <= triggerY && relativeBottom > triggerY) {
              activeId = scene.id;
              break;
            }

            // 备选：如果没有跨越激活线的，记录离激活线最近的一个
            const diff = Math.abs(relativeTop - triggerY);
            if (diff < minDiff) {
              minDiff = diff;
              activeId = scene.id;
            }
          }

          if (activeId !== null) {
            setActiveSceneId(activeId);
          }
          ticking = false;
        });

        ticking = true;
      }
    };

    container.addEventListener("scroll", handleScroll, { passive: true });
    // 初始化执行一次
    handleScroll();

    return () => {
      container.removeEventListener("scroll", handleScroll);
    };
  }, [sceneGroups]);

  // ========== 操作 ==========

  const handleAddItem = async (sceneId: number, episodeId?: number) => {
    if (!storyboard) return;
    const group = sceneGroups.find((g) => g.scene.id === sceneId);
    const currentItems = group?.items || [];
    try {
      const newItem = await storyboardApi.createItem({
        storyboardId: storyboard.id,
        storyboardSceneId: sceneId,
        storyboardEpisodeId: episodeId,
        sortOrder: currentItems.length,
        shotNumber: String(currentItems.length + 1),
      });
      setSceneGroups((prev) =>
        prev.map((g) =>
          g.scene.id === sceneId
            ? { ...g, items: [...g.items, newItem] }
            : g
        )
      );
      setSelectedItemId(newItem.id);
    } catch (err) {
      console.error("添加新分镜条目失败:", err);
    }
  };

  const handleDeleteEpisode = async (episodeId: number) => {
    if (!confirm("确定要删除该分镜集吗？相关的分镜内容也将被删除。")) return false;
    try {
      await storyboardApi.deleteEpisode(episodeId);
      setEpisodes((prev) => prev.filter((ep) => ep.id !== episodeId));
      if (sidebarSelection.episodeId === episodeId) {
        const remaining = episodes.filter((ep) => ep.id !== episodeId);
        if (remaining.length > 0) {
          setSidebarSelection({ type: "episode", episodeId: remaining[0].id });
        } else {
          setSidebarSelection({ type: "all" });
        }
      }
      loadSceneGroups(sidebarSelection.episodeId === episodeId ? undefined : sidebarSelection.episodeId);
      return true;
    } catch (err) {
      console.error("删除分集失败:", err);
      return false;
    }
  };

  const handleDeleteScene = async (sceneId: number, episodeId: number) => {
    if (!confirm("确定要删除该分镜场次吗？")) return false;
    try {
      await storyboardApi.deleteScene(sceneId);
      if (sidebarSelection.sceneId === sceneId) {
        setSidebarSelection({ type: "episode", episodeId });
      }
      loadSceneGroups(episodeId);
      return true;
    } catch (err) {
      console.error("删除分镜头报错", err);
      return false;
    }
  };

  const handleReorderScenes = async (episodeId: number, sortedScenes: import("@/lib/api/storyboard").StoryboardScene[]) => {
    try {
      await Promise.all(
        sortedScenes.map((scene, idx) =>
          storyboardApi.updateScene({ id: scene.id, sortOrder: idx })
        )
      );
      if (
        sidebarSelection.type === "all" ||
        sidebarSelection.episodeId === episodeId
      ) {
        loadSceneGroups(sidebarSelection.type === "all" ? undefined : episodeId);
      }
    } catch (err) {
      console.error("更新排序失败", err);
      throw err;
    }
  };

  const handleDeleteItem = async (itemId: number) => {
    if (!confirm("确定要删除该镜头吗？")) return;
    try {
      await storyboardApi.deleteItem(itemId);
      setSceneGroups((prev) =>
        prev.map((g) => ({
          ...g,
          items: g.items.filter((i) => i.id !== itemId),
        }))
      );
      if (selectedItemId === itemId) setSelectedItemId(null);
      if (frameDialogItemId === itemId) setFrameDialogItemId(null);
    } catch (err) {
      console.error("删除条目失败:", err);
    }
  };

  const handleUpdateItemField = async (
    itemId: number,
    field: string,
    value: string | number | null
  ) => {
    try {
      const updated = await storyboardApi.updateItem({
        id: itemId,
        [field]: field === "duration" ? (value ? Number(value) : null) : value,
      });
      setSceneGroups((prev) =>
        prev.map((g) => ({
          ...g,
          items: g.items.map((i) => (i.id === updated.id ? updated : i)),
        }))
      );
    } catch (err) {
      console.error("更新条目失败:", err);
    }
  };

  const updateItemInSceneGroups = useCallback((updated: StoryboardItem) => {
    setSceneGroups((prev) =>
      prev.map((g) => ({
        ...g,
        items: g.items.map((i) => (i.id === updated.id ? updated : i)),
      }))
    );
  }, []);

  /** 手动更新镜头首尾帧 */
  const handleUpdateItemFrame = useCallback(
    async (itemId: number, frameType: StoryboardFrameType, imageUrl: string | null) => {
      const item = allItems.find((it) => it.id === itemId);
      const existingPrompt = item
        ? frameType === "first" ? item.firstFramePrompt : item.lastFramePrompt
        : null;
      try {
        const updated = await storyboardApi.updateFrame(itemId, {
          frameType,
          imageUrl,
          prompt: existingPrompt,
        });
        updateItemInSceneGroups(updated);
      } catch (err) {
        console.error("更新镜头首尾帧失败:", err);
        throw err;
      }
    },
    [updateItemInSceneGroups, allItems]
  );

  /** 手动更新镜头首尾帧提示词 */
  const handleUpdateFramePrompt = useCallback(
    async (itemId: number, frameType: StoryboardFrameType, prompt: string | null) => {
      const item = allItems.find((it) => it.id === itemId);
      const existingImageUrl = item
        ? frameType === "first" ? item.firstFrameImageUrl : item.lastFrameImageUrl
        : null;
      try {
        const updated = await storyboardApi.updateFrame(itemId, {
          frameType,
          imageUrl: existingImageUrl,
          prompt,
        });
        updateItemInSceneGroups(updated);
      } catch (err) {
        console.error("更新镜头首尾帧提示词失败:", err);
        throw err;
      }
    },
    [updateItemInSceneGroups, allItems]
  );

  /** 提交镜头首尾帧 AI 生成任务 */
  const handleGenerateItemFrame = useCallback(
    async (item: StoryboardItem, frameType: StoryboardFrameType, prompt: string) => {
      if (!storyboard) {
        throw new Error("缺少分镜上下文，无法生成首尾帧");
      }
      if (frameType === "last" && !item.firstFrameImageUrl) {
        throw new Error("该镜头尚未生成首帧，无法生成尾帧。请先生成首帧。");
      }
      const frameLabel = frameType === "first" ? "首帧" : "尾帧";
      const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);

      // 尾帧复用短路：首帧 + 上镜有尾帧图 → 直接复用，跳过 LLM pipeline
      if (frameType === "first" && item.storyboardSceneId) {
        const group = sceneGroups.find((g) => g.scene.id === item.storyboardSceneId);
        if (group) {
          const idx = group.items.findIndex((it) => it.id === item.id);
          if (idx > 0) {
            const prevItem = group.items[idx - 1];
            if (prevItem.lastFrameImageUrl) {
              try {
                await storyboardApi.reusePrevFrame(item.id);
                await refreshStoryboardData();
                return;
              } catch (err) {
                console.warn("尾帧复用失败，回退到正常生成流程:", err);
              }
            }
          }
        }
      }

      try {
        setNotificationOpen(true);
        const pipelineId = addPipeline({
          label: `生成镜头 ${shotLabel} ${frameLabel}`,
          projectId,
          request: {
            agentType: "storyboard_frame_gen",
            category: "pipeline",
            title: `生成镜头 ${shotLabel} ${frameLabel}`,
            projectId,
            context: {
              selectedStoryboardItemIds: [item.id],
              storyboardId: storyboard.id,
              frameType,
              framePrompt: prompt,
            },
          },
          onComplete: () => {
            void refreshStoryboardData();
          },
        });
        setPanelExpanded(true);
        setExpandedTaskId(pipelineId);
      } catch (err) {
        console.error("提交镜头首尾帧生成任务失败:", err);
        throw err;
      }
    },
    [
      addPipeline,
      projectId,
      refreshStoryboardData,
      sceneGroups,
      setExpandedTaskId,
      setNotificationOpen,
      setPanelExpanded,
      storyboard,
    ]
  );

  /** 打开单个镜头首尾帧编辑弹窗 */
  const handleOpenFrameDialog = useCallback(
    (item: StoryboardItem, frameType: StoryboardFrameType) => {
      setSelectedItemId(item.id);
      setFrameDialogItemId(item.id);
      setFrameDialogInitialType(frameType);
    },
    []
  );

  /** 关闭单个镜头首尾帧编辑弹窗 */
  const handleCloseFrameDialog = useCallback(() => {
    setFrameDialogItemId(null);
  }, []);

  // 拖拽排序
  const handleReorderItems = async (
    sceneId: number,
    reorderedItems: import("@/lib/api/storyboard").StoryboardItem[]
  ) => {
    // 乐观更新本地状态
    setSceneGroups((prev) =>
      prev.map((g) =>
        g.scene.id === sceneId ? { ...g, items: reorderedItems } : g
      )
    );
    // 后台批量更新 sortOrder
    try {
      await storyboardApi.batchUpdateItemSort(
        reorderedItems.map((item) => item.id)
      );
    } catch (err) {
      console.error("更新排序失败:", err);
    }
  };



  useEffect(() => {
    refreshCurrentEpisode();
  }, [refreshCurrentEpisode]);

  /** 提交本集合成视频任务（带选配参数） */
  const handleComposeEpisodeVideo = useCallback(async (
    segments?: ComposeSegmentReq[],
  ) => {
    if (!currentEpisodeId || !currentEpisode) return;
    if (
      submittingComposeEpisodeIds.includes(currentEpisodeId) ||
      runningComposeEpisodeIds.includes(currentEpisodeId) ||
      currentEpisode.composeStatus === 1
    ) {
      return;
    }

    const epLabel = currentEpisode.title?.trim()
      || (currentEpisode.episodeNumber != null ? `第 ${currentEpisode.episodeNumber} 集` : `集 ${currentEpisode.id}`);
    setSubmittingComposeEpisodeIds((prev) =>
      prev.includes(currentEpisodeId) ? prev : [...prev, currentEpisodeId]
    );
    setNotificationOpen(true);
    try {
      const taskId = await storyboardApi.composeEpisodeVideo(currentEpisodeId, segments);
      setSubmittingComposeEpisodeIds((prev) =>
        prev.filter((id) => id !== currentEpisodeId)
      );
      setRunningComposeEpisodeIds((prev) =>
        prev.includes(currentEpisodeId) ? prev : [...prev, currentEpisodeId]
      );

      attachTaskStream({
        label: `合成本集视频：${epLabel}`,
        projectId,
        taskId,
        cancellable: false,
        onSettled: () => {
          setRunningComposeEpisodeIds((prev) =>
            prev.filter((id) => id !== currentEpisodeId)
          );
          void refreshCurrentEpisode();
        },
      });

      void refreshCurrentEpisode();
    } catch (err) {
      console.error("提交合成任务失败:", err);
      setSubmittingComposeEpisodeIds((prev) =>
        prev.filter((id) => id !== currentEpisodeId)
      );
      setRunningComposeEpisodeIds((prev) =>
        prev.filter((id) => id !== currentEpisodeId)
      );
    }
  }, [
    currentEpisodeId,
    currentEpisode,
    submittingComposeEpisodeIds,
    runningComposeEpisodeIds,
    projectId,
    attachTaskStream,
    setNotificationOpen,
    refreshCurrentEpisode,
  ]);

  /** 单个镜头生成视频 */
  const handleVideoGen = useCallback(
    (itemId: number) => {
      if (!storyboard) return;
      const addPipeline = usePipelineStore.getState().addPipeline;
      const setNotificationOpen =
        usePipelineStore.getState().setNotificationOpen;

      addPipeline({
        label: `生成视频 (镜头 #${itemId})`,
        projectId,
        request: {
          agentType: "storyboard_video_gen",
          projectId,
          context: {
            selectedStoryboardItemIds: [itemId],
            storyboardId: storyboard.id,
          },
        },
        onComplete: () => {
          void refreshStoryboardData();
        },
      });
      setNotificationOpen(true);
    },
    [projectId, storyboard, refreshStoryboardData]
  );

  // ========== 渲染 ==========

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  // 空状态：没有分镜
  if (!storyboard) {
    return (
      <>
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="flex flex-col items-center justify-center py-20"
        >
          <div className="h-16 w-16 rounded-2xl bg-muted/20 flex items-center justify-center mb-5 border border-border">
            <Film className="h-8 w-8 text-muted-foreground/40" />
          </div>
          <h2 className="text-lg font-semibold mb-1.5">还没有分镜</h2>
          <p className="text-muted-foreground/70 text-sm mb-6 max-w-md text-center">
            手动创建分镜表并逐条添加镜头，或使用 AI 根据剧本自动生成分镜
          </p>
          <div className="flex items-center gap-3">
            <Can permissionCode="storyboard:edit"><button
              onClick={() => setShowCreateDialog(true)}
              className={cn(
                "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-medium",
                "border border-border bg-card hover:bg-muted/30",
                "text-foreground transition-all duration-200"
              )}
            >
              <Plus className="h-4 w-4" />
              手动创建
            </button></Can>
            <Can permissionCode="storyboard:generate"><button
              onClick={handleAiStoryboard}
              className={cn(
                "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-medium",
                "bg-linear-to-r from-indigo-600 to-blue-600",
                "text-white shadow-lg shadow-indigo-500/20",
                "hover:shadow-indigo-500/30 hover:scale-[1.02]",
                "active:scale-[0.98] transition-all duration-200"
              )}
            >
              <Sparkles className="h-4 w-4" />
              AI 生成分镜
            </button></Can>
          </div>
        </motion.div>
        <Can permissionCode="storyboard:edit"><CreateStoryboardDialog
          open={showCreateDialog}
          projectId={projectId}
          projectName={project?.name}
          onClose={() => setShowCreateDialog(false)}
          onCreated={loadStoryboard}
        /></Can>
      </>
    );
  }

  // 有分镜：全宽单列布局
  return (
    <motion.div
      variants={{ hidden: { opacity: 0 }, visible: { opacity: 1, transition: { staggerChildren: 0.08, delayChildren: 0.1 } } }}
      initial="hidden"
      animate="visible"
      className="flex flex-col h-full min-h-0 rounded-xl border border-border overflow-hidden bg-card"
    >
      {/* 中栏：按场次分组的分镜内容 */}
      <motion.div variants={{ hidden: { opacity: 0, y: 16 }, visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.25, 0.46, 0.45, 0.94] } } }} className="flex-1 flex flex-col min-w-0 min-h-0">
        {/* 顶部工具栏 */}
        <div className="px-4 md:px-5 py-3 border-b border-border flex items-center justify-between shrink-0 bg-card/50 backdrop-blur-sm">
          <div className="flex items-center gap-2 min-w-0">
            <h2 className="text-base font-semibold flex items-center gap-2 overflow-hidden shrink-0">
              <Film className="h-4 w-4 text-primary shrink-0" />
              <span className="truncate">{storyboard.title || "分镜表"}</span>
            </h2>
          </div>
          <div className="flex items-center gap-2">
            {/* 合成本集视频 */}
            {currentEpisodeId && currentEpisode && (() => {
              const cs = currentEpisode.composeStatus;
              const isSubmitting = submittingComposeEpisodeIds.includes(currentEpisodeId);
              const isRunning = runningComposeEpisodeIds.includes(currentEpisodeId) || cs === 1;
              if (isSubmitting) {
                return (
                  <button
                    disabled
                    className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-border bg-muted/20 text-muted-foreground shrink-0 cursor-not-allowed"
                    title="正在提交合成任务"
                  >
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    提交中…
                  </button>
                );
              }
              if (isRunning) {
                return (
                  <button
                    disabled
                    className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-border bg-muted/20 text-muted-foreground shrink-0 cursor-not-allowed"
                    title="正在合成本集视频，预计 30s - 3min"
                  >
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    合成中…
                  </button>
                );
              }
              if (cs === 2 && currentEpisode.composedVideoUrl) {
                return (
                  <div className="hidden sm:flex items-center gap-1 shrink-0">
                    <button
                      onClick={() => setComposedPreviewUrl(currentEpisode.composedVideoUrl)}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-emerald-500/30 bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 transition-colors"
                      title="查看本集合成视频"
                    >
                      <PlayCircle className="h-3.5 w-3.5" />
                      查看本集视频
                    </button>
                    <button
                      onClick={() => setComposeConfigOpen(true)}
                      className="flex items-center justify-center w-8 h-8 rounded-lg border border-border bg-muted/20 text-muted-foreground hover:text-foreground hover:bg-muted/40 transition-colors"
                      title="重新合成"
                    >
                      <Clapperboard className="h-3.5 w-3.5" />
                    </button>
                  </div>
                );
              }
              if (cs === 3) {
                return (
                  <button
                    onClick={() => setComposeConfigOpen(true)}
                    className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-amber-500/30 bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 transition-colors shrink-0"
                    title={`上次失败：${currentEpisode.composeErrorMsg || "未知错误"}\n点击重试`}
                  >
                    <AlertCircle className="h-3.5 w-3.5" />
                    重试合成
                  </button>
                );
              }
              return (
                <button
                  onClick={() => setComposeConfigOpen(true)}
                  className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-primary/30 bg-primary/10 text-primary hover:bg-primary/20 transition-colors shrink-0"
                  title="将本集所有镜头视频按顺序拼接成一个完整视频"
                >
                  <Clapperboard className="h-3.5 w-3.5" />
                  合成本集视频
                </button>
              );
            })()}

            {/* 配音管理 */}
            {currentEpisodeId && (
              <Can permissionCode="storyboard:generate"><button
                onClick={() => setVoiceoverPanelOpen(true)}
                className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-purple-500/30 bg-purple-500/10 text-purple-600 hover:bg-purple-500/20 transition-colors shrink-0"
                title="管理本集配音"
              >
                <Mic className="h-3.5 w-3.5" />
                配音
              </button></Can>
            )}

            {/* 角色音色设置 */}
            <button
              onClick={() => setVoiceConfigOpen(true)}
              className="hidden sm:flex items-center justify-center w-8 h-8 rounded-lg border border-border bg-muted/20 text-muted-foreground hover:text-foreground hover:bg-muted/40 transition-colors shrink-0"
              title="角色音色设置"
            >
              <Mic className="h-3.5 w-3.5" />
            </button>

            <span className="text-xs font-medium text-cyan-400/80 flex items-center gap-1.5 shrink-0">
              <Clapperboard className="h-3.5 w-3.5" />
              列表
            </span>
            {/* 右侧边栏触发器 */}
            <Sheet open={rightSheetOpen} onOpenChange={setRightSheetOpen}>
              <SheetTrigger
                render={
                  <button className="2xl:hidden p-1.5 -mr-1.5 rounded-md hover:bg-muted text-muted-foreground transition-colors shrink-0">
                    <Info className="h-5 w-5" />
                  </button>
                }
              />
              <SheetContent side="right" className="w-[300px] p-0 border-l-0 flex flex-col pt-12 overflow-y-auto">
                <StoryboardRefPanel
                  storyboard={storyboard}
                  items={allItems}
                  selectedItem={selectedItem}
                  activeSceneGroup={activeSceneGroup}
                  projectId={projectId}
                  project={project}
                  assetLookup={assetLookup}
                  onUpdateFrame={handleUpdateItemFrame}
                  onUpdateFramePrompt={handleUpdateFramePrompt}
                  onGenerateFrame={handleGenerateItemFrame}
                  onEditSceneState={(sceneId) => setStateTimelineSceneId(sceneId)}
                  onEditAssets={(item) => {
                    setEditingItem(item);
                    setEditAssetsOpen(true);
                  }}
                />
              </SheetContent>
            </Sheet>
          </div>
        </div>

        {/* 分集/场景选择条 */}
        <div className="px-4 md:px-5 py-2 border-b border-border/50 flex flex-col sm:flex-row gap-2 shrink-0 bg-card/30">
          <div className="flex items-center gap-1 overflow-x-auto [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]">
            {episodes.map((ep) => {
              const isActive = currentEpisodeId === ep.id;
              const epScenes = sceneGroups.filter((g) => g.scene.episodeId === ep.id);
              return (
                <div
                  key={ep.id}
                  className={cn(
                    "shrink-0 flex items-center gap-1.5 pl-2.5 pr-1.5 py-1.5 rounded-md text-[11px] font-medium transition-all",
                    isActive
                      ? "bg-primary/12 text-primary"
                      : "text-muted-foreground hover:text-foreground hover:bg-muted/30"
                  )}
                >
                  <button
                    onClick={() => setSidebarSelection({ type: "episode", episodeId: ep.id })}
                    className="flex items-center gap-1.5"
                  >
                    <span className={cn("text-[9px] font-mono font-bold tabular-nums", isActive ? "text-primary" : "text-muted-foreground/50")}>
                      {ep.episodeNumber || "?"}
                    </span>
                    <span className="truncate max-w-[100px]">
                      {ep.title?.replace(/^第\s*\d+\s*集\s*/, "") || `第${ep.episodeNumber || "?"}集`}
                    </span>
                    <span className={cn("text-[8px] tabular-nums px-1 rounded-full", isActive ? "bg-primary/15 text-primary" : "bg-muted/30 text-muted-foreground/50")}>
                      {epScenes.length}
                    </span>
                  </button>
                  <button
                    onClick={async (e) => {
                      e.stopPropagation();
                      const success = await handleDeleteEpisode(ep.id);
                      if (success) {
                        void loadStoryboard();
                      }
                    }}
                    className={cn(
                      "p-0.5 rounded-sm transition-colors",
                      isActive ? "hover:bg-primary/15 text-primary/60 hover:text-primary" : "hover:bg-muted/30 text-muted-foreground/40 hover:text-destructive"
                    )}
                    title="删除分集"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </div>
              );
            })}
            <button
              onClick={async () => {
                try {
                  const ep = await storyboardApi.createEpisode({
                    storyboardId: storyboard.id,
                    episodeNumber: episodes.length + 1,
                    title: `第 ${episodes.length + 1} 集`,
                    sortOrder: episodes.length,
                  });
                  setEpisodes([...episodes, ep]);
                  setSidebarSelection({ type: "episode", episodeId: ep.id });
                } catch (err) {
                  console.error("创建分镜集失败:", err);
                }
              }}
              className="shrink-0 w-7 h-7 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-primary hover:bg-primary/8 transition-all"
              title="添加分镜集"
            >
              <Plus className="h-3.5 w-3.5" />
            </button>
          </div>

          {sceneGroups.length > 0 && (
            <div className="flex items-center gap-1 overflow-x-auto [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none] sm:ml-auto">
              {sceneGroups.map((group) => (
                <button
                  key={group.scene.id}
                  onClick={() => {
                    setActiveSceneId(group.scene.id);
                    scrollToScene(group.scene.id);
                  }}
                  className={cn(
                    "shrink-0 px-2 py-1 rounded-md text-[11px] transition-all",
                    activeSceneId === group.scene.id
                      ? "bg-primary/10 text-primary"
                      : "text-muted-foreground/60 hover:text-foreground hover:bg-muted/20"
                  )}
                >
                  {group.scene.sceneNumber || "?"}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* 内容区域 - 按场次滚动 */}
        <div
          ref={scrollContainerRef}
          className="flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-6"
        >
          {loadingScenes ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : sceneGroups.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20">
              <div className="h-16 w-16 rounded-2xl bg-muted/20 flex items-center justify-center mb-4 border border-border">
                <Camera className="h-8 w-8 text-muted-foreground/30" />
              </div>
              <p className="text-sm text-muted-foreground mb-1">
                暂无场次
              </p>
              <p className="text-xs text-muted-foreground/60 mb-5">
                请在左侧目录创建分镜集和场次，或使用 AI 重新生成分镜
              </p>
              <button
                onClick={handleAiStoryboard}
                className={cn(
                  "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-medium",
                  "bg-linear-to-r from-indigo-600 to-blue-600",
                  "text-white shadow-lg shadow-indigo-500/20",
                  "hover:shadow-indigo-500/30 hover:scale-[1.02]",
                  "active:scale-[0.98] transition-all duration-200"
                )}
              >
                <Sparkles className="h-4 w-4" />
                AI 重新生成分镜
              </button>
            </div>
          ) : (
            sceneGroups.map(({ scene, items }) => (
              <div
                key={scene.id}
                data-scene-id={scene.id}
                ref={(el) => {
                  sceneRefs.current[scene.id] = el;
                }}
                className={cn(
                  "scroll-mt-4 rounded-xl border transition-all duration-500 ease-out overflow-hidden",
                  activeSceneId === scene.id
                    ? "bg-violet-500/5 border-violet-500/20 shadow-[0_2px_12px_-3px_rgba(139,92,246,0.08)]"
                    : "border-border bg-card/30 hover:bg-card/50"
                )}
                onClick={() => setActiveSceneId(scene.id)}
              >
                {/* 场次标题栏 — 电影感设计 */}
                <div
                  className="flex items-center gap-3 px-4 py-2.5 cursor-pointer group/title border-b border-inherit"
                  onClick={() => setActiveSceneId(scene.id)}
                >
                  {/* 左侧彩色竖条 */}
                  <div className={cn(
                    "w-1 h-6 rounded-full shrink-0 transition-colors",
                    activeSceneId === scene.id ? "bg-violet-500" : "bg-primary/30 group-hover/title:bg-primary/50"
                  )} />
                  {/* 场次编号 */}
                  <span className={cn(
                    "text-[10px] font-mono font-bold tabular-nums px-2 py-0.5 rounded shrink-0 transition-colors",
                    activeSceneId === scene.id
                      ? "bg-violet-500/15 text-violet-600 dark:text-violet-400"
                      : "bg-muted/40 text-muted-foreground group-hover/title:text-foreground"
                  )}>
                    SC{scene.sceneNumber || scene.id}
                  </span>
                  {/* 场次标题 */}
                  <h3 className={cn(
                    "text-sm font-semibold transition-colors truncate",
                    activeSceneId === scene.id ? "text-violet-600 dark:text-violet-400" : "group-hover/title:text-primary"
                  )}>
                    {scene.sceneHeading ||
                      `场次 ${scene.sceneNumber || scene.id}`}
                  </h3>
                  {/* 场景信息标签 */}
                  {scene.location && (
                    <div className="hidden sm:flex items-center gap-1 text-[10px] text-muted-foreground shrink-0">
                      {scene.intExt && <span className="px-1.5 py-0.5 rounded bg-muted/30">{scene.intExt}</span>}
                      <span className="px-1.5 py-0.5 rounded bg-muted/30">{scene.location}</span>
                      {scene.timeOfDay && <span className="px-1.5 py-0.5 rounded bg-muted/30">{scene.timeOfDay}</span>}
                    </div>
                  )}
                  {/* 右侧操作区 */}
                  <div className="ml-auto flex items-center gap-2 shrink-0">
                    <span className={cn(
                      "text-[10px] font-medium tabular-nums px-2 py-0.5 rounded-full transition-colors",
                      activeSceneId === scene.id
                        ? "bg-violet-500/10 text-violet-600 dark:text-violet-400"
                        : "bg-muted/30 text-muted-foreground"
                    )}>
                      {items.length} 镜
                    </span>
                    <button
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        setStateTimelineSceneId(scene.id);
                      }}
                      className="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                      title="编辑场景状态时间线"
                    >
                      <SlidersHorizontal className="size-3.5" />
                    </button>
                  </div>
                </div>

                {/* 场次内的镜头列表 */}
                <div className="p-3">
                  <StoryboardShotListView
                    items={items}
                    selectedItemId={selectedItemId}
                    onSelectItem={handleSelectItem}
                    onUpdateItemField={handleUpdateItemField}
                    onAddItem={() =>
                      handleAddItem(scene.id, scene.episodeId)
                    }
                    onDeleteItem={handleDeleteItem}
                    onReorderItems={(reordered) =>
                      handleReorderItems(scene.id, reordered)
                    }
                    onVideoGen={handleVideoGen}
                    onOpenFrameDialog={handleOpenFrameDialog}
                    assetLookup={assetLookup}
                    onEditAssets={(item) => {
                      setEditingItem(item);
                      setEditAssetsOpen(true);
                    }}
                  />
                </div>
              </div>
            ))
          )}
        </div>
      </motion.div>

      {/* 右栏：引用信息 */}
      <motion.div variants={{ hidden: { opacity: 0, y: 16 }, visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.25, 0.46, 0.45, 0.94] } } }} className="shrink-0 hidden 2xl:block border-l border-border">
      <StoryboardRefPanel
        storyboard={storyboard}
        items={allItems}
        selectedItem={selectedItem}
        activeSceneGroup={activeSceneGroup}
        projectId={projectId}
        project={project}
        assetLookup={assetLookup}
        onUpdateFrame={handleUpdateItemFrame}
        onUpdateFramePrompt={handleUpdateFramePrompt}
        onGenerateFrame={handleGenerateItemFrame}
        onEditSceneState={(sceneId) => setStateTimelineSceneId(sceneId)}
        onEditAssets={(item) => {
          setEditingItem(item);
          setEditAssetsOpen(true);
        }}
      />
      </motion.div>

      <VideoPreviewDialog
        open={!!composedPreviewUrl}
        title="本集合成视频"
        videoUrl={composedPreviewUrl}
        onClose={() => setComposedPreviewUrl(null)}
      />

      <StoryboardFrameReferenceDialog
        key={`${frameDialogItemId ?? "closed"}-${frameDialogInitialType}`}
        open={frameDialogItemId !== null}
        item={frameDialogItem}
        project={project}
        initialFrameType={frameDialogInitialType}
        onClose={handleCloseFrameDialog}
        onUpdateFrame={handleUpdateItemFrame}
        onUpdateFramePrompt={handleUpdateFramePrompt}
        onGenerateFrame={handleGenerateItemFrame}
      />

      <EditItemAssetsDialog
        open={editAssetsOpen}
        item={editingItem}
        assetsList={assetsList}
        onClose={() => {
          setEditAssetsOpen(false);
          setEditingItem(null);
        }}
        onConfirm={async ({ characterIds, sceneAssetItemId, propIds }) => {
          if (!editingItem) return;
          try {
            const updated = await storyboardApi.updateItem({
              id: editingItem.id,
              characterIds: characterIds,
              sceneAssetItemId: sceneAssetItemId,
              propIds: propIds,
            });
            // 局部更新场次数据状态
            setSceneGroups((prev) =>
              prev.map((g) => ({
                ...g,
                items: g.items.map((i) => (i.id === updated.id ? updated : i)),
              }))
            );
            setEditAssetsOpen(false);
            setEditingItem(null);
          } catch (err) {
            console.error("更新关联资产失败:", err);
            alert("保存资产关联失败，请重试");
          }
        }}
      />

      <SceneStateTimelineDialog
        open={stateTimelineSceneId !== null}
        scene={stateTimelineSceneGroup?.scene || null}
        items={stateTimelineSceneGroup?.items || []}
        assetsList={assetsList}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) setStateTimelineSceneId(null);
        }}
        onSaved={() => {
          void refreshStoryboardData();
        }}
      />

      <ComposeConfigDialog
        open={composeConfigOpen}
        items={allItems}
        onConfirm={(segments) => {
          setComposeConfigOpen(false);
          void handleComposeEpisodeVideo(segments);
        }}
        onClose={() => setComposeConfigOpen(false)}
      />

      <VoiceoverPanelDialog
        open={voiceoverPanelOpen}
        onOpenChange={setVoiceoverPanelOpen}
        storyboardId={storyboard?.id ?? 0}
        episodeId={currentEpisodeId}
        sceneId={sidebarSelection.type === "scene" ? sidebarSelection.sceneId ?? null : null}
        episodeLabel={currentEpisode?.title?.trim()
          || (currentEpisode?.episodeNumber != null ? `第 ${currentEpisode.episodeNumber} 集` : undefined)}
        sceneLabel={sidebarSelection.type === "scene"
          ? (sceneGroups.find((g) => g.scene.id === sidebarSelection.sceneId)?.scene.sceneHeading?.trim()
            || (sceneGroups.find((g) => g.scene.id === sidebarSelection.sceneId)?.scene.sceneNumber?.trim()))
          : undefined}
        sceneNameMap={Object.fromEntries(
          sceneGroups.map((g) => [
            g.scene.id,
            g.scene.sceneHeading?.trim() || `场次${g.scene.sceneNumber ?? g.scene.id}`,
          ])
        )}
      />

      <VoiceConfigDialog
        open={voiceConfigOpen}
        onOpenChange={setVoiceConfigOpen}
        projectId={projectId}
      />
    </motion.div>
  );
}
