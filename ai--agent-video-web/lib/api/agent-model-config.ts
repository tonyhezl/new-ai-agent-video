import { http } from "./client";

// ========== 类型定义 ==========

export interface AgentModelConfig {
  id?: number;
  agentType: string;
  agentName: string;
  modelId: number | null;
  modelName: string | null;
  modelCode: string | null;
  supportReasoning: boolean | null;
  supportVision: boolean | null;
  enableTools: boolean;
}

export interface AgentModelConfigBatchSaveReq {
  configs: Array<{
    agentType: string;
    modelId: number;
  }>;
}

// ========== API ==========

export const agentModelConfigApi = {
  /** 获取所有 Agent 及其模型绑定配置 */
  list: () =>
    http.get<never, AgentModelConfig[]>("/api/ai/agent-model-config/list"),

  /** 批量保存 Agent 模型绑定配置 */
  batchSave: (data: AgentModelConfigBatchSaveReq) =>
    http.post<never, boolean>("/api/ai/agent-model-config/batch-save", data),
};
