"use client";

import { useState, useEffect, useCallback } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Loader2, Mic, PlayCircle, RefreshCw, AlertCircle, CheckCircle2, Volume2 } from "lucide-react";
import { toast } from "sonner";
import {
  storyboardApi,
  type ItemDialoguePreview,
  type AudioTask,
} from "@/lib/api/storyboard";
import { resolveMediaUrl } from "@/lib/api/client";

interface VoiceoverPanelDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  storyboardId: number;
  episodeId: number | null;
  sceneId?: number | null;
  episodeLabel?: string;
  sceneLabel?: string;
  /** 场次ID -> 场次显示名称映射（用于区分不同场次中相同的镜头号） */
  sceneNameMap?: Record<number, string>;
}

const AUDIO_STATUS_MAP: Record<number, { label: string; icon: typeof CheckCircle2; className: string }> = {
  0: { label: "未生成", icon: Mic, className: "text-muted-foreground" },
  1: { label: "生成中", icon: Loader2, className: "text-blue-500" },
  2: { label: "已完成", icon: CheckCircle2, className: "text-emerald-500" },
  3: { label: "失败", icon: AlertCircle, className: "text-red-500" },
};

export function VoiceoverPanelDialog({
  open,
  onOpenChange,
  storyboardId,
  episodeId,
  sceneId,
  episodeLabel,
  sceneLabel,
  sceneNameMap,
}: VoiceoverPanelDialogProps) {
  const [previews, setPreviews] = useState<ItemDialoguePreview[]>([]);
  const [tasks, setTasks] = useState<AudioTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [regeneratingId, setRegeneratingId] = useState<number | null>(null);
  const [playingUrl, setPlayingUrl] = useState<string | null>(null);
  const [selectedItemIds, setSelectedItemIds] = useState<Set<number>>(new Set());

  const loadPreview = useCallback(async () => {
    if (!episodeId) return;
    setLoading(true);
    try {
      const list = await storyboardApi.previewDialogue(storyboardId, episodeId, sceneId ?? undefined);
      setPreviews(list);
      setSelectedItemIds(new Set(list.map((p) => p.itemId)));
    } catch (err) {
      console.error("加载对白预览失败:", err);
    } finally {
      setLoading(false);
    }
  }, [storyboardId, episodeId, sceneId]);

  const loadTasks = useCallback(async () => {
    if (!episodeId) return;
    try {
      const list = await storyboardApi.listVoiceover(episodeId);
      setTasks(list);
    } catch (err) {
      console.error("加载配音列表失败:", err);
    }
  }, [episodeId]);

  useEffect(() => {
    if (open && episodeId) {
      loadPreview();
      loadTasks();
    }
  }, [open, episodeId, loadPreview, loadTasks]);

  useEffect(() => {
    if (!open || !tasks.length) return;
    const hasPending = tasks.some((t) => t.status === 0 || t.status === 1);
    if (!hasPending) return;
    const timer = setInterval(() => {
      loadTasks();
    }, 5000);
    return () => clearInterval(timer);
  }, [open, tasks, loadTasks]);

  const handleGenerate = async () => {
    if (!episodeId) return;
    const ids = Array.from(selectedItemIds);
    if (ids.length === 0) {
      toast.error("请至少选择一个镜头");
      return;
    }
    setGenerating(true);
    try {
      const msg = await storyboardApi.generateVoiceover(storyboardId, episodeId, ids);
      toast.success(msg);
      await loadTasks();
    } catch (err) {
      toast.error("生成失败: " + (err as Error).message);
    } finally {
      setGenerating(false);
    }
  };

  const handleRegenerate = async (taskId: number) => {
    setRegeneratingId(taskId);
    try {
      await storyboardApi.regenerateVoiceover(taskId);
      toast.success("已提交重生成");
      await loadTasks();
    } catch (err) {
      toast.error("重生成失败: " + (err as Error).message);
    } finally {
      setRegeneratingId(null);
    }
  };

  const toggleItem = (itemId: number) => {
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) {
        next.delete(itemId);
      } else {
        next.add(itemId);
      }
      return next;
    });
  };

  const toggleAll = () => {
    if (selectedItemIds.size === previews.length) {
      setSelectedItemIds(new Set());
    } else {
      setSelectedItemIds(new Set(previews.map((p) => p.itemId)));
    }
  };

  // 按 (itemId, seq) 分组，只保留 id 最大的（最新的）任务，忽略被重生成覆盖的旧任务
  const latestTaskByKey = tasks.reduce<Record<string, AudioTask>>((acc, t) => {
    const key = `${t.storyboardItemId}:${t.seq}`;
    if (!acc[key] || t.id > acc[key].id) {
      acc[key] = t;
    }
    return acc;
  }, {});

  const tasksByItemId = Object.values(latestTaskByKey).reduce<Record<number, AudioTask[]>>((acc, t) => {
    if (!acc[t.storyboardItemId]) acc[t.storyboardItemId] = [];
    acc[t.storyboardItemId].push(t);
    return acc;
  }, {});

  const allSelected = previews.length > 0 && selectedItemIds.size === previews.length;
  const selectedCount = selectedItemIds.size;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Mic className="h-4 w-4" />
            配音管理
            {episodeLabel ? ` - ${episodeLabel}` : ""}
            {sceneLabel ? ` / ${sceneLabel}` : ""}
          </DialogTitle>
          <DialogDescription>
            勾选要生成配音的镜头，点击&quot;生成选中&quot;提交 TTS 任务。
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center justify-between gap-2 mb-2">
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-1.5 cursor-pointer text-xs">
              <Checkbox
                checked={allSelected}
                onCheckedChange={toggleAll}
              />
              全选
            </label>
            <span className="text-xs text-muted-foreground">
              已选 {selectedCount} / {previews.length} 个镜头
            </span>
          </div>
          <Button
            size="sm"
            onClick={handleGenerate}
            disabled={generating || selectedCount === 0 || !episodeId}
          >
            {generating ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                提交中…
              </>
            ) : (
              <>
                <Mic className="h-3.5 w-3.5" />
                生成选中
              </>
            )}
          </Button>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : previews.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
            <Mic className="h-8 w-8 mb-2 opacity-50" />
            <p className="text-sm">该集暂无包含对白的镜头</p>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {previews.map((item) => {
              const isSelected = selectedItemIds.has(item.itemId);
              const itemTasks = tasksByItemId[item.itemId] ?? [];
              const audioSt = AUDIO_STATUS_MAP[item.audioStatus] ?? AUDIO_STATUS_MAP[0];
              const AudioIcon = audioSt.icon;
              const hasAudioUrl = !!item.audioUrl;

              return (
                <div
                  key={item.itemId}
                  className={`rounded-lg border p-3 transition-colors ${
                    isSelected
                      ? "border-purple-500/30 bg-purple-500/5"
                      : "border-border/30 bg-muted/10"
                  }`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    <Checkbox
                      checked={isSelected}
                      onCheckedChange={() => toggleItem(item.itemId)}
                    />
                    <span className="text-xs font-medium">
                      {item.sceneId && sceneNameMap?.[item.sceneId]
                        ? `${sceneNameMap[item.sceneId]} · 镜头 ${item.shotNumber}`
                        : `镜头 ${item.shotNumber}`}
                    </span>
                    <span className="text-[10px] text-muted-foreground">
                      #{item.itemId}
                    </span>
                    <div className="flex items-center gap-1">
                      <AudioIcon
                        className={`h-3 w-3 ${audioSt.className} ${
                          item.audioStatus === 1 ? "animate-spin" : ""
                        }`}
                      />
                      <span className={`text-xs ${audioSt.className}`}>
                        {audioSt.label}
                      </span>
                    </div>
                    {hasAudioUrl && (
                      <button
                        onClick={() => {
                          const url = resolveMediaUrl(item.audioUrl);
                          if (url) setPlayingUrl(url);
                        }}
                        className="flex items-center gap-1 px-1.5 py-0.5 rounded text-xs text-primary hover:bg-primary/10 transition-colors"
                        title="播放镜头音频"
                      >
                        <Volume2 className="h-3 w-3" />
                        播放
                      </button>
                    )}
                    <span className="text-xs text-muted-foreground ml-auto">
                      {item.lines.length} 句台词
                    </span>
                  </div>

                  <div className="flex flex-col gap-1.5 ml-6">
                    {item.lines.map((line) => {
                      const lineTask = itemTasks.find((t) => t.seq === line.seq);
                      const lineSt = lineTask
                        ? AUDIO_STATUS_MAP[lineTask.status] ?? AUDIO_STATUS_MAP[0]
                        : null;

                      return (
                        <div
                          key={line.seq}
                          className="flex items-start gap-2 rounded-md bg-background/50 p-2"
                        >
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <span className="text-xs font-medium">
                                {line.characterName || "旁白"}
                              </span>
                              {line.dialogueType && (
                                <span className="text-[10px] px-1 py-0.5 rounded bg-muted/30 text-muted-foreground">
                                  {line.dialogueType === "narration" ? "旁白" : "台词"}
                                </span>
                              )}
                              {line.hasVoiceConfig ? (
                                <span className="text-[10px] text-emerald-500">
                                  ✓ 已配音色
                                </span>
                              ) : (
                                <span className="text-[10px] text-amber-500">
                                  ⚠ 未配音色
                                </span>
                              )}
                              {lineSt && (
                                <lineSt.icon
                                  className={`h-3 w-3 ${lineSt.className} ${
                                    lineTask?.status === 1 ? "animate-spin" : ""
                                  }`}
                                />
                              )}
                            </div>
                            <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                              {line.text}
                            </p>
                            {lineTask?.status === 3 && lineTask.errorMessage && (
                              <p className="text-xs text-red-500 mt-0.5">
                                {lineTask.errorMessage}
                              </p>
                            )}
                          </div>

                          <div className="flex items-center gap-1 shrink-0">
                            {lineTask?.audioUrl && (
                              <button
                                onClick={() => {
                                const url = resolveMediaUrl(lineTask.audioUrl);
                                if (url) setPlayingUrl(url);
                              }}
                                className="p-1 rounded hover:bg-muted/40 transition-colors"
                                title="播放"
                              >
                                <PlayCircle className="h-4 w-4 text-primary" />
                              </button>
                            )}
                            {lineTask && (
                              <button
                                onClick={() => handleRegenerate(lineTask.id)}
                                disabled={regeneratingId === lineTask.id}
                                className="p-1 rounded hover:bg-muted/40 transition-colors disabled:opacity-50"
                                title="重生成"
                              >
                                {regeneratingId === lineTask.id ? (
                                  <Loader2 className="h-4 w-4 animate-spin" />
                                ) : (
                                  <RefreshCw className="h-3.5 w-3.5 text-muted-foreground" />
                                )}
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>

      {playingUrl && (
        <div
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80"
          onClick={() => setPlayingUrl(null)}
        >
          <audio
            src={playingUrl}
            controls
            autoPlay
            className="max-w-[90%]"
            onEnded={() => setPlayingUrl(null)}
          />
        </div>
      )}
    </Dialog>
  );
}
