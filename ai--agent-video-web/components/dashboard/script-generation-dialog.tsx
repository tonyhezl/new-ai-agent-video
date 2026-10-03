"use client";

import { useState } from "react";
import { X, Sparkles, Loader2, Wand2 } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { scriptApi } from "@/lib/api/script";
import { usePipelineStore } from "@/lib/store/pipeline-store";

interface ScriptGenerationDialogProps {
  open: boolean;
  projectId: number;
  projectName?: string;
  onClose: () => void;
  onCreated: () => void;
}

const genres = ["都市", "古装", "科幻", "悬疑", "玄幻", "历史", "爱情", "热血"];
const styles = ["轻松", "热血", "虐心", "搞笑", "悬疑", "治愈", "爽文", "催泪"];
const types = ["漫剧", "短剧", "动画", "纪录片", "宣传片", "MV"];
const episodeOptions = [1, 3, 5, 10, 20];

interface VisualStylePreset {
  label: string;
  description: string;
}
const visualStyles: VisualStylePreset[] = [
  { label: "写实3D CG", description: "写实向3D CG渲染，电影级光影，衣料与木石材质真实，史诗空镜构图，无卡通无二次元线稿" },
  { label: "古风仙侠", description: "写实向3D CG渲染，电影级光影，古风仙侠建筑，衣料与木石材质真实，史诗空镜构图，无卡通无二次元线稿" },
  { label: "二次元动漫", description: "日系二次元动漫画风，赛璐璐渲染，鲜艳色彩，夸张表情，流畅线条，轻快光影" },
  { label: "水墨国风", description: "水墨国风渲染，写意笔触，淡彩留白，中国传统美学，意境悠远" },
  { label: "赛博朋克", description: "赛博朋克风格，霓虹光影，未来都市，高对比度色彩，金属与玻璃质感" },
  { label: "真人写实", description: "真人写实摄影风格，自然光影，细腻材质与织物纹理，浅景深虚化，电影镜头质感" },
];

export function ScriptGenerationDialog({
  open,
  projectId,
  projectName,
  onClose,
  onCreated,
}: ScriptGenerationDialogProps) {
  const [description, setDescription] = useState("");
  const [genre, setGenre] = useState(genres[0]);
  const [style, setStyle] = useState(styles[0]);
  const [type, setType] = useState(types[0]);
  const [visualStyle, setVisualStyle] = useState(visualStyles[0].label);
  const [episodeCount, setEpisodeCount] = useState(3);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const addPipeline = usePipelineStore((s) => s.addPipeline);
  const setPanelExpanded = usePipelineStore((s) => s.setPanelExpanded);
  const setExpandedTaskId = usePipelineStore((s) => s.setExpandedTaskId);

  const handleSubmit = async () => {
    // 标题由 AI 根据创意内容自动生成（script_full_parse 阶段的 update_script_info 会覆盖），
    // 此处使用固定占位符，避免项目名干扰 AI 标题提取
    const resolvedTitle = "待AI生成";

    if (!description.trim()) {
      setError("请输入创意描述");
      return;
    }

    setLoading(true);
    setError("");
    try {
      const script = await scriptApi.create({
        projectId,
        title: resolvedTitle,
      });

      // 脚本创建成功后立即让页面从 EmptyState 切换到剧本视图，
      // 后续创作与解析在后台 Pipeline 中进行，通过 invalidation 机制自动刷新数据
      onCreated();

      const writerPipelineId = addPipeline({
        label: `AI 创作剧本 - ${projectName?.trim() || "未命名"}`,
        projectId,
        request: {
          agentType: "script_writer",
          category: "pipeline",
          title: `AI 剧本创作：${projectName?.trim() || "未命名"}`,
          projectId,
          context: {
            scriptId: script.id,
            genre,
            style,
            type,
            visualStyle: visualStyles.find((v) => v.label === visualStyle)?.description || visualStyle,
            episodeCount,
            description: description.trim(),
          },
        },
        onComplete: () => {
          // 创作完成后自动启动解析
          const parsePipelineId = addPipeline({
            label: `AI 解析剧本 - ${projectName?.trim() || "未命名"}`,
            projectId,
            request: {
              agentType: "script_full_parse",
              category: "pipeline",
              title: `AI 剧本解析：${projectName?.trim() || "未命名"}`,
              projectId,
              context: { scriptId: script.id },
            },
          });
          setExpandedTaskId(parsePipelineId);
        },
      });

      setPanelExpanded(true);
      setExpandedTaskId(writerPipelineId);

      // 清理并关闭对话框
      setDescription("");
      setEpisodeCount(3);
      setVisualStyle(visualStyles[0].label);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  const toggleChip = (value: string, current: string, setter: (v: string) => void, list: string[]) => {
    if (list.includes(value)) {
      setter(value);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm"
            onClick={handleClose}
          />
          <motion.div
            initial={{ opacity: 0, scale: 0.95, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 20 }}
            transition={{ duration: 0.2 }}
            className="fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-full max-w-xl"
          >
            <div className="rounded-2xl border border-border/40 p-6 bg-card shadow-2xl shadow-black/20 max-h-[85vh] overflow-y-auto">
              <div className="flex items-center justify-between mb-5">
                <div className="flex items-center gap-2">
                  <Wand2 className="h-5 w-5 text-purple-400" />
                  <h2 className="text-lg font-semibold">AI 创作剧本</h2>
                </div>
                <button
                  onClick={handleClose}
                  className="p-1.5 rounded-lg hover:bg-muted transition-colors"
                >
                  <X className="h-4 w-4 text-muted-foreground" />
                </button>
              </div>

              <div className="space-y-5">
                {/* 创意描述 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">
                    创意描述 <span className="text-destructive">*</span>
                  </label>
                  <textarea
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="一句话描述你的故事创意，例如：一个现代程序员穿越到古代，用编程思维破解宫廷谜案..."
                    rows={4}
                    className={cn(
                      "w-full px-3.5 py-2.5 rounded-xl text-sm resize-none",
                      "bg-muted/50 border border-border/40",
                      "focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/50",
                      "placeholder:text-muted-foreground/50 transition-all"
                    )}
                    disabled={loading}
                  />
                </div>

                {/* 题材 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">题材</label>
                  <div className="flex flex-wrap gap-2">
                    {genres.map((g) => (
                      <button
                        key={g}
                        onClick={() => toggleChip(g, genre, setGenre, genres)}
                        type="button"
                        className={cn(
                          "px-3 py-1.5 rounded-full text-xs font-medium transition-all",
                          genre === g
                            ? "bg-primary text-primary-foreground"
                            : "bg-muted/50 border border-border/40 hover:bg-muted text-muted-foreground"
                        )}
                        disabled={loading}
                      >
                        {g}
                      </button>
                    ))}
                  </div>
                </div>

                {/* 风格 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">风格</label>
                  <div className="flex flex-wrap gap-2">
                    {styles.map((s) => (
                      <button
                        key={s}
                        onClick={() => toggleChip(s, style, setStyle, styles)}
                        type="button"
                        className={cn(
                          "px-3 py-1.5 rounded-full text-xs font-medium transition-all",
                          style === s
                            ? "bg-primary text-primary-foreground"
                            : "bg-muted/50 border border-border/40 hover:bg-muted text-muted-foreground"
                        )}
                        disabled={loading}
                      >
                        {s}
                      </button>
                    ))}
                  </div>
                </div>

                {/* 类型 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">剧本类型</label>
                  <div className="flex flex-wrap gap-2">
                    {types.map((t) => (
                      <button
                        key={t}
                        onClick={() => toggleChip(t, type, setType, types)}
                        type="button"
                        className={cn(
                          "px-3 py-1.5 rounded-full text-xs font-medium transition-all",
                          type === t
                            ? "bg-primary text-primary-foreground"
                            : "bg-muted/50 border border-border/40 hover:bg-muted text-muted-foreground"
                        )}
                        disabled={loading}
                      >
                        {t}
                      </button>
                    ))}
                  </div>
                </div>

                {/* 画风 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">画风</label>
                  <div className="flex flex-wrap gap-2">
                    {visualStyles.map((vs) => (
                      <button
                        key={vs.label}
                        onClick={() => setVisualStyle(vs.label)}
                        type="button"
                        className={cn(
                          "px-3 py-1.5 rounded-full text-xs font-medium transition-all",
                          visualStyle === vs.label
                            ? "bg-primary text-primary-foreground"
                            : "bg-muted/50 border border-border/40 hover:bg-muted text-muted-foreground"
                        )}
                        disabled={loading}
                        title={vs.description}
                      >
                        {vs.label}
                      </button>
                    ))}
                  </div>
                  {visualStyles.find((v) => v.label === visualStyle) && (
                    <p className="text-[11px] text-muted-foreground/70 mt-1.5 leading-relaxed">
                      {visualStyles.find((v) => v.label === visualStyle)!.description}
                    </p>
                  )}
                </div>

                {/* 集数 */}
                <div>
                  <label className="block text-sm font-medium mb-1.5">集数</label>
                  <div className="flex flex-wrap gap-2">
                    {episodeOptions.map((ep) => (
                      <button
                        key={ep}
                        onClick={() => setEpisodeCount(ep)}
                        type="button"
                        className={cn(
                          "px-3 py-1.5 rounded-full text-xs font-medium transition-all",
                          episodeCount === ep
                            ? "bg-primary text-primary-foreground"
                            : "bg-muted/50 border border-border/40 hover:bg-muted text-muted-foreground"
                        )}
                        disabled={loading}
                      >
                        {ep} 集
                      </button>
                    ))}
                    <input
                      type="number"
                      min={1}
                      max={50}
                      value={episodeCount}
                      onChange={(e) => setEpisodeCount(Math.max(1, Math.min(50, Number(e.target.value) || 1)))}
                      className={cn(
                        "w-20 px-3 py-1.5 rounded-full text-xs font-medium text-center",
                        "bg-muted/50 border border-border/40",
                        "focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/50"
                      )}
                      disabled={loading}
                    />
                  </div>
                </div>

                {error && <p className="text-sm text-destructive">{error}</p>}
              </div>

              <div className="flex justify-end gap-3 mt-6">
                <button
                  onClick={handleClose}
                  disabled={loading}
                  className="px-4 py-2 rounded-xl text-sm font-medium hover:bg-muted transition-colors disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={loading}
                  className={cn(
                    "flex items-center gap-2 px-5 py-2 rounded-xl text-sm font-medium",
                    "bg-linear-to-r from-purple-600 to-pink-600",
                    "text-white shadow-lg shadow-purple-500/20",
                    "hover:shadow-purple-500/30 active:scale-[0.98] transition-all",
                    "disabled:opacity-50 disabled:cursor-not-allowed"
                  )}
                >
                  {loading ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin" />
                      创建中...
                    </>
                  ) : (
                    <>
                      <Sparkles className="h-4 w-4" />
                      开始创作
                    </>
                  )}
                </button>
              </div>
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
