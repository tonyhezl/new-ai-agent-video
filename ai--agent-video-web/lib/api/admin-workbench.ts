import { http } from "./client";
import type { PageResult } from "./types";

// ========== 模型价格 ==========

export interface ImageModelPrice {
  id: number;
  modelId: number;
  qualityLevel: string;
  aspectRatio: string;
  creditCost: number;
  upstreamCostCny: number;
  isDefault: number;
  status: number;
}

export interface PriceReqVO {
  modelId: number;
  qualityLevel?: string;
  aspectRatio?: string;
  creditCost: number;
  upstreamCostCny?: number;
  isDefault?: boolean;
}

export async function listModelPrices(modelId?: number): Promise<ImageModelPrice[]> {
  const params = modelId ? { modelId } : {};
  return http.get<never, ImageModelPrice[]>("/api/admin/image-model-prices", { params });
}

export async function createModelPrice(req: PriceReqVO): Promise<ImageModelPrice> {
  return http.post<never, ImageModelPrice>("/api/admin/image-model-prices", req);
}

export async function updateModelPrice(id: number, req: PriceReqVO): Promise<ImageModelPrice> {
  return http.put<never, ImageModelPrice>(`/api/admin/image-model-prices/${id}`, req);
}

export async function deleteModelPrice(id: number): Promise<boolean> {
  return http.delete<never, boolean>(`/api/admin/image-model-prices/${id}`);
}

// ========== 审核词库 ==========

export interface ModerationWord {
  id: number;
  word: string;
  normalized?: string;
  category?: string;
  matchMode: string;
  severity: number;
  enabled: number;
  remark?: string;
}

export interface ModerationWordReqVO {
  word: string;
  category?: string;
  matchMode?: string;
  severity?: number;
  remark?: string;
}

export interface ModerationHitLog {
  id: number;
  promptText?: string;
  hitWord?: string;
  hitCategory?: string;
  hitNormalized?: string;
  userId?: number;
  guestId?: string;
  clientIp?: string;
  createTime?: string;
}

export async function listModerationWords(params: {
  category?: string;
  enabled?: number;
  keyword?: string;
  pageNo?: number;
  pageSize?: number;
}): Promise<PageResult<ModerationWord>> {
  return http.get<never, PageResult<ModerationWord>>("/api/admin/moderation-words", { params });
}

export async function createModerationWord(req: ModerationWordReqVO): Promise<ModerationWord> {
  return http.post<never, ModerationWord>("/api/admin/moderation-words", req);
}

export async function updateModerationWord(id: number, req: ModerationWordReqVO): Promise<ModerationWord> {
  return http.put<never, ModerationWord>(`/api/admin/moderation-words/${id}`, req);
}

export async function enableModerationWord(id: number): Promise<boolean> {
  return http.post<never, boolean>(`/api/admin/moderation-words/${id}/enable`);
}

export async function disableModerationWord(id: number): Promise<boolean> {
  return http.post<never, boolean>(`/api/admin/moderation-words/${id}/disable`);
}

export async function reloadModerationWords(): Promise<boolean> {
  return http.post<never, boolean>("/api/admin/moderation-words/reload");
}

export async function listModerationHits(params: {
  category?: string;
  from?: string;
  to?: string;
  pageNo?: number;
  pageSize?: number;
}): Promise<PageResult<ModerationHitLog>> {
  return http.get<never, PageResult<ModerationHitLog>>("/api/admin/moderation-words/hits", { params });
}

// ========== 对账报表 ==========

export interface ReportOverview {
  totalRechargeCny: number;
  totalRechargeCredit: number;
  paidOrderCount: number;
  totalTasks: number;
  successTasks: number;
  failedTasks: number;
  freeTasks: number;
  paidTasks: number;
  totalRefundCredit: number;
  successRate: number;
  freeConversionRate: number;
  modelStats: Record<number, { total: number; success: number; free: number; paidCredit: number }>;
}

export interface CreditTransaction {
  id: number;
  userId: number;
  type: string;
  amount: number;
  balanceAfter: number;
  relatedId?: number;
  remark?: string;
  createTime?: string;
}

export async function getReportOverview(from?: string, to?: string): Promise<ReportOverview> {
  const params: Record<string, string> = {};
  if (from) params.from = from;
  if (to) params.to = to;
  return http.get<never, ReportOverview>("/api/admin/reports/overview", { params });
}

export async function getReportTransactions(params: {
  userId?: number;
  type?: string;
  pageNo?: number;
  pageSize?: number;
}): Promise<PageResult<CreditTransaction>> {
  return http.get<never, PageResult<CreditTransaction>>("/api/admin/reports/transactions", { params });
}
