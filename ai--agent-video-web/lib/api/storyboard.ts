import { http } from "./client";

// ========== 类型定义 ==========

/** 分镜脚本 */
export interface Storyboard {
  id: number;
  projectId: number;
  scriptId: number | null;
  title: string;
  description: string | null;
  customColumns: string | null;
  scope: number;
  ownerType: number;
  ownerId: number;
  totalDuration: number | null;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 分镜集合成状态: 0未开始 1合成中 2已完成 3失败 */
export type EpisodeComposeStatus = 0 | 1 | 2 | 3;

/** 分镜集 */
export interface StoryboardEpisode {
  id: number;
  storyboardId: number;
  scriptEpisodeId: number | null;
  episodeNumber: number | null;
  title: string | null;
  synopsis: string | null;
  sortOrder: number;
  status: number;
  composedVideoUrl: string | null;
  composeStatus: EpisodeComposeStatus;
  composeErrorMsg: string | null;
  composedAt: string | null;
  createTime: string;
  updateTime: string;
}

/** 分镜场次 */
export interface StoryboardScene {
  id: number;
  episodeId: number;
  storyboardId: number;
  sceneNumber: string | null;
  sceneHeading: string | null;
  location: string | null;
  timeOfDay: string | null;
  intExt: string | null;
  baseSceneAssetItemId: number | null;
  layoutDefinition: string | null;
  initialSceneState: string | null;
  shotStrategy: string | null;
  beats: string | null;
  stateTimelineVersion: number;
  sortOrder: number;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 分镜条目 */
export interface StoryboardItem {
  id: number;
  storyboardId: number;
  storyboardEpisodeId: number | null;
  storyboardSceneId: number | null;
  sortOrder: number;
  shotNumber: string | null;
  beatIds: string | null;
  autoShotNumber: string | null;
  imageUrl: string | null;
  referenceImageUrl: string | null;
  videoUrl: string | null;
  generatedImageUrl: string | null;
  firstFrameImageUrl: string | null;
  lastFrameImageUrl: string | null;
  firstFramePrompt: string | null;
  firstFrameEffectivePrompt: string | null;
  lastFramePrompt: string | null;
  lastFrameEffectivePrompt: string | null;
  generatedVideoUrl: string | null;
  videoPrompt: string | null;
  shotType: string | null;
  duration: number | null;
  content: string | null;
  sceneExpectation: string | null;
  sound: string | null;
  dialogue: string | null;
  soundEffect: string | null;
  music: string | null;
  cameraMovement: string | null;
  cameraAngle: string | null;
  cameraEquipment: string | null;
  focalLength: string | null;
  transition: string | null;
  characterIds: string | null;
  sceneAssetItemId: number | null;
  propIds: string | null;
  sceneStateBefore: string | null;
  sceneStateDelta: string | null;
  sceneStateAfter: string | null;
  stateBeforeHash: string | null;
  stateAfterHash: string | null;
  sceneStateHash: string | null;
  stateTimelineVersion: number;
  continuityMode: SceneContinuityMode;
  firstFrameStateHash: string | null;
  lastFrameStateHash: string | null;
  generatedVideoStateHash: string | null;
  remark: string | null;
  customData: string | null;
  aiGenerated: boolean;
  audioUrl: string | null;
  audioStatus: number;
  status: number;
  createTime: string;
  updateTime: string;
}

export type SceneContinuityMode = "CUT" | "SEAMLESS";

export type SceneStateOperation =
  | "UPSERT"
  | "ADD"
  | "MOVE"
  | "SET_STATE"
  | "SHOW"
  | "HIDE"
  | "REMOVE";

export type FixedFurnitureArrangement = "grid" | "row" | "line" | "cluster" | "custom";

export interface FixedFurnitureLayout {
  arrangement: FixedFurnitureArrangement | null;
  rows: number | null;
  columns: number | null;
  aisleDescription: string | null;
  empty: boolean;
  prohibitedFeatures: string[];
}

export interface SceneLayoutAnchor {
  id: string;
  name: string | null;
  kind: string | null;
  description: string | null;
  furnitureLayout: FixedFurnitureLayout | null;
  canContainObjects: boolean;
}

export interface SceneLayoutDefinition {
  anchors: SceneLayoutAnchor[];
}

export interface SceneObjectState {
  objectId: string;
  objectName: string;
  location: string | null;
  state: string | null;
  containerId: string | null;
  canContainObjects: boolean;
  visible: boolean;
}

export interface SceneStateSnapshot {
  objects: SceneObjectState[];
}

export interface SceneStateChange {
  operation: SceneStateOperation;
  objectId: string;
  objectName: string | null;
  location: string | null;
  state: string | null;
  containerId: string | null;
  canContainObjects: boolean | null;
  visible: boolean | null;
}

export interface SceneStateDelta {
  changes: SceneStateChange[];
}

export interface StoryboardShotSceneState {
  itemId: number;
  shotNumber: string | null;
  sortOrder: number;
  content: string | null;
  sceneAssetItemId: number | null;
  effectiveSceneAssetItemId: number | null;
  continuityMode: SceneContinuityMode;
  before: SceneStateSnapshot;
  delta: SceneStateDelta;
  after: SceneStateSnapshot;
  stateBeforeHash: string | null;
  stateAfterHash: string | null;
  sceneStateHash: string | null;
  firstFrameStale: boolean;
  lastFrameStale: boolean;
  videoStale: boolean;
}

export interface StoryboardSceneStateTimeline {
  sceneId: number;
  baseSceneAssetItemId: number | null;
  layoutDefinition: SceneLayoutDefinition;
  initialState: SceneStateSnapshot;
  timelineVersion: number;
  shots: StoryboardShotSceneState[];
}

export interface StoryboardSceneStateTimelineUpdateReq {
  baseSceneAssetItemId: number | null;
  layoutDefinition: SceneLayoutDefinition;
  initialState: SceneStateSnapshot;
  shots: Array<{
    itemId: number;
    sceneAssetItemId: number | null;
    continuityMode: SceneContinuityMode;
    delta: SceneStateDelta;
  }>;
}

// ========== 配音相关类型 ==========

/** 配音任务 */
export interface AudioTask {
  id: number;
  taskId: string;
  projectId: number;
  userId: number | null;
  storyboardId: number;
  storyboardEpisodeId: number;
  storyboardItemId: number;
  seq: number;
  text: string;
  characterName: string | null;
  characterAssetId: number | null;
  voiceConfig: string | null;
  dialogueType: string | null;
  modelId: number | null;
  modelCode: string | null;
  audioUrl: string | null;
  durationMs: number | null;
  sampleRate: number | null;
  status: number;
  errorMessage: string | null;
  retryCount: number;
  parentTaskId: string | null;
  createTime: string;
  updateTime: string;
}

/** 镜头对白预览 */
export interface ItemDialoguePreview {
  itemId: number;
  sceneId: number | null;
  shotNumber: string;
  audioStatus: number;
  audioUrl: string | null;
  lines: ItemDialogueLinePreview[];
}

/** 台词行预览 */
export interface ItemDialogueLinePreview {
  seq: number;
  characterName: string;
  text: string;
  dialogueType: string;
  characterAssetId: number | null;
  characterAssetName: string | null;
  voiceConfig: string | null;
  hasVoiceConfig: boolean;
  itemAudioStatus: number;
}

/** 角色音色配置 */
export interface VoiceConfig {
  voiceId: string;
  voiceName?: string;
  speed?: number;
  pitch?: number;
  emotion?: string;
  customVoiceId?: string;
  referenceAudioUrl?: string;
}

// ========== 请求类型 ==========

/** 创建分镜请求 */
export interface StoryboardCreateReq {
  projectId: number;
  scriptId?: number;
  title: string;
  description?: string;
}

/** 更新分镜请求 */
export interface StoryboardUpdateReq {
  id: number;
  title?: string;
  description?: string;
  status?: number;
}

/** 创建分镜集请求 */
export interface StoryboardEpisodeCreateReq {
  storyboardId: number;
  scriptEpisodeId?: number;
  episodeNumber?: number;
  title?: string;
  synopsis?: string;
  sortOrder?: number;
}

/** 更新分镜集请求 */
export interface StoryboardEpisodeUpdateReq {
  id: number;
  scriptEpisodeId?: number;
  episodeNumber?: number;
  title?: string;
  synopsis?: string;
  sortOrder?: number;
}

/** 创建分镜场次请求 */
export interface StoryboardSceneCreateReq {
  episodeId: number;
  storyboardId: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  shotStrategy?: string;
  beats?: string;
  sortOrder?: number;
}

/** 更新分镜场次请求 */
export interface StoryboardSceneUpdateReq {
  id: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  shotStrategy?: string;
  beats?: string;
  sortOrder?: number;
}

/** 创建分镜条目请求 */
export interface StoryboardItemCreateReq {
  storyboardId: number;
  storyboardEpisodeId?: number;
  storyboardSceneId?: number;
  shotNumber?: string;
  beatIds?: string;
  shotType?: string;
  content?: string;
  sceneExpectation?: string;
  dialogue?: string;
  soundEffect?: string;
  music?: string;
  cameraMovement?: string;
  cameraAngle?: string;
  transition?: string;
  duration?: number;
  sortOrder?: number;
  firstFrameImageUrl?: string | null;
  lastFrameImageUrl?: string | null;
  firstFramePrompt?: string | null;
  lastFramePrompt?: string | null;
  characterIds?: string | null;
  sceneAssetItemId?: number | null;
  propIds?: string | null;
}

/** 更新分镜条目请求 */
export interface StoryboardItemUpdateReq {
  id: number;
  shotNumber?: string;
  beatIds?: string;
  shotType?: string;
  content?: string;
  sceneExpectation?: string;
  dialogue?: string;
  soundEffect?: string;
  music?: string;
  cameraMovement?: string;
  cameraAngle?: string;
  transition?: string;
  duration?: number;
  sortOrder?: number;
  imageUrl?: string;
  firstFrameImageUrl?: string | null;
  lastFrameImageUrl?: string | null;
  firstFramePrompt?: string | null;
  lastFramePrompt?: string | null;
  videoPrompt?: string | null;
  status?: number;
  characterIds?: string | null;
  sceneAssetItemId?: number | null;
  propIds?: string | null;
}

/** 分镜首尾帧类型 */
export type StoryboardFrameType = "first" | "last";

/** 更新分镜首尾帧请求 */
export interface StoryboardFrameUpdateReq {
  frameType: StoryboardFrameType;
  imageUrl?: string | null;
  prompt?: string | null;
}

/** 合成视频段配置 */
export interface ComposeSegmentReq {
  itemId: number;
  startSec?: number | null;
  endSec?: number | null;
  /** 从上一段到此段的转场效果，仅对第 2 段及之后生效 */
  transition?: string | null;
  /** 转场持续时间（秒） */
  transitionDuration?: number | null;
}

/** 合成视频请求 */
export interface ComposeVideoReq {
  segments?: ComposeSegmentReq[] | null;
  transition?: string | null;
  transitionDuration?: number | null;
}

// ========== API ==========

export const storyboardApi = {
  // ========== 分镜脚本 ==========

  /** 获取分镜详情 */
  get: (id: number) => http.get<never, Storyboard>(`/api/storyboard/${id}`),

  /** 按项目查询分镜列表 */
  list: (projectId: number) =>
    http.get<never, Storyboard[]>(`/api/storyboard/list?projectId=${projectId}`),

  /** 创建分镜 */
  create: (data: StoryboardCreateReq) =>
    http.post<never, Storyboard>("/api/storyboard", data),

  /** 更新分镜 */
  update: (data: StoryboardUpdateReq) =>
    http.put<never, Storyboard>("/api/storyboard", data),

  /** 删除分镜 */
  delete: (id: number) => http.delete<never, boolean>(`/api/storyboard/${id}`),

  // ========== 分镜集 ==========

  /** 获取分镜集列表 */
  listEpisodes: (storyboardId: number) =>
    http.get<never, StoryboardEpisode[]>(`/api/storyboard/${storyboardId}/episodes`),

  /** 获取分镜集详情 */
  getEpisode: (id: number) =>
    http.get<never, StoryboardEpisode>(`/api/storyboard/episode/${id}`),

  /** 创建分镜集 */
  createEpisode: (data: StoryboardEpisodeCreateReq) =>
    http.post<never, StoryboardEpisode>("/api/storyboard/episode", data),

  /** 更新分镜集 */
  updateEpisode: (data: StoryboardEpisodeUpdateReq) =>
    http.put<never, StoryboardEpisode>("/api/storyboard/episode", data),

  /** 删除分镜集 */
  deleteEpisode: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/episode/${id}`),

  /** 绑定分镜集和剧本分集 */
  bindScriptEpisode: (id: number, scriptEpisodeId: number) =>
    http.put<never, StoryboardEpisode>(
      `/api/storyboard/episode/${id}/bindScriptEpisode`,
      { scriptEpisodeId }
    ),

  /** 清空分镜集下的场次和镜头 */
  clearEpisodeContent: (id: number) =>
    http.post<never, boolean>(`/api/storyboard/episode/${id}/clearContent`),

  /** 提交本集合成视频任务（异步），可选传入选配参数（含逐段转场） */
  composeEpisodeVideo: (episodeId: number, segments?: ComposeSegmentReq[]) =>
    http.post<never, string>(
      `/api/storyboard/episode/${episodeId}/compose-video`,
      segments ? { segments } : undefined,
    ),

  // ========== 分镜场次 ==========

  /** 按集获取分镜场次列表 */
  listScenesByEpisode: (episodeId: number) =>
    http.get<never, StoryboardScene[]>(`/api/storyboard/episode/${episodeId}/scenes`),

  /** 按分镜获取分镜场次列表 */
  listScenesByStoryboard: (storyboardId: number) =>
    http.get<never, StoryboardScene[]>(`/api/storyboard/${storyboardId}/scenes`),

  /** 获取分镜场次详情 */
  getScene: (id: number) =>
    http.get<never, StoryboardScene>(`/api/storyboard/scene/${id}`),

  /** 创建分镜场次 */
  createScene: (data: StoryboardSceneCreateReq) =>
    http.post<never, StoryboardScene>("/api/storyboard/scene", data),

  /** 更新分镜场次 */
  updateScene: (data: StoryboardSceneUpdateReq) =>
    http.put<never, StoryboardScene>("/api/storyboard/scene", data),

  /** 删除分镜场次 */
  deleteScene: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/scene/${id}`),

  /** 获取场次状态时间线 */
  getSceneStateTimeline: (sceneId: number) =>
    http.get<never, StoryboardSceneStateTimeline>(
      `/api/storyboard/scene/${sceneId}/state-timeline`
    ),

  /** 事务化更新场次状态时间线 */
  updateSceneStateTimeline: (
    sceneId: number,
    data: StoryboardSceneStateTimelineUpdateReq
  ) =>
    http.put<never, StoryboardSceneStateTimeline>(
      `/api/storyboard/scene/${sceneId}/state-timeline`,
      data
    ),

  /** 按现有镜头顺序重建场次状态时间线 */
  rebuildSceneStateTimeline: (sceneId: number) =>
    http.post<never, StoryboardSceneStateTimeline>(
      `/api/storyboard/scene/${sceneId}/state-timeline/rebuild`
    ),

  // ========== 分镜条目 ==========

  /** 获取分镜条目列表（按分镜） */
  listItems: (storyboardId: number) =>
    http.get<never, StoryboardItem[]>(`/api/storyboard/${storyboardId}/items`),

  /** 获取分镜条目列表（按场次） */
  listItemsByScene: (sceneId: number) =>
    http.get<never, StoryboardItem[]>(`/api/storyboard/scene/${sceneId}/items`),

  /** 获取分镜条目详情 */
  getItem: (id: number) =>
    http.get<never, StoryboardItem>(`/api/storyboard/item/${id}`),

  /** 创建分镜条目 */
  createItem: (data: StoryboardItemCreateReq) =>
    http.post<never, StoryboardItem>("/api/storyboard/item", data),

  /** 更新分镜条目 */
  updateItem: (data: StoryboardItemUpdateReq) =>
    http.put<never, StoryboardItem>("/api/storyboard/item", data),

  /** 更新分镜条目首尾帧 */
  updateFrame: (id: number, data: StoryboardFrameUpdateReq) =>
    http.put<never, StoryboardItem>(`/api/storyboard/item/${id}/updateFrame`, data),

  /** 复用上镜尾帧作为首帧（跳过 LLM 生图） */
  reusePrevFrame: (id: number) =>
    http.post<never, StoryboardItem>(`/api/storyboard/item/${id}/reuse-prev-frame`),

  /** 删除分镜条目 */
  deleteItem: (id: number) =>
    http.delete<never, boolean>(`/api/storyboard/item/${id}`),

  /** 批量创建分镜条目 */
  batchCreateItems: (storyboardId: number, items: StoryboardItemCreateReq[]) =>
    http.post<never, boolean>(`/api/storyboard/${storyboardId}/items/batch`, items),

  /** 批量更新分镜条目排序 */
  batchUpdateItemSort: (ids: number[]) =>
    http.post<never, boolean>("/api/storyboard/items/batch-sort", { ids }),

  // ========== 配音 ==========

  /** 批量生成配音（按分镜集，可指定镜头） */
  generateVoiceover: (storyboardId: number, episodeId: number, itemIds?: number[]) =>
    http.post<never, string>(`/api/storyboard/voiceover/${storyboardId}/generate`, { episodeId, itemIds }),

  /** 单条重生成 */
  regenerateVoiceover: (audioTaskId: number) =>
    http.post<never, string>(`/api/storyboard/voiceover/${audioTaskId}/regenerate`),

  /** 查询配音列表（按集） */
  listVoiceover: (episodeId: number) =>
    http.get<never, AudioTask[]>(`/api/storyboard/voiceover/list?episodeId=${episodeId}`),

  /** 预览配音台词（按分镜集/场次） */
  previewDialogue: (storyboardId: number, episodeId: number, sceneId?: number) =>
    http.get<never, ItemDialoguePreview[]>(`/api/storyboard/voiceover/${storyboardId}/preview?episodeId=${episodeId}${sceneId ? `&sceneId=${sceneId}` : ''}`),

  /** 获取角色音色配置 */
  getVoiceConfig: (assetId: number) =>
    http.get<never, VoiceConfig>(`/api/storyboard/voiceover/voice-config/${assetId}`),

  /** 设置角色音色配置 */
  setVoiceConfig: (assetId: number, config: VoiceConfig) =>
    http.post<never, VoiceConfig>(`/api/storyboard/voiceover/voice-config/${assetId}`, config),

  /** 生成参考音频样本（用当前音色合成一段样本音频） */
  generateSampleAudio: (assetId: number) =>
    http.post<never, VoiceConfig>(`/api/storyboard/voiceover/voice-config/${assetId}/generate-sample`),
};
