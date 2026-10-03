import { http, API_BASE_URL } from "./client";
import { getGuestId } from "@/lib/guest";
import { authenticatedFetch } from "./ai-assistant";

// ========== 类型定义 ==========

export interface QuotaInfo {
  guest: boolean;
  guestId: string;
  freeQuotaTotal: number;
  freeQuotaRemaining: number;
  creditBalance: number;
  mustLogin: boolean;
}

export interface ModelPrice {
  ratio: string;
  creditCost: number;
  defaultPrice: boolean;
}

export interface QualityInfo {
  level: string;
  defaultRatio: string;
  prices: ModelPrice[];
}

export interface ModelInfo {
  id: number;
  code: string;
  name: string;
  freeModel: boolean;
  supportedAspectRatios?: string[];
  supportedQualities?: string[];
  qualities: QualityInfo[];
}

export interface ModelsResp {
  models: ModelInfo[];
}

export interface SubmitTaskReq {
  modelId: number;
  qualityLevel: string;
  aspectRatio: string;
  prompt: string;
  negativePrompt?: string;
}

export interface SubmitTaskResp {
  taskId: number;
  taskNo: string;
  creditCost: number;
  freeUsed: boolean;
  freeQuotaRemaining: number;
  creditBalance: number;
  sseEndpoint: string;
}

export interface ImageTask {
  id: number;
  taskNo: string;
  status: number;
  prompt: string;
  negativePrompt?: string;
  qualityLevel: string;
  aspectRatio: string;
  creditCost: number;
  errorMsg?: string;
  createTime?: string;
}

export interface ImageResult {
  id: number;
  taskId: number;
  imageUrl: string;
  sort: number;
}

// ========== SSE 流类型 ==========

export interface ImageStreamEvent {
  type: "status" | "result" | "done" | "error";
  status?: string;
  progress?: number;
  imageUrl?: string;
  taskId?: number;
  message?: string;
}

export interface ImageStreamCallbacks {
  onEvent: (event: ImageStreamEvent) => void;
  onError?: (err: Error) => void;
  onComplete?: () => void;
}

// ========== API 方法 ==========

function guestHeaders(): Record<string, string> {
  const guestId = getGuestId();
  return guestId ? { "X-Guest-Id": guestId } : {};
}

export async function getQuota(): Promise<QuotaInfo> {
  return http.get<never, QuotaInfo>("/api/image-generation/quota", {
    headers: guestHeaders(),
  });
}

export async function getModels(): Promise<ModelsResp> {
  return http.get<never, ModelsResp>("/api/image-generation/models");
}

export async function submitTask(req: SubmitTaskReq): Promise<SubmitTaskResp> {
  return http.post<never, SubmitTaskResp>("/api/image-generation/tasks", req, {
    headers: guestHeaders(),
    timeout: 60000,
  });
}

export async function getTask(taskId: number): Promise<ImageTask> {
  return http.get<never, ImageTask>(`/api/image-generation/tasks/${taskId}`, {
    headers: guestHeaders(),
  });
}

export async function getTaskResults(taskId: number): Promise<ImageResult[]> {
  return http.get<never, ImageResult[]>(
    `/api/image-generation/tasks/${taskId}/results`, {
    headers: guestHeaders(),
  });
}

// ========== 历史记录 ==========

export interface HistoryItem {
  taskId: number;
  prompt: string;
  negativePrompt?: string;
  aspectRatio: string;
  qualityLevel: string;
  modelId: number;
  imageUrl?: string;
  createTime?: string;
}

export async function getHistory(
  pageNo: number = 1,
  pageSize: number = 20
): Promise<HistoryItem[]> {
  return http.get<never, HistoryItem[]>(
    `/api/image-generation/history?pageNo=${pageNo}&pageSize=${pageSize}`,
    { headers: guestHeaders() }
  );
}

// ========== SSE 流 ==========

function parseImageSseBlock(block: string): ImageStreamEvent | null {
  let eventType = "status";
  const dataLines: string[] = [];

  for (const rawLine of block.split("\n")) {
    const line = rawLine.trimEnd();
    if (!line || line.startsWith(":")) continue;
    if (line.startsWith("event:")) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  const jsonStr = dataLines.join("\n").trim();
  if (!jsonStr) return null;

  try {
    const data = JSON.parse(jsonStr);
    return { type: eventType, ...data } as ImageStreamEvent;
  } catch {
    return null;
  }
}

export function streamImageTask(
  taskId: number,
  callbacks: ImageStreamCallbacks
): AbortController {
  const controller = new AbortController();

  (async () => {
    try {
      const url = `${API_BASE_URL}/api/image-generation/tasks/${taskId}/stream`;
      const response = await authenticatedFetch(url, {
        signal: controller.signal,
        headers: guestHeaders(),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error("无法获取响应流");

      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const normalized = buffer.replace(/\r\n/g, "\n");
        const blocks = normalized.split("\n\n");
        buffer = blocks.pop() || "";

        for (const block of blocks) {
          const event = parseImageSseBlock(block);
          if (event) {
            console.debug("[SSE] event:", event.type, event);
            callbacks.onEvent(event);
            if (event.type === "done" || event.type === "error") {
              controller.abort();
              callbacks.onComplete?.();
              return;
            }
          } else {
            console.debug("[SSE] failed to parse block:", block);
          }
        }
      }

      if (buffer.trim()) {
        const event = parseImageSseBlock(buffer);
        if (event) callbacks.onEvent(event);
      }

      callbacks.onComplete?.();
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      callbacks.onError?.(err instanceof Error ? err : new Error(String(err)));
    }
  })();

  return controller;
}
