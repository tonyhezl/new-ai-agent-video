"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Scissors, Video, X } from "lucide-react";
import { resolveMediaUrl } from "@/lib/api/client";
import type { ComposeSegmentReq, StoryboardItem } from "@/lib/api/storyboard";

interface ComposeConfigDialogProps {
  open: boolean;
  items: StoryboardItem[];
  onConfirm: (segments: ComposeSegmentReq[]) => void;
  onClose: () => void;
}

interface SegmentState {
  selected: boolean;
  startSec: number;
  endSec: number;
  duration: number;
  durationLoaded: boolean;
  transition: string;
  transitionDuration: number;
}

export function ComposeConfigDialog({
  open,
  items,
  onConfirm,
  onClose,
}: ComposeConfigDialogProps) {
  const videoItems = useMemo(
    () =>
      items.filter(
        (item) => item.videoUrl || item.generatedVideoUrl
      ),
    [items]
  );

  const [states, setStates] = useState<Record<number, SegmentState>>({});
  const videoRefs = useRef<Record<number, HTMLVideoElement | null>>({});

  // 初始化/重置状态
  useEffect(() => {
    if (!open) return;
    const newStates: Record<number, SegmentState> = {};
    for (const item of videoItems) {
      newStates[item.id] = {
        selected: true,
        startSec: 0,
        endSec: 0,
        duration: 0,
        durationLoaded: false,
        transition: "none",
        transitionDuration: 0.5,
      };
    }
    const timer = window.setTimeout(() => setStates(newStates), 0);
    return () => window.clearTimeout(timer);
  }, [open, videoItems]);

  const handleLoadedMetadata = useCallback((itemId: number, video: HTMLVideoElement) => {
    const dur = video.duration;
    if (!isFinite(dur) || dur <= 0) return;
    setStates((prev) => {
      const s = prev[itemId];
      if (!s || s.durationLoaded) return prev;
      return {
        ...prev,
        [itemId]: {
          ...s,
          duration: dur,
          endSec: dur,
          durationLoaded: true,
        },
      };
    });
  }, []);

  const toggleSelect = (itemId: number) => {
    setStates((prev) => ({
      ...prev,
      [itemId]: {
        ...prev[itemId],
        selected: !prev[itemId]?.selected,
      },
    }));
  };

  const updateStart = (itemId: number, val: number) => {
    setStates((prev) => {
      const s = prev[itemId];
      if (!s) return prev;
      const clamped = Math.min(Math.max(0, val), s.duration);
      return {
        ...prev,
        [itemId]: {
          ...s,
          startSec: clamped,
          endSec: Math.max(s.endSec, clamped),
        },
      };
    });
  };

  const updateEnd = (itemId: number, val: number) => {
    setStates((prev) => {
      const s = prev[itemId];
      if (!s) return prev;
      const clamped = Math.min(Math.max(s.startSec, val), s.duration);
      return {
        ...prev,
        [itemId]: {
          ...s,
          endSec: clamped,
        },
      };
    });
  };

  const selectedItems = videoItems.filter((item) => states[item.id]?.selected);
  const allSelected = videoItems.length > 0 && selectedItems.length === videoItems.length;
  const totalDuration = selectedItems.reduce((sum, item) => {
    const s = states[item.id];
    if (!s || !s.durationLoaded) return sum;
    return sum + (s.endSec - s.startSec);
  }, 0);

  const toggleAll = () => {
    setStates((prev) => {
      const next = { ...prev };
      const target = !allSelected;
      for (const item of videoItems) {
        if (next[item.id]) {
          next[item.id] = { ...next[item.id], selected: target };
        }
      }
      return next;
    });
  };

  const updateTransition = (itemId: number, value: string) => {
    setStates((prev) => ({
      ...prev,
      [itemId]: { ...prev[itemId], transition: value },
    }));
  };

  const updateTransitionDuration = (itemId: number, value: number) => {
    setStates((prev) => ({
      ...prev,
      [itemId]: { ...prev[itemId], transitionDuration: value },
    }));
  };

  const handleConfirm = () => {
    const segments: ComposeSegmentReq[] = selectedItems.map((item, idx) => {
      const s = states[item.id];
      return {
        itemId: item.id,
        startSec: s.startSec > 0 ? s.startSec : null,
        endSec: s.durationLoaded && s.endSec < s.duration ? s.endSec : null,
        transition: idx > 0 ? s.transition : null,
        transitionDuration: idx > 0 ? s.transitionDuration : null,
      };
    });
    onConfirm(segments);
  };

  if (!open) return null;

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-sm"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-border/30 bg-card shadow-xl"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border/20 px-4 py-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold">
            <Video className="h-4 w-4 text-primary" />
            合成配置
          </h3>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-muted/30 hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              勾选要合成的视频，可拖动滑块裁剪每段视频的起止时间。
            </p>
            {videoItems.length > 0 && (
              <button
                onClick={toggleAll}
                className="text-xs font-medium text-primary hover:text-primary/80 transition-colors shrink-0"
              >
                {allSelected ? "取消全选" : "全选"}
              </button>
            )}
          </div>

          {videoItems.length === 0 && (
            <div className="py-8 text-center text-sm text-muted-foreground">
              本集没有可合成的视频
            </div>
          )}
          {videoItems.map((item, idx) => {
            const s = states[item.id];
            if (!s) return null;
            const videoUrl = resolveMediaUrl(item.videoUrl || item.generatedVideoUrl || "");
            // 计算在已选视频中的序号（用于判断是否第一段，第一段无转场）
            const selectedIdx = videoItems.filter((v, i) => i < idx && states[v.id]?.selected).length;
            const showTransition = s.selected && selectedIdx > 0;
            return (
              <div
                key={item.id}
                className={`rounded-lg border transition-colors ${
                  s.selected
                    ? "border-primary/30 bg-primary/5"
                    : "border-border/20 bg-muted/10"
                }`}
              >
                <div className="flex items-start gap-3 p-3">
                  {/* Checkbox */}
                  <button
                    onClick={() => toggleSelect(item.id)}
                    className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border transition-colors ${
                      s.selected
                        ? "border-primary bg-primary text-primary-foreground"
                        : "border-border/40 bg-background"
                    }`}
                  >
                    {s.selected && (
                      <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none">
                        <path d="M2 6L5 9L10 3" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    )}
                  </button>

                  {/* Video thumbnail + info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-xs font-medium text-muted-foreground">
                        #{idx + 1}
                      </span>
                      <span className="text-xs text-muted-foreground truncate">
                        {item.shotNumber || item.autoShotNumber || `镜头 ${item.id}`}
                      </span>
                      {s.durationLoaded && (
                        <span className="text-xs text-muted-foreground ml-auto">
                          时长 {s.duration.toFixed(1)}s
                        </span>
                      )}
                    </div>

                    {/* Video preview (small) */}
                    {videoUrl && (
                      <video
                        ref={(el) => { videoRefs.current[item.id] = el; }}
                        src={videoUrl}
                        className="w-full max-h-24 rounded-md bg-black mb-2"
                        onLoadedMetadata={(e) => handleLoadedMetadata(item.id, e.currentTarget)}
                        playsInline
                        muted
                        controls
                      />
                    )}

                    {/* Trim controls */}
                    {s.selected && s.durationLoaded && (
                      <div className="space-y-1.5">
                        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                          <Scissors className="h-3 w-3" />
                          裁剪区间
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-muted-foreground w-8">起</span>
                          <input
                            type="range"
                            min={0}
                            max={s.duration}
                            step={0.1}
                            value={s.startSec}
                            onChange={(e) => updateStart(item.id, parseFloat(e.target.value))}
                            className="flex-1 accent-primary"
                          />
                          <span className="text-xs tabular-nums w-10 text-right">
                            {s.startSec.toFixed(1)}s
                          </span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-muted-foreground w-8">止</span>
                          <input
                            type="range"
                            min={0}
                            max={s.duration}
                            step={0.1}
                            value={s.endSec}
                            onChange={(e) => updateEnd(item.id, parseFloat(e.target.value))}
                            className="flex-1 accent-primary"
                          />
                          <span className="text-xs tabular-nums w-10 text-right">
                            {s.endSec.toFixed(1)}s
                          </span>
                        </div>
                        <div className="text-xs text-muted-foreground">
                          裁剪后时长：{(s.endSec - s.startSec).toFixed(1)}s
                        </div>
                      </div>
                    )}

                    {/* 逐段转场选择（仅第2段及之后显示） */}
                    {showTransition && (
                      <div className="mt-2 pt-2 border-t border-border/20 space-y-1.5">
                        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                          <Video className="h-3 w-3" />
                          衔接方式（与上一段）
                        </div>
                        <div className="flex items-center gap-2">
                          <select
                            value={s.transition}
                            onChange={(e) => updateTransition(item.id, e.target.value)}
                            className="flex-1 rounded-md border border-border/30 bg-background px-2 py-1 text-xs focus:outline-none focus:ring-1 focus:ring-primary"
                          >
                            <option value="none">无转场（硬切）</option>
                            <option value="jcut">J-Cut（音频先行）</option>
                            <option value="lcut">L-Cut（音频拖尾）</option>
                            <option value="fade">淡入淡出</option>
                            <option value="dissolve">溶解</option>
                            <option value="wipeleft">向左擦除</option>
                            <option value="wiperight">向右擦除</option>
                            <option value="wipeup">向上擦除</option>
                            <option value="wipedown">向下擦除</option>
                            <option value="slideup">向上滑动</option>
                            <option value="slidedown">向下滑动</option>
                            <option value="slideleft">向左滑动</option>
                            <option value="slideright">向右滑动</option>
                            <option value="circleopen">圆形展开</option>
                            <option value="circleclose">圆形收缩</option>
                            <option value="smoothleft">平滑左擦</option>
                            <option value="smoothright">平滑右擦</option>
                            <option value="smoothup">平滑上擦</option>
                            <option value="smoothdown">平滑下擦</option>
                            <option value="fadeblack">淡入黑场</option>
                            <option value="fadewhite">淡入白场</option>
                            <option value="zoomin">放大过渡</option>
                          </select>
                          {s.transition !== "none" && (
                            <>
                              <input
                                type="number"
                                min={0.1}
                                max={3}
                                step={0.1}
                                value={s.transitionDuration}
                                onChange={(e) => updateTransitionDuration(item.id, Math.max(0.1, Math.min(3, parseFloat(e.target.value) || 0.5)))}
                                className="w-14 rounded-md border border-border/30 bg-background px-2 py-1 text-xs tabular-nums focus:outline-none focus:ring-1 focus:ring-primary"
                              />
                              <span className="text-xs text-muted-foreground shrink-0">秒</span>
                            </>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-border/20 px-4 py-3">
          <div className="text-xs text-muted-foreground">
            已选 {selectedItems.length} 段
            {totalDuration > 0 && ` · 总时长 ${totalDuration.toFixed(1)}s`}
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={onClose}
              className="rounded-lg border border-border/30 bg-muted/20 px-4 py-1.5 text-xs font-medium transition-colors hover:bg-muted/40"
            >
              取消
            </button>
            <button
              onClick={handleConfirm}
              disabled={selectedItems.length === 0}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <Video className="h-3.5 w-3.5" />
              开始合成
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
