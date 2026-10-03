import { http } from "./client";
import type { PageResult } from "./types";

export interface PovShot {
  id: number;
  storyId: number;
  shotOrder: number;
  narrativeStage: "HOOK" | "EXPLORE" | "TWIST" | "CLIMAX" | string;
  voiceText: string;
  visualPrompt: string;
  videoPrompt?: string;
  negativePrompt?: string;
  cameraView?: string;
  cameraMovement?: string;
  sfxCue?: string;
  durationSec?: number;
  imageUrl?: string;
  videoUrl?: string;
  audioUrl?: string;
  status: "PENDING" | "GENERATING" | "COMPLETED" | "FAILED" | string;
  errorMessage?: string;
  videoStatus?: string;
  audioStatus?: string;
  assetRefs?: PovShotAssetRef[];
  speakers?: PovShotSpeaker[];
  createTime?: string;
}

export interface PovAssetItem {
  id: number;
  assetId: number;
  itemKey: string;
  itemType: "initial" | "variant" | string;
  basedOnItemId?: number;
  stateDescription?: string;
  imagePrompt: string;
  negativePrompt?: string;
  imageUrl?: string;
  status: "PENDING" | "GENERATING" | "READY" | "FAILED" | string;
  errorMessage?: string;
  sortOrder?: number;
}

export interface PovAsset {
  id: number;
  storyId: number;
  assetKey: string;
  assetRole: string;
  name: string;
  description?: string;
  characterNo?: number;
  voiceRequired?: boolean;
  voiceDescription?: string;
  voiceSampleText?: string;
  voiceConfig?: string;
  referenceAudioUrl?: string;
  voiceStatus?: string;
  voiceErrorMessage?: string;
  sortOrder?: number;
  items: PovAssetItem[];
}

export interface PovShotAssetRef {
  assetId: number;
  assetItemId: number;
  assetName: string;
  assetRole: string;
  itemKey: string;
  imageUrl?: string;
  referenceOrder: number;
  required: boolean;
}

export interface PovShotSpeaker {
  assetId: number;
  characterNo: number;
  characterName: string;
  dialogueText: string;
  delivery?: string;
  referenceAudioUrl?: string;
  sortOrder: number;
}

export interface PovAssetVoiceUpdateReq {
  voiceId: string;
  voiceName?: string;
  speed?: number;
  pitch?: number;
  emotion?: string;
  customVoiceId?: string;
  sampleText?: string;
  referenceAudioUrl?: string;
}

export interface PovStory {
  id: number;
  userId: number;
  projectId?: number;
  title: string;
  hookText?: string;
  topicGenre: "URBAN_MYSTERY" | "DEEP_EXPLORATION" | "SECRET_REVEAL" | "TECH_PARANOIA" | string;
  fullScript: string;
  totalDurationEstimate: number;
  voiceId?: string;
  bgmStyle?: string;
  status: "DRAFT" | "GENERATING" | "COMPLETED" | "FAILED" | string;
  finalVideoUrl?: string;
  errorMessage?: string;
  createTime: string;
  updateTime: string;
  shots?: PovShot[];
  assets?: PovAsset[];
}

export interface PovStoryPageReq {
  pageNo?: number;
  pageSize?: number;
  topicGenre?: string;
  status?: string;
  keyword?: string;
  projectId?: number;
}

export interface PovStoryGenerateReq {
  topic: string;
  topicGenre?: string;
  voiceId?: string;
  bgmStyle?: string;
  modelId?: number;
  projectId?: number;
}

export interface PovSaveFromAiReq {
  aiOutputText: string;
  projectId?: number;
  voiceId?: string;
  bgmStyle?: string;
}

export interface PovShotUpdateReq {
  voiceText?: string;
  visualPrompt?: string;
  negativePrompt?: string;
  cameraView?: string;
  sfxCue?: string;
  durationSec?: number;
  imageUrl?: string;
  videoUrl?: string;
  audioUrl?: string;
}

export interface PovStoryUpdateReq {
  title?: string;
  hookText?: string;
  fullScript?: string;
  voiceId?: string;
  bgmStyle?: string;
}

export const povStoryApi = {
  /** 分页获取故事列表 */
  page: (params: PovStoryPageReq) =>
    http.get<never, PageResult<PovStory>>("/api/pov/stories/page", { params }),

  /** 获取故事详情（包含全部镜头） */
  get: (id: number) =>
    http.get<never, PovStory>(`/api/pov/stories/${id}`),

  /** 从 AI 输出内容保存故事 */
  saveFromAi: (data: PovSaveFromAiReq) =>
    http.post<never, PovStory>("/api/pov/stories/save-from-ai", data),

  /** 更新故事基础信息 */
  update: (id: number, data: PovStoryUpdateReq) =>
    http.put<never, boolean>(`/api/pov/stories/${id}`, data),

  /** 删除故事 */
  delete: (id: number) =>
    http.delete<never, boolean>(`/api/pov/stories/${id}`),

  /** 更新单个分镜明细 */
  updateShot: (storyId: number, shotId: number, data: PovShotUpdateReq) =>
    http.put<never, boolean>(`/api/pov/stories/${storyId}/shots/${shotId}`, data),

  /** 使用故事资产直接生成单镜视频 */
  generateShotVideo: (storyId: number, shotId: number, modelId?: number) =>
    http.post<never, string>(`/api/pov/stories/${storyId}/shots/${shotId}/generate-video`, null, {
      params: { modelId },
    }),

  /** 获取故事独立资产 */
  listAssets: (storyId: number) =>
    http.get<never, PovAsset[]>(`/api/pov/stories/${storyId}/assets`),

  /** 生成单个资产图片 */
  generateAssetImage: (storyId: number, itemId: number, modelId?: number) =>
    http.post<never, string>(`/api/pov/stories/${storyId}/assets/items/${itemId}/generate-image`, null, {
      params: { modelId },
    }),

  /** 批量生成故事资产图片 */
  generateAllAssets: (storyId: number, modelId?: number) =>
    http.post<never, number>(`/api/pov/stories/${storyId}/assets/generate-all`, null, {
      params: { modelId },
    }),

  /** 保存故事内角色音色快照 */
  updateAssetVoice: (storyId: number, assetId: number, data: PovAssetVoiceUpdateReq) =>
    http.put<never, PovAsset>(`/api/pov/stories/${storyId}/assets/${assetId}/voice`, data),

  /** 生成角色参考音频 */
  generateReferenceAudio: (storyId: number, assetId: number, ttsModelId?: number) =>
    http.post<never, string>(`/api/pov/stories/${storyId}/assets/${assetId}/generate-reference-audio`, null, {
      params: { ttsModelId },
    }),

  /** 批量使用故事资产直接生成视频 */
  generateAllShots: (storyId: number, modelId?: number) =>
    http.post<never, number>(`/api/pov/stories/${storyId}/generate-all`, null, {
      params: { mediaType: "VIDEO", modelId },
    }),

  /** 一键合成最终 POV 沉浸式视频 */
  compose: (storyId: number) =>
    http.post<never, string>(`/api/pov/stories/${storyId}/compose`),
};
