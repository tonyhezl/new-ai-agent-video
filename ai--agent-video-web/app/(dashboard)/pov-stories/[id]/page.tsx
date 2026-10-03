"use client";

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ArrowLeft,
  Check,
  Download,
  Film,
  Image as ImageIcon,
  Loader2,
  Mic2,
  Play,
  RefreshCw,
  Save,
  Sparkles,
  Video,
  Volume2,
} from "lucide-react";
import { toast } from "sonner";
import {
  povStoryApi,
  type PovAsset,
  type PovAssetVoiceUpdateReq,
  type PovShot,
  type PovStory,
} from "@/lib/api/pov-story";
import { resolveMediaUrl } from "@/lib/api/client";
import { getPovVoiceName, POV_VOICE_OPTIONS } from "@/lib/pov/voice-options";
import { cn } from "@/lib/utils";
import { Can } from "@/components/permission/can";

const GENRES: Record<string, string> = {
  URBAN_MYSTERY: "都市怪谈",
  DEEP_EXPLORATION: "废墟探险",
  HAUNTED_HOUSE_TESTER: "凶宅试睡",
  OCCUPATIONAL_TABOO: "特殊职业",
  TECH_PARANOIA: "科技惊悚",
  UNEXPLORED_ZONE: "诡异禁区",
  ANOMALOUS_SPACE: "空间异象",
  FOLKLORE_SECRETS: "民间秘辛",
};

const STAGES: Record<string, string> = {
  HOOK: "钩子",
  EXPLORE: "探索",
  TWIST: "反转",
  CLIMAX: "高潮",
};

const STATUS_LABELS: Record<string, string> = {
  PENDING: "待生成",
  GENERATING: "生成中",
  READY: "已完成",
  COMPLETED: "已完成",
  FAILED: "生成失败",
  NOT_REQUIRED: "无需生成",
};

function formatAssetRole(assetRole: string): string {
  const normalized = assetRole.toUpperCase();
  if (normalized.includes("CHARACTER")) return "角色";
  if (normalized.includes("SCENE") || normalized.includes("LOCATION")) return "场景";
  if (normalized.includes("ENVIRONMENT")) return "环境";
  if (normalized.includes("PROP")) return normalized.includes("KEY") ? "关键道具" : "道具";
  return assetRole;
}

function formatItemLabel(itemType: string, itemIndex: number): string {
  return itemType.toLowerCase() === "initial" ? "初始状态" : `状态变体 ${itemIndex}`;
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : "未知错误";
}

function parseVoice(voiceConfig?: string, sampleText?: string): PovAssetVoiceUpdateReq {
  try {
    const parsed = voiceConfig ? JSON.parse(voiceConfig) : {};
    return {
      voiceId: parsed.voiceId || "",
      voiceName: parsed.voiceName || "",
      speed: parsed.speed,
      pitch: parsed.pitch,
      emotion: parsed.emotion || "",
      customVoiceId: parsed.customVoiceId || "",
      sampleText: sampleText || "",
    };
  } catch {
    return { voiceId: "", sampleText: sampleText || "" };
  }
}

function notifyGenerationChanges(previous: PovStory, current: PovStory) {
  const previousAssets = new Map((previous.assets || []).map((asset) => [asset.id, asset]));
  const readyImages: string[] = [];
  const failedImages: string[] = [];
  const readyVoices: string[] = [];
  const failedVoices: string[] = [];

  for (const asset of current.assets || []) {
    const previousAsset = previousAssets.get(asset.id);
    if (!previousAsset) continue;

    const previousItems = new Map(previousAsset.items.map((item) => [item.id, item]));
    for (const item of asset.items) {
      const previousItem = previousItems.get(item.id);
      if (!previousItem) continue;

      const label = `${asset.name}（${item.stateDescription || item.itemKey}）`;
      const imageChanged = Boolean(item.imageUrl && item.imageUrl !== previousItem.imageUrl);
      if (item.status === "READY" && (previousItem.status !== "READY" || imageChanged)) {
        readyImages.push(label);
      } else if (item.status === "FAILED" && previousItem.status !== "FAILED") {
        failedImages.push(label);
      }
    }

    if (asset.voiceStatus === "READY"
      && (previousAsset.voiceStatus !== "READY"
        || asset.referenceAudioUrl !== previousAsset.referenceAudioUrl)) {
      readyVoices.push(asset.name);
    } else if (asset.voiceStatus === "FAILED" && previousAsset.voiceStatus !== "FAILED") {
      failedVoices.push(asset.name);
    }
  }

  const previousShots = new Map((previous.shots || []).map((shot) => [shot.id, shot]));
  const readyVideos: number[] = [];
  const failedVideos: number[] = [];
  for (const shot of current.shots || []) {
    const previousShot = previousShots.get(shot.id);
    if (!previousShot) continue;
    const videoChanged = Boolean(shot.videoUrl && shot.videoUrl !== previousShot.videoUrl);
    if (shot.videoStatus === "COMPLETED"
      && (previousShot.videoStatus !== "COMPLETED" || videoChanged)) {
      readyVideos.push(shot.shotOrder);
    } else if (shot.videoStatus === "FAILED" && previousShot.videoStatus !== "FAILED") {
      failedVideos.push(shot.shotOrder);
    }
  }

  if (readyImages.length === 1) toast.success(`${readyImages[0]}资产图片生成完成`);
  else if (readyImages.length > 1) toast.success(`${readyImages.length} 个资产图片生成完成`);
  if (failedImages.length === 1) toast.error(`${failedImages[0]}资产图片生成失败`);
  else if (failedImages.length > 1) toast.error(`${failedImages.length} 个资产图片生成失败`);
  if (readyVoices.length === 1) toast.success(`${readyVoices[0]}参考音频生成完成`);
  else if (readyVoices.length > 1) toast.success(`${readyVoices.length} 个角色参考音频生成完成`);
  if (failedVoices.length === 1) toast.error(`${failedVoices[0]}参考音频生成失败`);
  else if (failedVoices.length > 1) toast.error(`${failedVoices.length} 个角色参考音频生成失败`);
  if (readyVideos.length === 1) toast.success(`镜头 ${readyVideos[0]} 视频生成完成`);
  else if (readyVideos.length > 1) toast.success(`${readyVideos.length} 个镜头视频生成完成`);
  if (failedVideos.length === 1) toast.error(`镜头 ${failedVideos[0]} 视频生成失败`);
  else if (failedVideos.length > 1) toast.error(`${failedVideos.length} 个镜头视频生成失败`);
}

function CharacterVoiceEditor({
  storyId,
  asset,
  busy,
  onBusy,
  onRefresh,
}: {
  storyId: number;
  asset: PovAsset;
  busy: boolean;
  onBusy: (value: boolean) => void;
  onRefresh: () => void;
}) {
  const [form, setForm] = useState<PovAssetVoiceUpdateReq>(() =>
    parseVoice(asset.voiceConfig, asset.voiceSampleText));
  const selectedVoiceName = getPovVoiceName(form.voiceId, form.voiceName);

  useEffect(() => {
    setForm(parseVoice(asset.voiceConfig, asset.voiceSampleText));
  }, [asset.voiceConfig, asset.voiceSampleText]);

  const saveVoice = async () => {
    if (!form.voiceId.trim()) {
      toast.error("请填写音色 ID");
      return;
    }
    onBusy(true);
    try {
      await povStoryApi.updateAssetVoice(storyId, asset.id, {
        ...form,
        voiceName: getPovVoiceName(form.voiceId, form.voiceName),
      });
      toast.success(`角色${asset.characterNo}音色已保存`);
      onRefresh();
    } catch (error) {
      toast.error(`保存失败: ${messageOf(error)}`);
    } finally {
      onBusy(false);
    }
  };

  const generateAudio = async () => {
    if (!form.voiceId.trim()) {
      toast.error("请选择音色");
      return;
    }
    onBusy(true);
    try {
      await povStoryApi.updateAssetVoice(storyId, asset.id, {
        ...form,
        voiceName: getPovVoiceName(form.voiceId, form.voiceName),
      });
      await povStoryApi.generateReferenceAudio(storyId, asset.id);
      toast.success(`角色${asset.characterNo}参考音频已提交`);
      onRefresh();
    } catch (error) {
      toast.error(`提交失败: ${messageOf(error)}`);
    } finally {
      onBusy(false);
    }
  };

  return (
    <div className="mt-3 border-t border-border/60 pt-3 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-xs font-medium">角色 {asset.characterNo} · {asset.name}</p>
          <p className="text-[11px] text-muted-foreground mt-0.5">{asset.voiceDescription || "未设置声音描述"}</p>
        </div>
        <span className={cn(
          "text-[10px] px-2 py-1 border rounded",
          asset.voiceStatus === "READY" ? "text-emerald-400 border-emerald-500/30" :
            asset.voiceStatus === "FAILED" ? "text-red-400 border-red-500/30" : "text-amber-400 border-amber-500/30"
        )}>
          {STATUS_LABELS[asset.voiceStatus || "PENDING"] || asset.voiceStatus || "待生成"}
        </span>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-[260px_1fr_auto] gap-2">
        <select
          value={form.voiceId}
          onChange={(event) => {
            const voiceId = event.target.value;
            setForm((value) => ({
              ...value,
              voiceId,
              voiceName: getPovVoiceName(voiceId),
            }));
          }}
          aria-label={`角色${asset.characterNo}音色`}
          className="h-9 px-3 rounded border border-border bg-background text-xs outline-none focus:border-amber-500/60"
        >
          {!form.voiceId && <option value="">请选择音色</option>}
          {form.voiceId && !POV_VOICE_OPTIONS.some((option) => option.id === form.voiceId) && (
            <option value={form.voiceId}>{selectedVoiceName}（自定义）</option>
          )}
          {POV_VOICE_OPTIONS.map((option) => (
            <option key={option.id} value={option.id}>{option.name}</option>
          ))}
        </select>
        <input
          value={form.sampleText || ""}
          onChange={(event) => setForm((value) => ({ ...value, sampleText: event.target.value }))}
          placeholder="参考音频样本文本"
          className="h-9 px-3 rounded border border-border bg-background text-xs outline-none focus:border-amber-500/60"
        />
        <div className="flex gap-2">
          <button onClick={saveVoice} disabled={busy} title="保存音色" className="h-9 w-9 grid place-items-center rounded border border-border hover:bg-muted disabled:opacity-50">
            <Save className="w-4 h-4" />
          </button>
          <button onClick={generateAudio} disabled={busy || !form.voiceId.trim()} className="h-9 px-3 inline-flex items-center gap-1.5 rounded bg-amber-500 text-black text-xs font-medium hover:bg-amber-400 disabled:opacity-50">
            {busy || asset.voiceStatus === "GENERATING" ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Mic2 className="w-3.5 h-3.5" />}
            生成参考音频
          </button>
        </div>
      </div>
      {asset.referenceAudioUrl && (
        <div className="flex items-center gap-2 text-xs text-emerald-400">
          <Volume2 className="w-4 h-4 shrink-0" />
          <audio src={resolveMediaUrl(asset.referenceAudioUrl) || undefined} controls className="h-8 w-full max-w-xl" />
        </div>
      )}
      {asset.voiceErrorMessage && <p className="text-xs text-red-400">{asset.voiceErrorMessage}</p>}
    </div>
  );
}

export default function PovStoryDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const router = useRouter();
  const { id } = use(params);
  const storyId = Number(id);
  const [story, setStory] = useState<PovStory | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [generatingAllAssets, setGeneratingAllAssets] = useState(false);
  const [generatingAllVideos, setGeneratingAllVideos] = useState(false);
  const [composing, setComposing] = useState(false);
  const storyRef = useRef<PovStory | null>(null);
  const fetchingRef = useRef(false);

  const fetchDetail = useCallback(async () => {
    if (fetchingRef.current) return;
    fetchingRef.current = true;
    try {
      const data = await povStoryApi.get(storyId);
      if (storyRef.current) notifyGenerationChanges(storyRef.current, data);
      storyRef.current = data;
      setStory(data);
    } catch (error) {
      toast.error(`获取故事详情失败: ${messageOf(error)}`);
    } finally {
      fetchingRef.current = false;
      setLoading(false);
    }
  }, [storyId]);

  useEffect(() => { fetchDetail(); }, [fetchDetail]);

  const hasRunningTask = useMemo(() => {
    const assetRunning = story?.assets?.some((asset) =>
      asset.voiceStatus === "GENERATING" || asset.items.some((item) => item.status === "GENERATING"));
    const videoRunning = story?.shots?.some((shot) => shot.videoStatus === "GENERATING");
    return Boolean(assetRunning || videoRunning || generatingAllAssets || generatingAllVideos);
  }, [story, generatingAllAssets, generatingAllVideos]);

  useEffect(() => {
    if (!hasRunningTask) return;
    const timer = window.setInterval(fetchDetail, 2500);
    return () => window.clearInterval(timer);
  }, [hasRunningTask, fetchDetail]);

  const generateAssetItem = async (itemId: number) => {
    setActiveKey(`item-${itemId}`);
    try {
      await povStoryApi.generateAssetImage(storyId, itemId);
      toast.success("资产图片任务已提交");
      fetchDetail();
    } catch (error) {
      toast.error(`提交失败: ${messageOf(error)}`);
    } finally {
      setActiveKey(null);
    }
  };

  const generateAllAssets = async () => {
    setGeneratingAllAssets(true);
    try {
      const count = await povStoryApi.generateAllAssets(storyId);
      toast.success(`已提交 ${count} 个初始资产，状态变体将在主图完成后自动生成`);
      fetchDetail();
    } catch (error) {
      toast.error(`批量生成资产失败: ${messageOf(error)}`);
    } finally {
      setGeneratingAllAssets(false);
    }
  };

  const generateShotVideo = async (shotId: number) => {
    setActiveKey(`shot-${shotId}`);
    try {
      await povStoryApi.generateShotVideo(storyId, shotId);
      toast.success("镜头视频任务已提交");
      fetchDetail();
    } catch (error) {
      toast.error(`提交失败: ${messageOf(error)}`);
    } finally {
      setActiveKey(null);
    }
  };

  const generateAllVideos = async () => {
    setGeneratingAllVideos(true);
    try {
      const count = await povStoryApi.generateAllShots(storyId);
      toast.success(`已直接提交 ${count} 个视频任务`);
      fetchDetail();
    } catch (error) {
      toast.error(`批量生成视频失败: ${messageOf(error)}`);
    } finally {
      setGeneratingAllVideos(false);
    }
  };

  const compose = async () => {
    setComposing(true);
    try {
      await povStoryApi.compose(storyId);
      toast.success("完整视频合成成功");
      fetchDetail();
    } catch (error) {
      toast.error(`合成失败: ${messageOf(error)}`);
    } finally {
      setComposing(false);
    }
  };

  if (loading) return <div className="h-80 grid place-items-center"><Loader2 className="w-6 h-6 animate-spin text-amber-400" /></div>;
  if (!story) return <div className="p-8 text-sm text-muted-foreground">故事不存在或已被删除</div>;

  const assets = story.assets || [];
  const shots = story.shots || [];

  return (
    <div className="max-w-7xl mx-auto p-4 md:p-6 pb-20 space-y-7">
      <header className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 border-b border-border pb-4">
        <div className="flex items-center gap-3 min-w-0">
          <button onClick={() => router.push("/pov-stories")} title="返回" className="w-9 h-9 shrink-0 grid place-items-center rounded border border-border hover:bg-muted">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="text-xl font-semibold truncate">{story.title}</h1>
              <span className="text-[11px] px-2 py-1 rounded border border-amber-500/30 text-amber-400">{GENRES[story.topicGenre] || story.topicGenre}</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">{assets.length} 个故事资产 · {shots.length} 个直接视频镜头 · 约 {story.totalDurationEstimate} 秒</p>
          </div>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <Can permissionCode="pov:story:edit"><Can permissionCode="generation:create">
          <button onClick={generateAllAssets} disabled={generatingAllAssets} className="h-9 px-3 inline-flex items-center gap-1.5 rounded border border-border hover:bg-muted text-xs disabled:opacity-50">
            {generatingAllAssets ? <Loader2 className="w-4 h-4 animate-spin" /> : <Sparkles className="w-4 h-4 text-indigo-400" />}
            生成故事资产
          </button>
          <button onClick={generateAllVideos} disabled={generatingAllVideos} className="h-9 px-3 inline-flex items-center gap-1.5 rounded bg-amber-500 text-black hover:bg-amber-400 text-xs font-medium disabled:opacity-50">
            {generatingAllVideos ? <Loader2 className="w-4 h-4 animate-spin" /> : <Video className="w-4 h-4" />}
            批量直接生成视频
          </button>
          <button onClick={compose} disabled={composing} className="h-9 px-3 inline-flex items-center gap-1.5 rounded border border-border hover:bg-muted text-xs disabled:opacity-50">
            {composing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Film className="w-4 h-4" />}
            合成成片
          </button>
          </Can></Can>
        </div>
      </header>

      <section className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-5">
        <div className="space-y-3">
          {story.hookText && <p className="text-sm text-amber-300 border-l-2 border-amber-400 pl-3">{story.hookText}</p>}
          <p className="text-sm leading-7 text-foreground/85 whitespace-pre-wrap">{story.fullScript}</p>
        </div>
        <div className="border border-border rounded p-3 bg-card">
          <p className="text-xs font-medium flex items-center gap-1.5"><Play className="w-4 h-4 text-emerald-400" />完整成片</p>
          {story.finalVideoUrl ? (
            <>
              <video src={resolveMediaUrl(story.finalVideoUrl) || undefined} controls className="mt-3 w-full aspect-[9/16] max-h-60 bg-black object-contain rounded" />
              <a href={resolveMediaUrl(story.finalVideoUrl) || undefined} target="_blank" rel="noreferrer" download className="mt-2 h-8 flex items-center justify-center gap-1.5 rounded border border-border text-xs hover:bg-muted">
                <Download className="w-3.5 h-3.5" />下载
              </a>
            </>
          ) : <div className="mt-3 h-32 grid place-items-center border border-dashed border-border rounded text-xs text-muted-foreground">全部镜头视频完成后可合成</div>}
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex items-end justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold">故事资产与角色声音</h2>
            <p className="text-xs text-muted-foreground mt-1">资产只属于当前 POV 故事；状态变体依赖初始图生成。</p>
          </div>
        </div>
        {assets.length === 0 ? (
          <div className="p-6 border border-dashed border-border rounded text-sm text-muted-foreground">当前故事没有资产结构，需要重新生成故事。</div>
        ) : (
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-3">
            {assets.map((asset) => (
              <article key={asset.id} className="border border-border rounded bg-card p-3">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3 className="text-sm font-medium">{asset.name}</h3>
                      <span className="text-[10px] px-1.5 py-0.5 rounded border border-border text-muted-foreground">{formatAssetRole(asset.assetRole)}</span>
                    </div>
                    <p className="text-xs text-muted-foreground mt-1 leading-5">{asset.description}</p>
                  </div>
                </div>
                <div className="mt-3 divide-y divide-border/60 border-y border-border/60">
                  {asset.items.map((item, itemIndex) => (
                    <div key={item.id} className="py-2 flex items-center gap-3">
                      <div className="w-16 h-16 shrink-0 rounded overflow-hidden bg-muted grid place-items-center">
                        {item.imageUrl ? (
                          // Asset URLs can come from user-configured storage providers that Next Image cannot whitelist.
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={resolveMediaUrl(item.imageUrl) || undefined} alt={item.stateDescription || item.itemKey} className="w-full h-full object-cover" />
                        ) : <ImageIcon className="w-5 h-5 text-muted-foreground" />}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium">{formatItemLabel(item.itemType, itemIndex)}</span>
                          {item.status === "READY" && <Check className="w-3.5 h-3.5 text-emerald-400" />}
                        </div>
                        <p className="text-[11px] text-muted-foreground mt-1 line-clamp-2">{item.stateDescription}</p>
                        {item.errorMessage && <p className="text-[11px] text-red-400 mt-1">{item.errorMessage}</p>}
                      </div>
                      <Can permissionCode="pov:story:edit"><Can permissionCode="generation:create"><button
                        onClick={() => generateAssetItem(item.id)}
                        disabled={item.status === "GENERATING" || activeKey === `item-${item.id}`}
                        title={item.imageUrl ? "重新生成资产图" : "生成资产图"}
                        className="w-8 h-8 shrink-0 grid place-items-center rounded border border-border hover:bg-muted disabled:opacity-50"
                      >
                        {item.status === "GENERATING" || activeKey === `item-${item.id}` ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
                      </button></Can></Can>
                    </div>
                  ))}
                </div>
                {asset.characterNo != null && (
                  <Can permissionCode="pov:story:edit"><Can permissionCode="pov:voice:generate"><CharacterVoiceEditor
                    storyId={storyId}
                    asset={asset}
                    busy={activeKey === `voice-${asset.id}`}
                    onBusy={(value) => setActiveKey(value ? `voice-${asset.id}` : null)}
                    onRefresh={fetchDetail}
                  /></Can></Can>
                )}
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="space-y-3">
        <div>
          <h2 className="text-base font-semibold">POV 直接视频镜头</h2>
          <p className="text-xs text-muted-foreground mt-1">参考顺序即实际传入顺序；镜头不生成首帧或尾帧。</p>
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          {shots.map((shot: PovShot) => (
            <article key={shot.id} className="border border-border rounded bg-card p-3 grid grid-cols-1 sm:grid-cols-[1fr_150px] gap-3">
              <div className="min-w-0 space-y-3">
                <div className="flex items-center gap-2 flex-wrap">
                  <h3 className="text-sm font-semibold">镜头 {shot.shotOrder}</h3>
                  <span className="text-[10px] px-1.5 py-0.5 rounded border border-border text-muted-foreground">{STAGES[shot.narrativeStage] || shot.narrativeStage}</span>
                  <span className="text-[10px] text-muted-foreground">{shot.durationSec || 5}s · {shot.cameraMovement || shot.cameraView}</span>
                </div>
                <p className="text-xs leading-5">{shot.videoPrompt || shot.visualPrompt}</p>
                <div>
                  <p className="text-[11px] font-medium text-muted-foreground mb-1.5">参考图片顺序</p>
                  <div className="flex flex-wrap gap-1.5">
                    {(shot.assetRefs || []).map((ref, index) => (
                      <span key={ref.assetItemId} className={cn("text-[10px] px-2 py-1 rounded border", ref.imageUrl ? "border-emerald-500/30 text-emerald-400" : "border-amber-500/30 text-amber-400")}>
                        图片{index + 1} · {ref.assetName}/{ref.itemKey}
                      </span>
                    ))}
                  </div>
                </div>
                {(shot.speakers || []).length > 0 && (
                  <div>
                    <p className="text-[11px] font-medium text-muted-foreground mb-1.5">角色声音映射</p>
                    <div className="space-y-1">
                      {(shot.speakers || []).map((speaker, index, speakers) => {
                        const imageIndex = (shot.assetRefs || []).findIndex((ref) => ref.assetId === speaker.assetId) + 1;
                        const audioIndex = speakers.findIndex((item) => item.assetId === speaker.assetId) + 1;
                        return <p key={`${speaker.assetId}-${index}`} className="text-[11px] text-foreground/80">角色{speaker.characterNo} · {speaker.characterName} → 图片{imageIndex || "未绑定"} → 音频{audioIndex}：{speaker.dialogueText}</p>;
                      })}
                    </div>
                  </div>
                )}
                {shot.errorMessage && <p className="text-xs text-red-400">{shot.errorMessage}</p>}
                <Can permissionCode="pov:story:edit"><Can permissionCode="generation:create"><button
                  onClick={() => generateShotVideo(shot.id)}
                  disabled={shot.videoStatus === "GENERATING" || activeKey === `shot-${shot.id}`}
                  className="h-8 px-3 inline-flex items-center gap-1.5 rounded border border-border hover:bg-muted text-xs disabled:opacity-50"
                >
                  {shot.videoStatus === "GENERATING" || activeKey === `shot-${shot.id}` ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Video className="w-3.5 h-3.5" />}
                  {shot.videoUrl ? "重新生成视频" : "直接生成视频"}
                </button></Can></Can>
              </div>
              <div className="aspect-[9/16] max-h-64 rounded overflow-hidden bg-black border border-border grid place-items-center">
                {shot.videoUrl ? <video src={resolveMediaUrl(shot.videoUrl) || undefined} controls className="w-full h-full object-contain" /> :
                  shot.videoStatus === "GENERATING" ? <Loader2 className="w-6 h-6 animate-spin text-amber-400" /> :
                    <Video className="w-7 h-7 text-muted-foreground" />}
              </div>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
