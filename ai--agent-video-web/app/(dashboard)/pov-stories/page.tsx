"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import {
  Eye,
  Plus,
  Play,
  Film,
  Sparkles,
  Clock,
  Trash2,
  AlertCircle,
  CheckCircle2,
  Loader2,
  Volume2,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { povStoryApi, type PovStory } from "@/lib/api/pov-story";
import { CreatePovStoryDialog } from "@/components/dashboard/pov/create-pov-story-dialog";
import { toast } from "sonner";

const GENRE_MAP: Record<string, { label: string; color: string }> = {
  URBAN_MYSTERY: {
    label: "都市怪谈",
    color: "bg-purple-500/15 text-purple-400 border-purple-500/30",
  },
  DEEP_EXPLORATION: {
    label: "废墟探险",
    color: "bg-amber-500/15 text-amber-400 border-amber-500/30",
  },
  HAUNTED_HOUSE_TESTER: {
    label: "凶宅试睡",
    color: "bg-red-500/15 text-red-400 border-red-500/30",
  },
  OCCUPATIONAL_TABOO: {
    label: "特殊职业",
    color: "bg-emerald-500/15 text-emerald-400 border-emerald-500/30",
  },
  TECH_PARANOIA: {
    label: "科技惊悚",
    color: "bg-indigo-500/15 text-indigo-400 border-indigo-500/30",
  },
  UNEXPLORED_ZONE: {
    label: "诡异禁区",
    color: "bg-blue-500/15 text-blue-400 border-blue-500/30",
  },
  ANOMALOUS_SPACE: {
    label: "曼德拉异象",
    color: "bg-pink-500/15 text-pink-400 border-pink-500/30",
  },
  FOLKLORE_SECRETS: {
    label: "民间秘辛",
    color: "bg-yellow-500/15 text-yellow-400 border-yellow-500/30",
  },
};

export default function PovStoriesPage() {
  const router = useRouter();
  const [stories, setStories] = useState<PovStory[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeGenre, setActiveGenre] = useState<string>("ALL");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [playingVideoUrl, setPlayingVideoUrl] = useState<string | null>(null);

  const fetchStories = useCallback(async () => {
    setLoading(true);
    try {
      const data = await povStoryApi.page({
        pageNo: 1,
        pageSize: 50,
        topicGenre: activeGenre === "ALL" ? undefined : activeGenre,
      });
      setStories(data?.list || []);
    } catch (err: unknown) {
      toast.error("加载故事列表失败: " + (err instanceof Error ? err.message : "未知错误"));
    } finally {
      setLoading(false);
    }
  }, [activeGenre]);

  useEffect(() => {
    fetchStories();
  }, [fetchStories]);

  const handleDelete = async (e: React.MouseEvent, storyId: number) => {
    e.stopPropagation();
    if (!confirm("确定要删除这个故事及其所有分镜吗？")) return;

    try {
      await povStoryApi.delete(storyId);
      toast.success("删除成功");
      fetchStories();
    } catch (err: unknown) {
      toast.error("删除失败: " + (err instanceof Error ? err.message : "未知错误"));
    }
  };

  return (
    <div className="flex flex-col h-full space-y-6 p-6 max-w-7xl mx-auto">
      {/* 顶部标题与行动条 */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-border/40 pb-5">
        <div className="space-y-1">
          <h1 className="text-2xl font-bold tracking-tight text-foreground flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-400">
              <Eye className="w-5 h-5" />
            </div>
            第一人称猎奇故事
            <span className="text-xs px-2.5 py-0.5 rounded-full bg-amber-500/15 text-amber-400 border border-amber-500/30 font-medium">
              POV 爆款引擎
            </span>
          </h1>
          <p className="text-xs text-muted-foreground">
            主观窥探视角 • 60秒心理悬疑 • 彻底摆脱角色人脸一致性束缚 • 极高完播率
          </p>
        </div>

        <button
          onClick={() => setDialogOpen(true)}
          className="flex items-center gap-2 px-5 py-2.5 text-xs font-semibold text-black bg-gradient-to-r from-amber-400 to-orange-400 hover:from-amber-300 hover:to-orange-300 rounded-xl shadow-lg shadow-amber-500/20 transition-all hover:scale-[1.02] active:scale-[0.98]"
        >
          <Plus className="w-4 h-4" />
          创作新猎奇故事
        </button>
      </div>

      {/* 题材过滤器 */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1">
        {[
          { key: "ALL", label: "全部题材" },
          { key: "URBAN_MYSTERY", label: "都市怪谈" },
          { key: "DEEP_EXPLORATION", label: "废墟探险" },
          { key: "HAUNTED_HOUSE_TESTER", label: "凶宅试睡" },
          { key: "OCCUPATIONAL_TABOO", label: "特殊职业" },
          { key: "TECH_PARANOIA", label: "科技惊悚" },
          { key: "UNEXPLORED_ZONE", label: "诡异禁区" },
          { key: "ANOMALOUS_SPACE", label: "曼德拉异象" },
          { key: "FOLKLORE_SECRETS", label: "民间秘辛" },
        ].map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveGenre(tab.key)}
            className={cn(
              "px-3.5 py-1.5 rounded-lg text-xs font-medium transition-all",
              activeGenre === tab.key
                ? "bg-amber-400 text-black shadow-sm font-semibold"
                : "bg-muted/40 text-muted-foreground hover:text-foreground hover:bg-muted/70 border border-border/40"
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* 故事卡片网格 */}
      {loading ? (
        <div className="flex flex-col items-center justify-center h-64 space-y-3 text-muted-foreground">
          <Loader2 className="w-6 h-6 animate-spin text-amber-400" />
          <p className="text-xs">加载第一人称故事库...</p>
        </div>
      ) : stories.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-80 rounded-2xl border border-dashed border-border/60 bg-muted/10 p-8 text-center space-y-4">
          <div className="p-4 rounded-2xl bg-amber-500/10 text-amber-400 border border-amber-500/20">
            <Eye className="w-8 h-8" />
          </div>
          <div className="space-y-1">
            <h3 className="text-base font-semibold text-foreground">
              暂无猎奇故事
            </h3>
            <p className="text-xs text-muted-foreground max-w-sm">
              点击下方按钮，输入一个反常反常的灵感（如老宅暗格、深夜猫眼），让
              AI 为你全自动生成主观视角的 50 秒爆款短视频。
            </p>
          </div>
          <button
            onClick={() => setDialogOpen(true)}
            className="flex items-center gap-2 px-5 py-2 text-xs font-medium text-black bg-amber-400 hover:bg-amber-300 rounded-xl transition-all"
          >
            <Sparkles className="w-3.5 h-3.5" />
            立即创建第一个故事
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {stories.map((story) => {
            const genreInfo = GENRE_MAP[story.topicGenre] || {
              label: story.topicGenre,
              color: "bg-muted text-muted-foreground",
            };

            return (
              <motion.div
                key={story.id}
                onClick={() => router.push(`/pov-stories/${story.id}`)}
                className="group relative flex flex-col justify-between p-5 rounded-2xl bg-card border border-border/60 hover:border-amber-500/50 hover:shadow-xl hover:shadow-amber-500/5 transition-all cursor-pointer overflow-hidden"
              >
                <div className="space-y-3">
                  {/* 顶部元信息 */}
                  <div className="flex items-center justify-between gap-2">
                    <span
                      className={cn(
                        "text-[11px] px-2.5 py-0.5 rounded-md border font-medium",
                        genreInfo.color
                      )}
                    >
                      {genreInfo.label}
                    </span>
                    <span className="text-[11px] text-muted-foreground flex items-center gap-1">
                      <Clock className="w-3 h-3" />
                      约 {story.totalDurationEstimate || 50} 秒
                    </span>
                  </div>

                  {/* 标题 */}
                  <h3 className="text-base font-bold text-foreground group-hover:text-amber-400 transition-colors line-clamp-1">
                    {story.title}
                  </h3>

                  {/* 黄金钩子文案 */}
                  {story.hookText && (
                    <div className="p-2.5 rounded-xl bg-amber-500/5 border border-amber-500/20 text-xs text-amber-300/90 leading-relaxed line-clamp-2">
                      <span className="font-semibold text-amber-400">
                        [黄金钩子]{" "}
                      </span>
                      {story.hookText}
                    </div>
                  )}

                  {/* 独白剧情简略 */}
                  <p className="text-xs text-muted-foreground line-clamp-2 leading-relaxed">
                    {story.fullScript}
                  </p>
                </div>

                {/* 底部操作与状态 */}
                <div className="flex items-center justify-between pt-4 mt-4 border-t border-border/40 text-xs">
                  <div className="flex items-center gap-2">
                    {story.status === "COMPLETED" ? (
                      <span className="flex items-center gap-1 text-emerald-400 text-[11px]">
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        视频已合成
                      </span>
                    ) : story.status === "GENERATING" ? (
                      <span className="flex items-center gap-1 text-amber-400 text-[11px] animate-pulse">
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        生成中
                      </span>
                    ) : (
                      <span className="text-muted-foreground text-[11px]">
                        分镜草稿
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-2">
                    {story.finalVideoUrl && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setPlayingVideoUrl(story.finalVideoUrl || null);
                        }}
                        className="p-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 transition-colors"
                        title="播放完整合成视频"
                      >
                        <Play className="w-3.5 h-3.5 fill-emerald-400" />
                      </button>
                    )}
                    <button
                      onClick={(e) => handleDelete(e, story.id)}
                      className="p-1.5 rounded-lg text-muted-foreground hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                      title="删除故事"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      )}

      {/* 创作弹窗 */}
      <CreatePovStoryDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={() => {
          setDialogOpen(false);
          fetchStories();
        }}
      />

      {/* 视频全屏预览弹窗 */}
      {playingVideoUrl && (
        <div
          onClick={() => setPlayingVideoUrl(null)}
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="relative w-full max-w-sm rounded-2xl overflow-hidden bg-black border border-border/40 shadow-2xl"
          >
            <video
              src={playingVideoUrl}
              controls
              autoPlay
              className="w-full h-auto aspect-[9/16] object-contain"
            />
          </div>
        </div>
      )}
    </div>
  );
}
