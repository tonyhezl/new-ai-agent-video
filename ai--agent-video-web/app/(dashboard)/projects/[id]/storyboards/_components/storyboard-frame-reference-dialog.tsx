"use client";

import { useCallback, useRef, useState } from "react";
import { Image as ImageIcon, Loader2, Sparkles, X, ZoomIn } from "lucide-react";
import ImageInput from "@/components/dashboard/image-input";
import { SafeImage } from "@/components/ui/safe-image";
import { Textarea } from "@/components/ui/textarea";
import { resolveMediaUrl } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { Project } from "@/lib/api/project";
import type { StoryboardFrameType, StoryboardItem } from "@/lib/api/storyboard";

type FrameUpdateHandler = (
  itemId: number,
  frameType: StoryboardFrameType,
  imageUrl: string | null
) => Promise<void> | void;

type FramePromptUpdateHandler = (
  itemId: number,
  frameType: StoryboardFrameType,
  prompt: string | null
) => Promise<void> | void;

type FrameGenerateHandler = (
  item: StoryboardItem,
  frameType: StoryboardFrameType,
  prompt: string
) => Promise<void> | void;

function getFrameLabel(frameType: StoryboardFrameType) {
  return frameType === "first" ? "首帧" : "尾帧";
}

export function buildDefaultFramePrompt(
  item: StoryboardItem,
  project: Project | null | undefined,
  frameType: StoryboardFrameType
) {
  const style =
    [
      project?.artStyleDescription,
      project?.artStyleImagePrompt,
      project?.artStyle,
    ].find((text) => text && text.trim()) || "高质量精细画面";
  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const parts = [
    `项目画风：${style}`,
    `镜头：${shotLabel}`,
    item.shotType ? `景别：${item.shotType}` : null,
    item.content ? `画面内容：${item.content}` : null,
    item.sceneExpectation ? `画面期望：${item.sceneExpectation}` : null,
    item.dialogue ? `对白/旁白：${item.dialogue}` : null,
    item.cameraMovement ? `运镜：${item.cameraMovement}` : null,
    item.cameraAngle ? `机位角度：${item.cameraAngle}` : null,
  ].filter(Boolean);

  const frameInstruction =
    frameType === "first"
      ? "生成该镜头视频的首帧定格图。只表现镜头内容中第一个动作的起始瞬间（如第一个动词对应的动作刚开始的状态），禁止包含后续连续动作。环境、光线、氛围、场景细节保留完整。"
      : "生成该镜头视频的尾帧定格图。只表现镜头内容中最后一个动作完成后的定格结果状态，禁止包含动作过程词（如\"正在\"\"着去\"），禁止包含前序动作。环境、光线、氛围、场景细节保留完整。";

  return `${frameInstruction}\n${parts.join("\n")}`;
}

export function buildDefaultBatchFramePrompt(frameType: StoryboardFrameType) {
  const frameInstruction =
    frameType === "first"
      ? "生成每个分镜镜头视频的首帧定格图，只表现镜头内容中第一个动作的起始瞬间，禁止包含后续连续动作。"
      : "生成每个分镜镜头视频的尾帧定格图，只表现镜头内容中最后一个动作完成后的定格结果状态，禁止包含动作过程词和前序动作。";

  return `${frameInstruction}\n请结合每个镜头自身的画面内容、画面期望、对白/旁白、景别、运镜、机位角度、角色、道具、场景和项目画风补足构图、主体关系、环境氛围与细节。保持同一分镜集内角色、道具、场景和画面风格一致。`;
}

function FramePromptDialog({
  open,
  title,
  defaultPrompt,
  submitting,
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  defaultPrompt: string;
  submitting?: boolean;
  onClose: () => void;
  onConfirm: (prompt: string) => Promise<void> | void;
}) {
  const [prompt, setPrompt] = useState(defaultPrompt);

  if (!open) return null;

  const canSubmit = prompt.trim().length > 0 && !submitting;

  const handleConfirm = async () => {
    if (!canSubmit) return;
    await onConfirm(prompt.trim());
  };

  return (
    <div className="fixed inset-0 z-[10000] flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={() => !submitting && onClose()}
      />
      <div className="relative bg-card border border-border/30 rounded-xl shadow-2xl w-[520px] max-w-[92vw] max-h-[calc(100vh-2rem)] sm:max-h-[82vh] flex flex-col overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-border/20 shrink-0">
          <div className="flex items-center gap-2">
            <div className="h-8 w-8 rounded-lg bg-primary/10 flex items-center justify-center">
              <Sparkles className="h-4 w-4 text-primary" />
            </div>
            <div>
              <h3 className="text-sm font-semibold">{title}</h3>
              <p className="text-[10px] text-muted-foreground">
                编辑后提交到任务中心
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="p-1.5 rounded-lg hover:bg-muted text-muted-foreground transition-colors disabled:opacity-40"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="p-5 min-h-0 overflow-hidden">
          <Textarea
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            className="h-[min(52vh,28rem)] min-h-40 max-h-full overflow-y-auto overscroll-contain text-xs leading-relaxed resize-none [field-sizing:fixed]"
          />
        </div>

        <div className="px-5 py-3.5 border-t border-border/20 flex items-center justify-end gap-2 shrink-0">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 rounded-lg text-xs font-medium text-muted-foreground hover:bg-muted transition-colors disabled:opacity-40"
          >
            取消
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={!canSubmit}
            className={cn(
              "flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-medium transition-all",
              "bg-primary text-primary-foreground hover:bg-primary/90",
              "disabled:opacity-40 disabled:pointer-events-none"
            )}
          >
            {submitting ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Sparkles className="h-3.5 w-3.5" />
            )}
            提交生成
          </button>
        </div>
      </div>
    </div>
  );
}

export function FrameReferenceSection({
  item,
  project,
  frameType,
  imageUrl,
  prompt,
  effectivePrompt,
  className,
  onUpdateFrame,
  onUpdateFramePrompt,
  onGenerateFrame,
  onGenerateSubmitted,
  onPreviewImage,
}: {
  item: StoryboardItem;
  project?: Project | null;
  frameType: StoryboardFrameType;
  imageUrl: string | null;
  prompt: string | null;
  effectivePrompt: string | null;
  className?: string;
  onUpdateFrame?: FrameUpdateHandler;
  onUpdateFramePrompt?: FramePromptUpdateHandler;
  onGenerateFrame?: FrameGenerateHandler;
  onGenerateSubmitted?: () => void;
  onPreviewImage?: (url: string, title: string) => void;
}) {
  const label = getFrameLabel(frameType);
  const [promptOpen, setPromptOpen] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [promptEditing, setPromptEditing] = useState(false);
  const [promptValue, setPromptValue] = useState(prompt || "");
  const [promptSaving, setPromptSaving] = useState(false);
  const skipNextChangeConfirmRef = useRef(false);
  const autoDefaultPrompt = buildDefaultFramePrompt(item, project, frameType);
  const defaultPrompt = prompt || autoDefaultPrompt;

  const missingFirstFrame = frameType === "last" && !item.firstFrameImageUrl;

  const confirmOverwrite = useCallback(() => {
    if (!imageUrl) return true;
    return confirm(`${label}参考图已存在，确认覆盖吗？`);
  }, [imageUrl, label]);

  const handleChange = async (nextValue: string) => {
    if (!onUpdateFrame) return;
    const nextUrl = nextValue.trim() || null;
    const currentUrl = imageUrl?.trim() || null;
    if (
      nextUrl &&
      currentUrl &&
      nextUrl !== currentUrl &&
      !skipNextChangeConfirmRef.current &&
      !confirmOverwrite()
    ) {
      return;
    }
    skipNextChangeConfirmRef.current = false;
    try {
      setUpdating(true);
      await onUpdateFrame(item.id, frameType, nextUrl);
    } catch (err) {
      console.error(`更新${label}失败:`, err);
      alert(`更新${label}失败，请重试`);
    } finally {
      setUpdating(false);
    }
  };

  const handleOpenPrompt = () => {
    if (!onGenerateFrame) return;
    if (missingFirstFrame) {
      alert("该镜头尚未生成首帧，无法生成尾帧。请先生成首帧。");
      return;
    }
    if (!confirmOverwrite()) return;
    setPromptOpen(true);
  };

  const handleConfirmPrompt = async (nextPrompt: string) => {
    if (!onGenerateFrame) return;
    try {
      setSubmitting(true);
      await onGenerateFrame(item, frameType, nextPrompt);
      setPromptOpen(false);
      setSubmitting(false);
      onGenerateSubmitted?.();
    } catch (err) {
      console.error(`提交${label}生成失败:`, err);
      alert(`提交${label}生成失败，请重试`);
      setSubmitting(false);
    }
  };

  return (
    <div className={cn("border-t border-border/20 pt-4", className)}>
      <div className="flex items-center justify-between gap-2 mb-2">
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
          <ImageIcon className="h-3 w-3" /> {label}参考图
        </h4>
        <div className="flex items-center gap-1">
          {imageUrl && onPreviewImage && (
            <button
              type="button"
              onClick={() => onPreviewImage(imageUrl, `${label}参考图`)}
              className="h-7 w-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted/40 transition-colors"
              title={`预览${label}`}
            >
              <ZoomIn className="h-3.5 w-3.5" />
            </button>
          )}
          {onGenerateFrame && (
            <button
              type="button"
              onClick={handleOpenPrompt}
              disabled={submitting || missingFirstFrame}
              className="h-7 w-7 rounded-lg flex items-center justify-center text-primary hover:bg-primary/10 transition-colors disabled:opacity-40 disabled:pointer-events-none"
              title={missingFirstFrame ? "请先生成首帧后再生成尾帧" : `AI生成${label}`}
            >
              {submitting ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Sparkles className="h-3.5 w-3.5" />
              )}
            </button>
          )}
        </div>
      </div>

      {missingFirstFrame && (
        <div className="mb-2 rounded-lg bg-amber-500/10 border border-amber-500/20 px-3 py-2 text-[10px] text-amber-600">
          该镜头尚未生成首帧，无法生成尾帧。请先切换到首帧并生成。
        </div>
      )}

      <div className={cn(updating && "opacity-70 pointer-events-none")}>
        <ImageInput
          value={imageUrl || ""}
          onChange={(nextUrl) => {
            void handleChange(nextUrl);
          }}
          previewHeight="h-96"
          previewContainerClassName="mx-auto w-[360px] max-w-full bg-muted/20"
          previewImageClassName="object-contain"
          onPreviewClick={
            imageUrl && onPreviewImage
              ? () => onPreviewImage(imageUrl, `${label}参考图`)
              : undefined
          }
          uploadSubDir="storyboard-frames"
          placeholder={`粘贴${label}图片链接...`}
          beforeUpload={() => {
            const ok = confirmOverwrite();
            skipNextChangeConfirmRef.current = ok;
            if (ok) {
              window.setTimeout(() => {
                skipNextChangeConfirmRef.current = false;
              }, 5000);
            }
            return ok;
          }}
        />
      </div>

      {/* 用户确认的基础指令：可查看、编辑、保存 */}
      <div className="mt-3">
        <p className="mb-1.5 text-[10px] font-medium text-muted-foreground">生成指令</p>
        {promptEditing ? (
          <div className="space-y-2">
            <Textarea
              value={promptValue}
              onChange={(e) => setPromptValue(e.target.value)}
              className="text-[10px] leading-relaxed min-h-20 resize-none"
              placeholder="输入首尾帧提示词..."
            />
            <div className="flex items-center justify-end gap-1.5">
              <button
                type="button"
                onClick={() => {
                  setPromptEditing(false);
                  setPromptValue(prompt || "");
                }}
                disabled={promptSaving}
                className="px-2.5 py-1 rounded-md text-[10px] font-medium text-muted-foreground hover:bg-muted transition-colors disabled:opacity-40"
              >
                取消
              </button>
              <button
                type="button"
                onClick={async () => {
                  if (!onUpdateFramePrompt) return;
                  try {
                    setPromptSaving(true);
                    await onUpdateFramePrompt(item.id, frameType, promptValue.trim() || null);
                    setPromptEditing(false);
                  } catch (err) {
                    console.error(`保存${label}提示词失败:`, err);
                    alert(`保存${label}提示词失败，请重试`);
                  } finally {
                    setPromptSaving(false);
                  }
                }}
                disabled={promptSaving}
                className="flex items-center gap-1 px-2.5 py-1 rounded-md text-[10px] font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-40"
              >
                {promptSaving && <Loader2 className="h-3 w-3 animate-spin" />}
                保存
              </button>
            </div>
          </div>
        ) : (
          <div className="group/prompt">
            {prompt ? (
              <p className="text-[10px] text-muted-foreground/60 line-clamp-4 leading-relaxed whitespace-pre-wrap">
                {prompt}
              </p>
            ) : (
              <p className="text-[10px] text-muted-foreground/30 italic">
                暂无提示词
              </p>
            )}
            {onUpdateFramePrompt && (
              <button
                type="button"
                onClick={() => {
                  setPromptValue(prompt || autoDefaultPrompt);
                  setPromptEditing(true);
                }}
                className="mt-1 text-[10px] text-primary/60 hover:text-primary transition-colors opacity-0 group-hover/prompt:opacity-100"
              >
                编辑提示词
              </button>
            )}
          </div>
        )}
      </div>

      <div className="mt-3 border-t border-border/20 pt-3">
        <p className="text-[10px] font-medium text-muted-foreground">实际生图提示词</p>
        <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground/60">
          该记录包含镜头上下文、参考图描述和服务端场景状态约束，只读。
        </p>
        {effectivePrompt ? (
          <Textarea
            value={effectivePrompt}
            readOnly
            className="mt-2 min-h-28 resize-none bg-muted/20 text-[10px] leading-relaxed"
          />
        ) : imageUrl ? (
          <p className="mt-2 text-[10px] text-muted-foreground/60">
            此图片生成于提示词审计启用前，未保存实际任务提示词。
          </p>
        ) : (
          <p className="mt-2 text-[10px] text-muted-foreground/60">
            提交生成后，这里会显示实际发送给图片模型的提示词。
          </p>
        )}
      </div>

      <FramePromptDialog
        key={`${item.id}-${frameType}-${promptOpen ? "open" : "closed"}`}
        open={promptOpen}
        title={`AI生成${label}`}
        defaultPrompt={defaultPrompt}
        submitting={submitting}
        onClose={() => setPromptOpen(false)}
        onConfirm={handleConfirmPrompt}
      />
    </div>
  );
}

export function StoryboardFrameReferenceDialog({
  open,
  item,
  project,
  initialFrameType = "first",
  onClose,
  onUpdateFrame,
  onUpdateFramePrompt,
  onGenerateFrame,
}: {
  open: boolean;
  item: StoryboardItem | null;
  project?: Project | null;
  initialFrameType?: StoryboardFrameType;
  onClose: () => void;
  onUpdateFrame?: FrameUpdateHandler;
  onUpdateFramePrompt?: FramePromptUpdateHandler;
  onGenerateFrame?: FrameGenerateHandler;
}) {
  const [activeFrameType, setActiveFrameType] =
    useState<StoryboardFrameType>(initialFrameType);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState("");

  const handlePreviewImage = useCallback((url: string, title: string) => {
    setPreviewImageUrl(url);
    setPreviewImageTitle(title);
  }, []);

  if (!open || !item) return null;

  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const activeImageUrl =
    activeFrameType === "first"
      ? item.firstFrameImageUrl
      : item.lastFrameImageUrl;
  const activePrompt =
    activeFrameType === "first" ? item.firstFramePrompt : item.lastFramePrompt;
  const activeEffectivePrompt =
    activeFrameType === "first"
      ? item.firstFrameEffectivePrompt
      : item.lastFrameEffectivePrompt;

  return (
    <>
      <div className="fixed inset-0 z-[9000] flex items-center justify-center p-4">
        <div
          className="absolute inset-0 bg-black/60 backdrop-blur-sm"
          onClick={onClose}
        />
        <div className="relative w-[720px] max-w-[96vw] h-[min(780px,calc(100vh-2rem))] max-h-[calc(100vh-2rem)] overflow-hidden rounded-xl border border-border/30 bg-card shadow-2xl flex flex-col">
          <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-border/20 shrink-0">
            <div className="min-w-0">
              <h3 className="text-sm font-semibold truncate">
                镜头 #{shotLabel} 首尾帧
              </h3>
              <p className="text-[10px] text-muted-foreground mt-0.5">
                设置该镜号视频生成时使用的首帧和尾帧参考
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="p-1.5 rounded-lg hover:bg-muted text-muted-foreground transition-colors shrink-0"
              title="关闭"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="px-5 pt-4 shrink-0">
            <div className="grid grid-cols-2 gap-2 rounded-lg bg-muted/25 p-1">
              {(["first", "last"] as StoryboardFrameType[]).map((frameType) => {
                const label = getFrameLabel(frameType);
                const hasFrame =
                  frameType === "first"
                    ? !!item.firstFrameImageUrl
                    : !!item.lastFrameImageUrl;
                const active = activeFrameType === frameType;
                return (
                  <button
                    key={frameType}
                    type="button"
                    onClick={() => setActiveFrameType(frameType)}
                    className={cn(
                      "h-9 rounded-md px-3 text-xs font-medium transition-all flex items-center justify-center gap-2",
                      active
                        ? "bg-background text-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground"
                    )}
                  >
                    <span>{label}</span>
                    <span
                      className={cn(
                        "h-1.5 w-1.5 rounded-full",
                        hasFrame ? "bg-emerald-500" : "bg-muted-foreground/30"
                      )}
                    />
                  </button>
                );
              })}
            </div>
          </div>

          <div className="p-5 overflow-y-auto min-h-0 flex-1">
            <FrameReferenceSection
              key={`${item.id}-${activeFrameType}`}
              item={item}
              project={project}
              frameType={activeFrameType}
              imageUrl={activeImageUrl}
              prompt={activePrompt}
              effectivePrompt={activeEffectivePrompt}
              className="border-t-0 pt-0"
              onUpdateFrame={onUpdateFrame}
              onUpdateFramePrompt={onUpdateFramePrompt}
              onGenerateFrame={onGenerateFrame}
              onGenerateSubmitted={onClose}
              onPreviewImage={handlePreviewImage}
            />
          </div>
        </div>
      </div>

      {previewImageUrl && (
        <div
          className="fixed inset-0 z-[10020] flex items-center justify-center bg-black/85 backdrop-blur-md p-4 animate-in fade-in duration-200"
          onClick={() => setPreviewImageUrl(null)}
        >
          <div
            className="relative max-w-[90vw] max-h-[90vh] flex flex-col items-center gap-3"
            onClick={(event) => event.stopPropagation()}
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
    </>
  );
}
