"use client";

import { useState, useEffect, useCallback, useMemo, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  Sparkles,
  Loader2,
  Wand2,
  ChevronDown,
  Gift,
  LogIn,
  Download,
  Check,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { useAuthStore } from "@/lib/store/auth-store";
import {
  getQuota,
  getModels,
  submitTask,
  getTask,
  getTaskResults,
  getHistory,
  type QuotaInfo,
  type ModelInfo,
  type HistoryItem,
} from "@/lib/api/image-generation";
import { resolveMediaUrl } from "@/lib/api/client";
import { RechargeDialog } from "@/components/workbench/recharge-dialog";

const DEFAULT_RESOLUTION_LEVELS = [
  { key: "1K", label: "1K" },
  { key: "2K", label: "2K" },
  { key: "3K", label: "3K" },
  { key: "4K", label: "4K" },
];

const DEFAULT_ASPECT_RATIOS = [
  { key: "1:1", label: "1:1" },
  { key: "16:9", label: "16:9" },
  { key: "9:16", label: "9:16" },
  { key: "4:3", label: "4:3" },
  { key: "3:4", label: "3:4" },
];

interface GeneratedImage {
  taskId: number;
  url: string;
  prompt: string;
  modelId: number;
  modelName: string;
  timestamp: number;
}

export default function WorkbenchPage() {
  const router = useRouter();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const authHydrated = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false
  );

  const [quota, setQuota] = useState<QuotaInfo | null>(null);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [selectedModel, setSelectedModel] = useState<ModelInfo | null>(null);
  const [qualityLevel, setQualityLevel] = useState("1K");
  const [aspectRatio, setAspectRatio] = useState("1:1");
  const [prompt, setPrompt] = useState("");
  const [negativePrompt, setNegativePrompt] = useState("");
  const [showNegative, setShowNegative] = useState(false);
  const [modelDropdownOpen, setModelDropdownOpen] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [progress, setProgress] = useState(0);
  const [currentImage, setCurrentImage] = useState<GeneratedImage | null>(null);
  const [dbHistory, setDbHistory] = useState<HistoryItem[]>([]);
  const [rechargeOpen, setRechargeOpen] = useState(false);

  const loadData = useCallback(async () => {
    try {
      const [quotaResp, modelsResp, historyResp] = await Promise.all([
        getQuota(),
        getModels(),
        getHistory(1, 20),
      ]);
      setQuota(quotaResp);
      const modelList = modelsResp.models || [];
      setModels(modelList);
      const firstFree = modelList.find((m) => m.freeModel);
      setSelectedModel(firstFree || modelList[0] || null);
      setDbHistory(historyResp || []);
    } catch (err) {
      toast.error("加载工作台数据失败");
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const refreshQuota = useCallback(async () => {
    try {
      const q = await getQuota();
      setQuota(q);
    } catch {
      // ignore
    }
  }, []);

  const refreshHistory = useCallback(async () => {
    try {
      const items = await getHistory(1, 20);
      setDbHistory(items || []);
    } catch {
      // ignore
    }
  }, []);

  // 当前选择的费用
  const currentCost = useMemo(() => {
    if (!selectedModel) return 0;
    const quality =
      selectedModel.qualities?.find(
        (q) =>
          q.level?.toUpperCase() === qualityLevel.toUpperCase() ||
          (qualityLevel === "1K" &&
            (q.level?.toLowerCase() === "standard" ||
              q.level?.toLowerCase() === "fast")) ||
          (qualityLevel === "2K" && q.level?.toLowerCase() === "ultra")
      ) || selectedModel.qualities?.[0];
    if (!quality) return 0;
    const price = quality.prices?.find((p) => p.ratio === aspectRatio);
    return price?.creditCost || quality.prices?.[0]?.creditCost || 0;
  }, [selectedModel, qualityLevel, aspectRatio]);

  const canGenerate = useMemo(() => {
    if (!selectedModel || !prompt.trim() || generating) return false;
    if (quota?.mustLogin) return false;
    if (quota && quota.freeQuotaRemaining > 0 && selectedModel.freeModel)
      return true;
    if (quota && quota.creditBalance >= currentCost) return true;
    return false;
  }, [selectedModel, prompt, generating, quota, currentCost]);

  const generateButtonText = useMemo(() => {
    if (generating) return `正在编织画面 · ${progress}%`;
    if (!prompt.trim()) return "请输入描述";
    if (quota?.mustLogin) return "请登录后继续";
    if (
      quota &&
      quota.freeQuotaRemaining > 0 &&
      selectedModel?.freeModel
    )
      return `开始生成 · 免费`;
    if (quota && quota.creditBalance < currentCost)
      return "余额不足，请充值";
    return `开始生成 · ${currentCost} 创作点`;
  }, [generating, progress, prompt, quota, selectedModel, currentCost]);

  const handleGenerate = useCallback(async () => {
    if (!selectedModel || !prompt.trim() || generating) return;

    if (quota?.mustLogin) {
      router.push("/login?redirect=/workbench");
      return;
    }

    if (
      quota &&
      quota.freeQuotaRemaining <= 0 &&
      quota.creditBalance < currentCost
    ) {
      setRechargeOpen(true);
      return;
    }

    setGenerating(true);
    setProgress(10);

    try {
      const resp = await submitTask({
        modelId: selectedModel.id,
        qualityLevel,
        aspectRatio,
        prompt: prompt.trim(),
        negativePrompt: negativePrompt.trim() || undefined,
      });

      setProgress(30);

      // 刷新额度
      await refreshQuota();

      // 轮询任务状态（不依赖 SSE，更可靠）
      const pollInterval = setInterval(async () => {
        try {
          const task = await getTask(resp.taskId);
          if (task.status === 2) {
            // 完成 — 拉取结果
            clearInterval(pollInterval);
            setProgress(100);
            const results = await getTaskResults(resp.taskId);
            if (results.length > 0) {
              const img: GeneratedImage = {
                taskId: resp.taskId,
                url: results[0].imageUrl,
                prompt: prompt.trim(),
                modelId: selectedModel.id,
                modelName: selectedModel.name,
                timestamp: Date.now(),
              };
              setCurrentImage(img);
            }
            setGenerating(false);
            refreshQuota();
            refreshHistory();
          } else if (task.status === 3) {
            // 失败
            clearInterval(pollInterval);
            setGenerating(false);
            toast.error(task.errorMsg || "生成失败");
            refreshQuota();
          } else if (task.status === 1) {
            setProgress(50);
          }
        } catch (err) {
          // 轮询出错，不中断，继续等待
          console.debug("[Workbench] poll error:", err);
        }
      }, 2000);

      // 10 分钟超时
      setTimeout(() => {
        clearInterval(pollInterval);
        setGenerating((prev) => {
          if (prev) {
            toast.error("生成超时，请稍后查看历史记录");
          }
          return false;
        });
      }, 10 * 60 * 1000);
    } catch (err) {
      setGenerating(false);
      toast.error(err instanceof Error ? err.message : "提交任务失败");
    }
  }, [
    selectedModel,
    prompt,
    negativePrompt,
    generating,
    quota,
    currentCost,
    qualityLevel,
    aspectRatio,
    router,
    refreshQuota,
    refreshHistory,
  ]);

  const handleLogin = () => {
    router.push("/login?redirect=/workbench");
  };

  const handleRegister = () => {
    router.push("/register/email?redirect=/workbench");
  };

  const quotaDisplay = useMemo(() => {
    if (!quota) return null;
    if (quota.guest && quota.freeQuotaRemaining > 0) {
      return (
        <span className="text-green-400">
          免费 {quota.freeQuotaRemaining}/{quota.freeQuotaTotal}
        </span>
      );
    }
    return (
      <span className="text-cyan-400">{quota.creditBalance} 创作点</span>
    );
  }, [quota]);

  // 根据模型动态计算可选分辨率和比例
  const resolutionLevels = useMemo(() => {
    if (selectedModel?.supportedQualities && selectedModel.supportedQualities.length > 0) {
      return selectedModel.supportedQualities.map((q) => ({ key: q, label: q }));
    }
    return DEFAULT_RESOLUTION_LEVELS;
  }, [selectedModel]);

  const aspectRatios = useMemo(() => {
    if (selectedModel?.supportedAspectRatios && selectedModel.supportedAspectRatios.length > 0) {
      return selectedModel.supportedAspectRatios.map((r) => ({ key: r, label: r }));
    }
    return DEFAULT_ASPECT_RATIOS;
  }, [selectedModel]);

  // 切换模型时，如果当前选的分辨率/比例不在新模型支持列表中，回退到第一个
  useEffect(() => {
    if (resolutionLevels.length > 0 && !resolutionLevels.some((r) => r.key === qualityLevel)) {
      setQualityLevel(resolutionLevels[0].key);
    }
  }, [resolutionLevels, qualityLevel]);

  useEffect(() => {
    if (aspectRatios.length > 0 && !aspectRatios.some((r) => r.key === aspectRatio)) {
      setAspectRatio(aspectRatios[0].key);
    }
  }, [aspectRatios, aspectRatio]);

  const currentRatioInfo = aspectRatios.find((r) => r.key === aspectRatio);

  return (
    <div className="flex flex-col h-full gap-4">
      {/* ========== 顶部：额度栏 ========== */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <h1 className="text-xl font-extrabold tracking-tight">
            灵感画布
          </h1>
          <span className="text-[10px] text-muted-foreground/40">
            AI 图像生成工作台
          </span>
        </div>
        <div className="flex items-center gap-2">
          {/* 额度显示 */}
          {quotaDisplay && (
            <div
              onClick={() => setRechargeOpen(true)}
              className="flex items-center gap-1.5 px-3 h-8 rounded-lg border border-border/40 text-xs cursor-pointer hover:border-cyan-400/40 transition-colors"
            >
              <Gift className="h-3 w-3" />
              {quotaDisplay}
            </div>
          )}
          {/* 充值按钮 */}
          <button
            onClick={() => setRechargeOpen(true)}
            className="h-8 px-3 rounded-lg bg-gradient-to-r from-cyan-400 to-violet-400 text-white text-xs font-bold hover:brightness-110 transition-all"
          >
            充值
          </button>
          {/* 登录/注册 */}
          {authHydrated && !isAuthenticated && (
            <>
              <button
                onClick={handleRegister}
                className="h-8 px-3 rounded-lg border border-border/40 text-xs font-medium hover:border-border/80 transition-colors"
              >
                注册
              </button>
              <button
                onClick={handleLogin}
                className="h-8 px-3 rounded-lg border border-border/40 text-xs font-medium hover:border-border/80 transition-colors flex items-center gap-1"
              >
                <LogIn className="h-3 w-3" />
                登录
              </button>
            </>
          )}
        </div>
      </div>

      {/* ========== 主区域：左画布 + 右控制台 ========== */}
      <div className="flex flex-1 min-h-0 gap-4 flex-col lg:flex-row">
        {/* 左侧：画布 */}
        <div className="flex-1 min-h-0 rounded-2xl border border-border/40 bg-card/40 backdrop-blur-sm relative overflow-hidden flex items-center justify-center">
          {/* 画布元信息 */}
          <div className="absolute left-4 top-4 flex items-center gap-2 text-[11px] text-muted-foreground/40 z-10">
            {generating ? (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse" />
                正在生成 · {currentRatioInfo?.label}
              </>
            ) : currentImage ? (
              <>
                <Check className="h-3 w-3 text-green-400" />
                已完成 · {currentRatioInfo?.label}
              </>
            ) : (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/30" />
                画布就绪 · {currentRatioInfo?.label}
              </>
            )}
          </div>

          {/* 图片展示 */}
          {currentImage ? (
            <div className="absolute inset-0 flex items-center justify-center p-8">
              <img
                src={resolveMediaUrl(currentImage.url) || currentImage.url}
                alt={currentImage.prompt}
                className="max-w-full max-h-full object-contain rounded-lg shadow-2xl"
              />
              {/* 下载按钮 */}
              <a
                href={
                  resolveMediaUrl(currentImage.url) || currentImage.url
                }
                download={`loomframe-${currentImage.taskId}.png`}
                className="absolute bottom-10 right-10 p-2 rounded-lg bg-card/80 backdrop-blur-sm border border-border/40 text-muted-foreground hover:text-foreground transition-colors z-10"
                title="下载图片"
              >
                <Download className="h-4 w-4" />
              </a>
              {/* 清空按钮 */}
              <button
                onClick={() => setCurrentImage(null)}
                className="absolute top-10 right-10 p-2 rounded-lg bg-card/80 backdrop-blur-sm border border-border/40 text-muted-foreground hover:text-foreground transition-colors z-10"
                title="清空画布"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          ) : generating ? (
            <div className="flex flex-col items-center gap-4">
              <div className="relative">
                <div className="w-32 h-32 rounded-2xl border-2 border-cyan-400/20 overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-cyan-400/10 to-violet-400/10 transition-all duration-500"
                    style={{ width: `${progress}%` }}
                  />
                </div>
                <Loader2 className="absolute inset-0 m-auto h-6 w-6 animate-spin text-cyan-400" />
              </div>
              <p className="text-xs text-muted-foreground">
                正在编织画面... {progress}%
              </p>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-3 text-muted-foreground/30">
              <div className="w-24 h-24 rounded-2xl border-2 border-dashed border-border/30 flex items-center justify-center">
                <Sparkles className="h-8 w-8" />
              </div>
              <p className="text-xs">输入描述，开始创作</p>
            </div>
          )}
        </div>

        {/* 右侧：控制台 */}
        <div className="w-full lg:w-[340px] shrink-0 rounded-2xl border border-border/40 bg-card/60 backdrop-blur-sm flex flex-col max-h-full">
          {/* 控制台头部 */}
          <div className="flex items-center justify-between px-4 h-12 border-b border-border/40 shrink-0">
            <span className="text-sm font-bold">生成指令</span>
            <span className="flex items-center gap-1 text-[10px] text-green-400">
              <span className="w-1 h-1 rounded-full bg-green-400" />
              自动保存
            </span>
          </div>

          {/* 控制台内容 */}
          <div className="flex-1 overflow-y-auto p-4 space-y-4">
            {/* 提示词输入 */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-[11px] font-semibold">
                  描述你的画面
                </span>
                <span className="text-[10px] text-muted-foreground/40">
                  {prompt.length} / 2000
                </span>
              </div>
              <textarea
                value={prompt}
                onChange={(e) => setPrompt(e.target.value.slice(0, 2000))}
                placeholder="一位旅人在雨夜霓虹街道中回望，电影感构图，暖色逆光..."
                className="w-full h-28 resize-none rounded-xl border border-border/40 bg-muted/20 px-3 py-2.5 text-xs outline-none focus:border-cyan-400/40 focus:ring-2 focus:ring-cyan-400/5 transition-all placeholder:text-muted-foreground/30"
              />
              <div className="flex items-center justify-between mt-1.5">
                <button
                  onClick={() => setShowNegative((v) => !v)}
                  className="text-[10px] text-muted-foreground hover:text-foreground transition-colors"
                >
                  {showNegative ? "隐藏负向提示词" : "添加负向提示词"}
                </button>
                <span className="text-[9px] text-muted-foreground/30">
                  ⌘ + Enter 生成
                </span>
              </div>
              {showNegative && (
                <textarea
                  value={negativePrompt}
                  onChange={(e) => setNegativePrompt(e.target.value)}
                  placeholder="不希望出现的元素，如：模糊、低质量、变形..."
                  className="w-full h-16 mt-2 resize-none rounded-xl border border-border/40 bg-muted/20 px-3 py-2 text-xs outline-none focus:border-cyan-400/40 transition-all placeholder:text-muted-foreground/30"
                />
              )}
            </div>

            {/* 模型选择 */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-[11px] font-semibold">生成引擎</span>
              </div>
              <div className="relative">
                <button
                  onClick={() => setModelDropdownOpen((v) => !v)}
                  className="w-full h-10 rounded-lg border border-border/40 bg-muted/20 px-3 flex items-center justify-between text-xs transition-colors hover:border-border/80"
                >
                  <span className="flex items-center gap-2">
                    <span className="w-6 h-6 rounded-md bg-gradient-to-br from-violet-400 to-pink-400 flex items-center justify-center text-[9px] font-bold text-white">
                      {selectedModel?.name?.charAt(0) || "AI"}
                    </span>
                    {selectedModel?.name || "选择模型"}
                    {selectedModel?.freeModel && (
                      <span className="px-1.5 py-0.5 rounded text-[9px] font-semibold bg-green-500/10 text-green-400 border border-green-500/20">
                        免费
                      </span>
                    )}
                  </span>
                  <ChevronDown
                    className={cn(
                      "h-3.5 w-3.5 text-muted-foreground transition-transform",
                      modelDropdownOpen && "rotate-180"
                    )}
                  />
                </button>
                <AnimatePresence>
                  {modelDropdownOpen && (
                    <motion.div
                      initial={{ opacity: 0, y: -4 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -4 }}
                      className="absolute z-40 left-0 right-0 mt-1.5 rounded-xl border border-border/40 bg-card shadow-xl overflow-hidden max-h-60 overflow-y-auto"
                    >
                      {models.map((model) => (
                        <button
                          key={model.id}
                          onClick={() => {
                            setSelectedModel(model);
                            setModelDropdownOpen(false);
                          }}
                          className="w-full flex items-center justify-between px-3 py-2.5 hover:bg-muted/30 transition-colors text-left"
                        >
                          <span className="flex items-center gap-2">
                            <span className="w-6 h-6 rounded-md bg-gradient-to-br from-violet-400 to-pink-400 flex items-center justify-center text-[9px] font-bold text-white">
                              {model.name?.charAt(0)}
                            </span>
                            <span>
                              <span className="block text-xs">
                                {model.name}
                              </span>
                              <span className="block text-[10px] text-muted-foreground/40">
                                {model.qualities?.[0]?.prices?.[0]?.creditCost ||
                                  0}{" "}
                                创作点
                              </span>
                            </span>
                          </span>
                          {model.freeModel ? (
                            <span className="px-1.5 py-0.5 rounded text-[9px] font-semibold bg-green-500/10 text-green-400 border border-green-500/20">
                              免费
                            </span>
                          ) : (
                            <span className="px-1.5 py-0.5 rounded text-[9px] font-semibold bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                              付费
                            </span>
                          )}
                        </button>
                      ))}
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </div>

            {/* 分辨率 */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-[11px] font-semibold">分辨率</span>
                <span className="text-[10px] text-muted-foreground/40">
                  约 {currentCost} 创作点
                </span>
              </div>
              <div className="flex gap-1.5 flex-wrap">
                {resolutionLevels.map((q) => (
                  <button
                    key={q.key}
                    onClick={() => setQualityLevel(q.key)}
                    className={cn(
                      "flex-1 h-9 rounded-lg border text-xs font-semibold transition-all",
                      qualityLevel === q.key
                        ? "border-cyan-400/40 text-cyan-400 bg-cyan-500/5"
                        : "border-border/30 text-muted-foreground hover:border-border/60"
                    )}
                  >
                    {q.label}
                  </button>
                ))}
              </div>
            </div>

            {/* 画面比例 */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-[11px] font-semibold">画面比例</span>
                <span className="text-[10px] text-muted-foreground/40">
                  {currentRatioInfo?.label}
                </span>
              </div>
              <div className="grid grid-cols-5 gap-1.5">
                {aspectRatios.map((r) => (
                  <button
                    key={r.key}
                    onClick={() => setAspectRatio(r.key)}
                    className={cn(
                      "h-10 rounded-lg border text-[9px] font-medium transition-all",
                      aspectRatio === r.key
                        ? "border-cyan-400/40 text-cyan-400 bg-cyan-500/5"
                        : "border-border/30 text-muted-foreground hover:border-border/60"
                    )}
                  >
                    {r.label}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* 控制台底部：生成按钮 */}
          <div className="p-3 border-t border-border/40 shrink-0">
            <button
              onClick={handleGenerate}
              disabled={!canGenerate}
              className={cn(
                "w-full h-11 rounded-xl font-bold text-sm transition-all",
                canGenerate
                  ? "bg-gradient-to-r from-cyan-400 via-blue-400 to-violet-400 text-white hover:brightness-110 shadow-lg shadow-cyan-500/10"
                  : "bg-muted/30 text-muted-foreground cursor-not-allowed"
              )}
            >
              {generating ? (
                <span className="flex items-center justify-center gap-1.5">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  {generateButtonText}
                </span>
              ) : (
                <span className="flex items-center justify-center gap-1.5">
                  <Wand2 className="h-3.5 w-3.5" />
                  {generateButtonText}
                </span>
              )}
            </button>
          </div>
        </div>
      </div>

      {/* ========== 底部：历史记录 ========== */}
      {dbHistory.length > 0 && (
        <div className="rounded-xl border border-border/40 bg-card/40 backdrop-blur-sm p-3 shrink-0 max-h-48 overflow-hidden flex flex-col">
          <div className="flex items-center justify-between mb-2 shrink-0">
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold">历史记录</span>
              <span className="text-[10px] text-muted-foreground/40">
                {dbHistory.length} 张
              </span>
            </div>
          </div>
          <div className="flex-1 flex gap-3 overflow-x-auto scrollbar-none pb-1">
            {dbHistory.map((item) => (
              <button
                key={item.taskId}
                onClick={() => {
                  if (item.imageUrl) {
                    const img: GeneratedImage = {
                      taskId: item.taskId,
                      url: item.imageUrl,
                      prompt: item.prompt,
                      modelId: item.modelId,
                      modelName:
                        models.find((m) => m.id === item.modelId)?.name ||
                        `#${item.modelId}`,
                      timestamp: Date.now(),
                    };
                    setCurrentImage(img);
                    setPrompt(item.prompt);
                    if (item.negativePrompt) {
                      setNegativePrompt(item.negativePrompt);
                      setShowNegative(true);
                    }
                    if (item.aspectRatio) setAspectRatio(item.aspectRatio);
                    if (item.qualityLevel) setQualityLevel(item.qualityLevel);
                  }
                }}
                className={cn(
                  "shrink-0 w-36 rounded-lg overflow-hidden border-2 transition-all group",
                  currentImage?.taskId === item.taskId
                    ? "border-cyan-400 shadow-lg shadow-cyan-500/10"
                    : "border-transparent hover:border-border/40"
                )}
              >
                <div className="w-full h-20 bg-muted/20 overflow-hidden relative">
                  {item.imageUrl ? (
                    <img
                      src={resolveMediaUrl(item.imageUrl) || item.imageUrl}
                      alt={item.prompt}
                      className="w-full h-full object-cover"
                      onError={(e) => {
                        // 图片加载失败（如远程 OSS URL 已过期），隐藏破损图片并显示占位
                        e.currentTarget.style.display = "none";
                        if (e.currentTarget.parentElement) {
                          const placeholder = document.createElement("div");
                          placeholder.className = "w-full h-full flex items-center justify-center text-muted-foreground/30 text-[10px]";
                          placeholder.innerText = "已过期";
                          e.currentTarget.parentElement.appendChild(placeholder);
                        }
                      }}
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-muted-foreground/30">
                      <Sparkles className="h-5 w-5" />
                    </div>
                  )}
                </div>
                <div className="p-1.5 text-left">
                  <p className="text-[10px] text-muted-foreground line-clamp-2 leading-tight">
                    {item.prompt}
                  </p>
                  <p className="text-[8px] text-muted-foreground/40 mt-0.5">
                    {item.aspectRatio} · {item.createTime?.slice(5, 16) || ""}
                  </p>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 充值弹窗 */}
      <RechargeDialog
        open={rechargeOpen}
        onOpenChange={setRechargeOpen}
        onSuccess={refreshQuota}
      />
    </div>
  );
}
