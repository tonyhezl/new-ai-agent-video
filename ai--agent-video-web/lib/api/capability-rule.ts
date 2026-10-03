import { http } from "./client";

// ========== 类型定义 ==========

export interface CapabilityRule {
  id: number;
  name: string;
  modelType: number;
  platform: string | null;
  codeContains: string | null;
  codeStartsWith: string | null;
  family: string | null;
  protocol: string | null;
  sortOrder: number;
  status: number;
  capabilityConfig: string;
  createTime: string;
  updateTime: string;
}

export interface CapabilityRuleCreateReq {
  name: string;
  modelType: number;
  platform?: string | null;
  codeContains?: string | null;
  codeStartsWith?: string | null;
  family?: string | null;
  protocol?: string | null;
  sortOrder?: number;
  status?: number;
  capabilityConfig: string;
}

export type CapabilityRuleUpdateReq = CapabilityRuleCreateReq & {
  id: number;
};

// ========== 常量 ==========

export const RULE_MODEL_TYPE_OPTIONS = [
  { value: 2, label: "图片生成" },
  { value: 3, label: "视频生成" },
] as const;

export const RULE_MODEL_TYPE_LABELS: Record<number, string> = {
  2: "图片生成",
  3: "视频生成",
};

// ========== API ==========

export const capabilityRuleApi = {
  list: (modelType?: number) =>
    http.get<never, CapabilityRule[]>(
      modelType !== undefined
        ? `/api/capability-rule/list?modelType=${modelType}`
        : `/api/capability-rule/list`
    ),

  get: (id: number) =>
    http.get<never, CapabilityRule>(`/api/capability-rule/get?id=${id}`),

  create: (data: CapabilityRuleCreateReq) =>
    http.post<never, number>("/api/capability-rule/create", data),

  update: (data: CapabilityRuleUpdateReq) =>
    http.put<never, boolean>("/api/capability-rule/update", data),

  delete: (id: number) =>
    http.delete<never, boolean>(`/api/capability-rule/delete?id=${id}`),
};
