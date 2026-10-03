import { http } from "./client";

// ========== 类型定义 ==========

/** 媒体条目 */
export interface MediaEntry {
  id: string;
  source: "storyboard" | "asset";
  mediaType: "image" | "video";
  url: string;
  thumbnailUrl: string | null;
  label: string;
  subLabel: string;
  projectId: number | null;
  projectName: string;
  createTime: string | null;
  entityId: number;
  field: string;
}

/** 媒体列表响应 */
export interface MediaListResp {
  records: MediaEntry[];
  total: number;
  page: number;
  size: number;
}

// ========== API ==========

export const mediaApi = {
  /** 查询媒体列表 */
  list: (params?: {
    projectId?: number;
    mediaType?: "image" | "video";
    source?: "storyboard" | "asset";
    page?: number;
    size?: number;
  }) => {
    const searchParams = new URLSearchParams();
    if (params?.projectId) searchParams.set("projectId", String(params.projectId));
    if (params?.mediaType) searchParams.set("mediaType", params.mediaType);
    if (params?.source) searchParams.set("source", params.source);
    searchParams.set("page", String(params?.page ?? 1));
    searchParams.set("size", String(params?.size ?? 60));
    return http.get<never, MediaListResp>(`/api/media/list?${searchParams.toString()}`);
  },

  /** 批量清除媒体 URL */
  batchClear: (entries: { source: string; entityId: number; field: string }[]) =>
    http.post<never, number>("/api/media/batch-clear", entries),
};
