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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Loader2, Mic } from "lucide-react";
import { toast } from "sonner";
import {
  storyboardApi,
  type VoiceConfig,
} from "@/lib/api/storyboard";
import { assetApi, type Asset } from "@/lib/api/asset";
import { resolveMediaUrl } from "@/lib/api/client";

const VOICE_PRESETS = [
  // 通用场景
  { id: "zh_female_xiaohe_uranus_bigtts", name: "小何 通用女声", group: "通用场景" },
  { id: "zh_female_vv_uranus_bigtts", name: "Vivi 通用女声", group: "通用场景" },
  { id: "zh_male_m191_uranus_bigtts", name: "云舟 通用男声", group: "通用场景" },
  { id: "zh_male_taocheng_uranus_bigtts", name: "小天 青年男声", group: "通用场景" },
  { id: "zh_female_qingxinnvsheng_uranus_bigtts", name: "清新女声", group: "通用场景" },
  { id: "zh_female_shuangkuaisisi_uranus_bigtts", name: "爽快思思", group: "通用场景" },
  { id: "zh_female_linjianvhai_uranus_bigtts", name: "邻家女孩", group: "通用场景" },
  { id: "zh_female_liuchangnv_uranus_bigtts", name: "流畅女声", group: "通用场景" },
  { id: "zh_male_ruyayichen_uranus_bigtts", name: "儒雅逸辰", group: "通用场景" },
  { id: "zh_male_shaonianzixin_uranus_bigtts", name: "少年梓辛", group: "通用场景" },
  // 角色扮演
  { id: "zh_female_cancan_uranus_bigtts", name: "灿灿 知性女声", group: "角色扮演" },
  { id: "zh_female_sajiaoxuemei_uranus_bigtts", name: "撒娇学妹", group: "角色扮演" },
  { id: "zh_female_meilinvyou_uranus_bigtts", name: "魅力女友", group: "角色扮演" },
  { id: "zh_female_tianmeixiaoyuan_uranus_bigtts", name: "甜美小源", group: "角色扮演" },
  { id: "zh_female_tianmeitaozi_uranus_bigtts", name: "甜美桃子", group: "角色扮演" },
  { id: "zh_male_dongfanghaoran_moon_bigtts", name: "东方浩然", group: "角色扮演" },
  { id: "zh_male_shenyeboke_moon_bigtts", name: "深夜播客", group: "角色扮演" },
  // 角色扮演 - ICL 音色
  { id: "ICL_zh_male_badaozongcai_v1_tob", name: "霸道总裁", group: "角色扮演" },
  { id: "ICL_zh_male_ruyazongcai_tob", name: "儒雅总裁", group: "角色扮演" },
  { id: "ICL_zh_male_shenchenzongcai_tob", name: "深沉总裁", group: "角色扮演" },
  { id: "ICL_zh_male_gaolengzongcai_tob", name: "高冷总裁", group: "角色扮演" },
  { id: "ICL_zh_male_lengaozongcai_tob", name: "冷傲总裁", group: "角色扮演" },
  { id: "ICL_zh_male_badaoshaoye_tob", name: "霸道少爷", group: "角色扮演" },
  { id: "ICL_zh_female_xiangliangya_v1_tob", name: "善良雅", group: "角色扮演" },
  { id: "ICL_zh_female_qiuling_v1_tob", name: "倾心少女", group: "角色扮演" },
  { id: "ICL_zh_female_yilin_v1_tob", name: "依林妹妹", group: "角色扮演" },
  // 多情感音色
  { id: "zh_female_gaolengyujie_emo_v2_mars_bigtts", name: "高冷御姐（多情感）", group: "多情感" },
  { id: "zh_male_aojiaobazong_emo_v2_mars_bigtts", name: "傲娇霸总（多情感）", group: "多情感" },
  { id: "zh_male_lengkugege_emo_v2_mars_bigtts", name: "冷酷哥哥（多情感）", group: "多情感" },
  { id: "zh_male_shenyeboke_emo_v2_mars_bigtts", name: "深夜播客（多情感）", group: "多情感" },
  // 视频配音
  { id: "zh_male_sunwukong_uranus_bigtts", name: "猴哥", group: "视频配音" },
  { id: "zh_male_dayi_uranus_bigtts", name: "大壹", group: "视频配音" },
  { id: "zh_female_jitangnv_uranus_bigtts", name: "鸡汤女", group: "视频配音" },
];

const EMOTION_OPTIONS = [
  { value: "neutral", label: "平静" },
  { value: "happy", label: "开心" },
  { value: "sad", label: "悲伤" },
  { value: "angry", label: "愤怒" },
  { value: "surprised", label: "惊讶" },
  { value: "fear", label: "恐惧" },
  { value: "hate", label: "厌恶" },
  { value: "excited", label: "激动" },
  { value: "coldness", label: "冷漠" },
];

interface VoiceConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: number;
  onSaved?: () => void;
}

export function VoiceConfigDialog({
  open,
  onOpenChange,
  projectId,
  onSaved,
}: VoiceConfigDialogProps) {
  const [characters, setCharacters] = useState<Asset[]>([]);
  const [selectedAssetId, setSelectedAssetId] = useState<number | null>(null);
  const [config, setConfig] = useState<VoiceConfig | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [generatingSample, setGeneratingSample] = useState(false);

  const loadCharacters = useCallback(async () => {
    try {
      const list = await assetApi.list(projectId, "character");
      setCharacters(list);
      if (list.length > 0 && selectedAssetId === null) {
        setSelectedAssetId(list[0].id);
      }
    } catch (err) {
      console.error("加载角色列表失败:", err);
    }
  }, [projectId, selectedAssetId]);

  const loadVoiceConfig = useCallback(async (assetId: number) => {
    setLoading(true);
    try {
      const vc = await storyboardApi.getVoiceConfig(assetId);
      setConfig(vc);
    } catch {
      setConfig(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      loadCharacters();
    }
  }, [open, loadCharacters]);

  useEffect(() => {
    if (selectedAssetId) {
      loadVoiceConfig(selectedAssetId);
    } else {
      setConfig(null);
    }
  }, [selectedAssetId, loadVoiceConfig]);

  const handleSave = async () => {
    if (!selectedAssetId || !config?.voiceId) {
      toast.error("请选择角色并填写音色ID");
      return;
    }
    setSaving(true);
    try {
      await storyboardApi.setVoiceConfig(selectedAssetId, config);
      toast.success("音色配置已保存");
      onSaved?.();
      onOpenChange(false);
    } catch (err) {
      toast.error("保存失败: " + (err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleGenerateSample = async () => {
    if (!selectedAssetId || !config?.voiceId) {
      toast.error("请先选择音色");
      return;
    }
    setGeneratingSample(true);
    try {
      // 先保存当前配置，再生成参考音频
      await storyboardApi.setVoiceConfig(selectedAssetId, config);
      const updated = await storyboardApi.generateSampleAudio(selectedAssetId);
      setConfig(updated);
      toast.success("参考音频已生成");
    } catch (err) {
      toast.error("生成参考音频失败: " + (err as Error).message);
    } finally {
      setGeneratingSample(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Mic className="h-4 w-4" />
            角色音色设置
          </DialogTitle>
          <DialogDescription>
            为每个角色配置 TTS 音色，配音时将自动匹配角色名。
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label>选择角色</Label>
            <Select
              value={selectedAssetId?.toString() ?? ""}
              onValueChange={(v) => setSelectedAssetId(Number(v))}
            >              <SelectTrigger>
                <SelectValue placeholder="选择角色" />
              </SelectTrigger>
              <SelectContent>
                {characters.map((c) => (
                  <SelectItem key={c.id} value={c.id.toString()}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <>
              <div className="flex flex-col gap-1.5">
                <Label>音色</Label>
                <Select
                  value={config?.voiceId ?? ""}
                  onValueChange={(v) =>
                    setConfig({
                      ...config!,
                      voiceId: v ?? "",
                      voiceName: VOICE_PRESETS.find((p) => p.id === v)?.name,
                    } as VoiceConfig)
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择音色">
                      {config?.voiceId
                        ? VOICE_PRESETS.find((p) => p.id === config.voiceId)?.name ?? config.voiceId
                        : "选择音色"}
                    </SelectValue>
                  </SelectTrigger>
                  <SelectContent>
                    {Object.entries(
                      VOICE_PRESETS.reduce<Record<string, typeof VOICE_PRESETS>>((acc, v) => {
                        (acc[v.group] ??= []).push(v);
                        return acc;
                      }, {})
                    ).map(([group, voices]) => (
                      <div key={group}>
                        <div className="px-2 py-1 text-xs font-medium text-muted-foreground sticky top-0 bg-popover z-10">{group}</div>
                        {voices.map((v) => (
                          <SelectItem key={v.id} value={v.id}>
                            {v.name}
                          </SelectItem>
                        ))}
                      </div>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>自定义音色 ID（可选，覆盖上方选择）</Label>
                <Input
                  value={config?.customVoiceId ?? ""}
                  onChange={(e) =>
                    setConfig({
                      ...config!,
                      customVoiceId: e.target.value || undefined,
                      voiceId: e.target.value || config!.voiceId,
                    } as VoiceConfig)
                  }
                  placeholder="如 S_xxxxxxxxx 或 saturn_xxx"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>语速（0.5 ~ 2.0，默认 1.0）</Label>
                <Input
                  type="number"
                  step="0.1"
                  min="0.5"
                  max="2.0"
                  value={config?.speed ?? 1.0}
                  onChange={(e) =>
                    setConfig({ ...config!, speed: Number(e.target.value) })
                  }
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>情绪</Label>
                <Select
                  value={config?.emotion ?? "neutral"}
                  onValueChange={(v) =>
                    setConfig({ ...config!, emotion: v ?? undefined } as VoiceConfig)
                  }
                >
                  <SelectTrigger>
                    <SelectValue>
                      {config?.emotion
                        ? EMOTION_OPTIONS.find((e) => e.value === config.emotion)?.label ?? config.emotion
                        : "平静"}
                    </SelectValue>
                  </SelectTrigger>
                  <SelectContent>
                    {EMOTION_OPTIONS.map((e) => (
                      <SelectItem key={e.value} value={e.value}>
                        {e.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>参考音频（用于视频模型音色克隆）</Label>
                <div className="flex gap-2">
                  <Input
                    value={config?.referenceAudioUrl ?? ""}
                    onChange={(e) =>
                      setConfig({ ...config!, referenceAudioUrl: e.target.value || undefined } as VoiceConfig)
                    }
                    placeholder="点击右侧按钮自动生成，或手动填写音频 URL"
                    className="flex-1"
                  />
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={handleGenerateSample}
                    disabled={generatingSample || !config?.voiceId}
                    className="shrink-0"
                  >
                    {generatingSample ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      "生成参考音频"
                    )}
                  </Button>
                </div>
                {config?.referenceAudioUrl && (
                  <audio src={resolveMediaUrl(config.referenceAudioUrl) ?? undefined} controls className="w-full h-8 mt-1" />
                )}
                <p className="text-xs text-muted-foreground">
                  点击「生成参考音频」会用当前音色合成一段样本音频，生成视频时自动传入进行音色克隆，无需后期配音。
                </p>
              </div>
            </>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            取消
          </Button>
          <Button onClick={handleSave} disabled={saving || !config?.voiceId}>
            {saving ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                保存中…
              </>
            ) : (
              "保存"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
