export interface PovVoiceOption {
  id: string;
  name: string;
}

// POV uses its own TTS allowlist so changes here cannot affect storyboard voice configuration.
// 音色来自 DashScope Qwen-Audio-TTS 系统音色列表
// qwen-audio-3.0-tts-plus 仅支持 longanlingxin / longanlufeng 两个系统音色
export const POV_VOICE_OPTIONS: PovVoiceOption[] = [
  { id: "longanlingxin", name: "龙安灵心 · 知心温暖女声（悬疑旁白）" },
  { id: "longanlufeng", name: "龙安鲁风 · 明亮开朗男声（探索解说）" },
];

export function getPovVoiceName(voiceId?: string, fallback?: string): string {
  return POV_VOICE_OPTIONS.find((option) => option.id === voiceId)?.name
    || fallback
    || voiceId
    || "未选择音色";
}
