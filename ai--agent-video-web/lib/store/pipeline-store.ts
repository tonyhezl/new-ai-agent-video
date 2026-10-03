"use client";

import { create } from "zustand";
import {
  pipelineStream,
  cancelPipeline,
  type AiChatReq,
  type AiChatStreamEvent,
  type TokenUsageInfo,
} from "@/lib/api/ai-pipeline";
import {
  reconnectTaskStream,
  getTaskStreamStatus,
  listRunningTaskStreams,
} from "@/lib/api/task-stream";

// ========== 数据失效映射 ==========

/** 数据失效类型 */
export type InvalidationType = "assets" | "scripts" | "storyboards";

/** 工具名 → 影响的数据类型 */
const TOOL_INVALIDATION_MAP: Record<string, InvalidationType> = {
  // assets 相关的工具
  create_asset: "assets",
  add_asset_item: "assets",
  update_asset: "assets",
  batch_create_assets: "assets",
  batch_create_asset_items: "assets",
  update_asset_image: "assets",
  generate_image: "assets",

  // scripts 相关的工具
  save_script_episode: "scripts",
  save_script_scene_items: "scripts",
  update_script: "scripts",
  update_script_info: "scripts",
  manage_script_scenes: "scripts",
  update_script_scene: "scripts",
  update_script_scene_item: "scripts",
  manage_script_scene_items: "scripts",

  // storyboards 相关的工具
  save_storyboard_episode: "storyboards",
  save_storyboard_scene_shots: "storyboards",
  insert_storyboard_item: "storyboards",
  update_storyboard_item_video: "storyboards",
  update_storyboard_item_frame: "storyboards",
  generate_video: "storyboards",
};

// ========== 类型 ==========

/** 子 Agent 时间线中的元素 */
export type SubTimelineItem =
  | {
      type: "tool";
      id: string;
      name: string;
      arguments: string;
      status: "calling" | "done" | "error";
      result?: string;
    }
  | { type: "content"; text: string }
  | { type: "reasoning"; text: string; durationMs?: number }
  | { type: "token_usage"; inputTokens: number; outputTokens: number; totalTokens: number; modelCode?: string }
  | { type: "agent_model_usage"; agentType: string; agentName: string; modelId: number | null; modelCode: string | null; modelName: string | null; modelSource: string; subAgent: boolean; parentAgentType: string | null };

/** 时间线中的每个元素（与 agent-pipeline.tsx 一致） */
export type TimelineItem =
  | {
      type: "tool";
      id: string;
      name: string;
      arguments: string;
      status: "calling" | "done" | "error";
      result?: string;
      /** 如果此工具是子 Agent 调用，agentName 标识来源 */
      agentName?: string;
      /** 子 Agent 的嵌套时间线（推理、内容、工具调用） */
      children?: SubTimelineItem[];
    }
  | { type: "reasoning"; text: string; durationMs?: number }
  | { type: "content"; text: string }
  | { type: "token_usage"; inputTokens: number; outputTokens: number; totalTokens: number; modelCode?: string }
  | { type: "agent_model_usage"; agentType: string; agentName: string; modelId: number | null; modelCode: string | null; modelName: string | null; modelSource: string; subAgent: boolean; parentAgentType: string | null };

export interface PipelineState {
  status: "running" | "done" | "error" | "cancelled";
  reasoningText: string;
  reasoningDurationMs?: number;
  timeline: TimelineItem[];
  conversationId?: string;
  error?: string;
}

export interface PipelineTask {
  id: string;
  label: string;
  projectId: number;
  status: "running" | "done" | "error" | "cancelled";
  state: PipelineState;
  createdAt: number;
  cancellable?: boolean;
  /** 任务结束时间（done/error/cancelled 时记录） */
  finishedAt?: number;
}

// ========== Store ==========

interface PipelineStoreState {
  tasks: PipelineTask[];
  notificationOpen: boolean;
  /** 是否显示大面板（任务中心） */
  panelExpanded: boolean;
  /** 当前展开详情的 pipeline id */
  expandedTaskId: string | null;
  /** 是否已执行过自动重连 */
  reconnected: boolean;
  /** 数据失效计数器 —— 页面监听对应 key 触发刷新 */
  invalidation: Record<InvalidationType, number>;

  // actions
  addPipeline: (config: {
    label: string;
    projectId: number;
    request: AiChatReq;
    onComplete?: () => void;
  }) => string;
  attachTaskStream: (config: {
    label: string;
    projectId: number;
    taskId: string;
    cancellable?: boolean;
    onComplete?: () => void;
    onSettled?: (status: "done" | "error" | "cancelled") => void;
  }) => string;
  /**
   * 添加一个非 AI 任务（如视频合成），不走 SSE 流。
   * 调用方负责后续通过 markSimpleTask 推进状态。
   */
  addSimpleTask: (config: {
    label: string;
    projectId: number;
    initialNote?: string;
    onComplete?: () => void;
  }) => string;
  /** 推进非 AI 任务的状态（追加结果文本/错误，标记完成或失败） */
  markSimpleTask: (
    id: string,
    update: {
      status: "done" | "error";
      resultText?: string;
      errorText?: string;
    }
  ) => void;
  cancelPipeline: (id: string) => void;
  removePipeline: (id: string) => void;
  clearCompleted: () => void;
  setNotificationOpen: (open: boolean) => void;
  setPanelExpanded: (expanded: boolean) => void;
  setExpandedTaskId: (id: string | null) => void;
  /** 页面加载时调用：查询后端 running 对话并尝试 SSE 重连 */
  tryReconnect: () => void;
}

// 简单任务的完成回调（不放在 zustand state 里避免序列化问题）
const simpleTaskCallbacks = new Map<string, () => void>();

// 存储 AbortController 的 map（不放在 zustand state 里避免序列化问题）
const abortControllers = new Map<string, AbortController>();

// ========== 内存保护常量 ==========

/** 已完成任务保留上限，超过后自动清理最旧的 */
const MAX_COMPLETED_TASKS = 5;

/** 单个 task 的 timeline 条目上限，超过后截断最旧的（防止推理模型生成海量事件） */
const MAX_TIMELINE_ITEMS = 200;

/** reasoningText 最大字符数，超过后截断（推理模型可生成数十万字符） */
const MAX_REASONING_TEXT_LENGTH = 50_000;

/** 自动清理已完成任务的延时（毫秒） */
const AUTO_CLEANUP_DELAY = 10_000;

// 自动清理定时器引用
let autoCleanupTimer: ReturnType<typeof setTimeout> | null = null;

function isMainAgentTerminalEvent(event: AiChatStreamEvent): boolean {
  return !event.parentToolCallId && !event.agentName && (
    event.outputType === "DONE" ||
    event.outputType === "ERROR" ||
    event.outputType === "CANCELLED"
  );
}

let idCounter = 0;
function generateId(): string {
  return `pipeline-${Date.now()}-${++idCounter}`;
}

type ContentMergeMode = "stream" | "paragraph";

function mergeContentText(
  existingText: string,
  incomingText: string,
  mode: ContentMergeMode
): string {
  if (!existingText) return incomingText;
  if (!incomingText) return existingText;
  if (mode === "stream") {
    return existingText + incomingText;
  }
  if (existingText.endsWith("\n\n")) {
    return existingText + incomingText;
  }
  if (existingText.endsWith("\n")) {
    return `${existingText}\n${incomingText}`;
  }
  return `${existingText}\n\n${incomingText}`;
}

/**
 * 获取当前运行中 pipeline 的 conversationId 集合
 * 供 notification-panel 过滤历史列表使用
 */
export function getRunningConversationIds(): Set<string> {
  const tasks = usePipelineStore.getState().tasks;
  const ids = new Set<string>();
  for (const t of tasks) {
    if (t.status === "running" && t.state.conversationId) {
      ids.add(t.state.conversationId);
    }
  }
  return ids;
}

/**
 * 在 timeline 中找到指定 tool ID 的节点，并向其 children 追加子事件。
 * 如果找不到（重连场景下 TOOL_CALL 可能被 Redis Stream 裁剪），
 * 自动创建一个占位工具节点来承载后续子事件。
 */
function appendToToolChildren(
  timeline: TimelineItem[],
  parentToolCallId: string,
  updater: (children: SubTimelineItem[]) => SubTimelineItem[],
  fallbackAgentName?: string
): TimelineItem[] {
  const found = timeline.some(
    (item) => item.type === "tool" && item.id === parentToolCallId
  );

  if (!found) {
    // 容错：创建占位父工具节点（TOOL_CALL 事件已被裁剪）
    // 优先使用事件中的 agentName，避免显示 unknown_sub_agent
    const placeholder: TimelineItem = {
      type: "tool",
      id: parentToolCallId,
      name: fallbackAgentName || "子Agent（恢复中）",
      arguments: "",
      status: "calling",
      children: updater([]),
    };
    return [...timeline, placeholder];
  }

  return timeline.map((item) => {
    if (item.type === "tool" && item.id === parentToolCallId) {
      return {
        ...item,
        // 回填真实名称：如果当前是占位名且事件携带了 agentName，则替换
        ...(fallbackAgentName && (item.name === "unknown_sub_agent" || item.name === "子Agent（恢复中）")
          ? { name: fallbackAgentName }
          : {}),
        children: updater(item.children ?? []),
      };
    }
    return item;
  });
}

function updateToolStatus(
  timeline: TimelineItem[],
  toolCallId: string,
  status: "calling" | "done" | "error"
): TimelineItem[] {
  return timeline.map((item) =>
    item.type === "tool" && item.id === toolCallId
      ? { ...item, status }
      : item
  );
}

function appendReasoningToSubTimeline(
  children: SubTimelineItem[],
  reasoningContent: string
): SubTimelineItem[] {
  const last = children[children.length - 1];
  if (last && last.type === "reasoning") {
    return [
      ...children.slice(0, -1),
      { ...last, text: last.text + reasoningContent },
    ];
  }
  return [
    ...children,
    {
      type: "reasoning",
      text: reasoningContent,
    },
  ];
}

function updateLastSubTimelineReasoningDuration(
  children: SubTimelineItem[],
  durationMs: number
): SubTimelineItem[] {
  for (let index = children.length - 1; index >= 0; index--) {
    const item = children[index];
    if (item.type === "reasoning") {
      return children.map((child, childIndex) =>
        childIndex === index && child.type === "reasoning"
          ? { ...child, durationMs }
          : child
      );
    }
  }
  return children;
}

function appendReasoningToTimeline(
  timeline: TimelineItem[],
  reasoningContent: string
): TimelineItem[] {
  const last = timeline[timeline.length - 1];
  if (last && last.type === "reasoning") {
    return [
      ...timeline.slice(0, -1),
      { ...last, text: last.text + reasoningContent },
    ];
  }
  return [
    ...timeline,
    {
      type: "reasoning",
      text: reasoningContent,
    },
  ];
}

function updateLastTimelineReasoningDuration(
  timeline: TimelineItem[],
  durationMs: number
): TimelineItem[] {
  for (let index = timeline.length - 1; index >= 0; index--) {
    const item = timeline[index];
    if (item.type === "reasoning") {
      return timeline.map((timelineItem, timelineIndex) =>
        timelineIndex === index && timelineItem.type === "reasoning"
          ? { ...timelineItem, durationMs }
          : timelineItem
      );
    }
  }
  return timeline;
}

/** 截断 timeline，保留最新的 N 条，防止无限增长 */
function truncateTimeline<T>(timeline: T[]): T[] {
  if (timeline.length <= MAX_TIMELINE_ITEMS) return timeline;
  return timeline.slice(timeline.length - MAX_TIMELINE_ITEMS);
}

/** 截断 reasoningText，保留最新的部分 */
function truncateReasoningText(text: string): string {
  if (text.length <= MAX_REASONING_TEXT_LENGTH) return text;
  return text.slice(text.length - MAX_REASONING_TEXT_LENGTH);
}

/** 调度自动清理已完成任务 */
function scheduleAutoCleanup(set: (fn: (s: PipelineStoreState) => Partial<PipelineStoreState>) => void, get: () => PipelineStoreState) {
  if (autoCleanupTimer) {
    clearTimeout(autoCleanupTimer);
    autoCleanupTimer = null;
  }
  autoCleanupTimer = setTimeout(() => {
    autoCleanupTimer = null;
    const state = get();
    const completedTasks = state.tasks.filter((t) => t.status !== "running");
    if (completedTasks.length > MAX_COMPLETED_TASKS) {
      const toRemove = completedTasks
        .sort((a, b) => (a.finishedAt ?? a.createdAt) - (b.finishedAt ?? b.createdAt))
        .slice(0, completedTasks.length - MAX_COMPLETED_TASKS);
      const removeIds = new Set(toRemove.map((t) => t.id));
      for (const id of removeIds) {
        abortControllers.delete(id);
        simpleTaskCallbacks.delete(id);
      }
      set((s) => ({
        tasks: s.tasks.filter((t) => !removeIds.has(t.id)),
        expandedTaskId:
          s.expandedTaskId && removeIds.has(s.expandedTaskId) ? null : s.expandedTaskId,
      }));
    }
  }, AUTO_CLEANUP_DELAY);
}

/** 处理 SSE 事件的通用逻辑（支持子 Agent 嵌套） */
function createEventHandler(
  id: string,
  set: (fn: (s: PipelineStoreState) => Partial<PipelineStoreState>) => void,
  get: () => PipelineStoreState,
  onComplete?: () => void,
  onSettled?: (status: "done" | "error" | "cancelled") => void,
  contentMergeMode: ContentMergeMode = "stream"
) {
  // 事件队列 + 定时节流，避免高频 set() 导致 Maximum update depth exceeded
  const eventQueue: AiChatStreamEvent[] = [];
  let flushScheduled = false;
  const FLUSH_INTERVAL = 100; // ms — 比 rAF(~16ms) 慢，但能批量处理更多事件

  function flushEvents() {
    flushScheduled = false;
    const batch = eventQueue.splice(0);
    if (batch.length === 0) return;

    // 收集本批次需要触发的 invalidation 类型
    const invalidations: InvalidationType[] = [];
    let hasTerminalEvent = false;
    for (const event of batch) {
      if (isMainAgentTerminalEvent(event)) {
        hasTerminalEvent = true;
      }
    }


    set((s) => {
      const tasks = s.tasks.map((t) => {
        if (t.id !== id) return t;

        const next: PipelineState = {
          ...t.state,
          timeline: [...t.state.timeline],
        };

        for (const event of batch) {
          if (event.conversationId) {
            next.conversationId = event.conversationId;
          }

          const isSubAgent = !!event.parentToolCallId;

          switch (event.outputType) {
            case "REASONING":
              if (event.reasoningContent) {
                if (isSubAgent) {
                  next.timeline = appendToToolChildren(
                    next.timeline,
                    event.parentToolCallId!,
                    (children) =>
                      appendReasoningToSubTimeline(
                        children,
                        event.reasoningContent!
                      ),
                    event.agentName || undefined
                  );
                } else {
                  next.reasoningText += event.reasoningContent;
                  next.timeline = appendReasoningToTimeline(
                    next.timeline,
                    event.reasoningContent
                  );
                }
              }
              break;

            case "CONTENT":
              if (event.reasoningDurationMs && !isSubAgent) {
                next.reasoningDurationMs = event.reasoningDurationMs;
                next.timeline = updateLastTimelineReasoningDuration(
                  next.timeline,
                  event.reasoningDurationMs
                );
              }
              if (event.content) {
                const content = event.content;
                if (isSubAgent) {
                  next.timeline = appendToToolChildren(
                    next.timeline,
                    event.parentToolCallId!,
                    (children) => {
                      let updatedChildren = [...children];
                      if (event.reasoningDurationMs) {
                        updatedChildren = updateLastSubTimelineReasoningDuration(
                          updatedChildren,
                          event.reasoningDurationMs
                        );
                      }

                      const last =
                        updatedChildren[updatedChildren.length - 1];
                      if (last && last.type === "content") {
                        return [
                          ...updatedChildren.slice(0, -1),
                          {
                            ...last,
                            text: mergeContentText(
                              last.text,
                              content,
                              contentMergeMode
                            ),
                          },
                        ];
                      }
                      return [
                        ...updatedChildren,
                        { type: "content" as const, text: content },
                      ];
                    }
                  );
                } else {
                  const last = next.timeline[next.timeline.length - 1];
                  if (last && last.type === "content") {
                    next.timeline[next.timeline.length - 1] = {
                      ...last,
                      text: mergeContentText(
                        last.text,
                        content,
                        contentMergeMode
                      ),
                    };
                  } else {
                    next.timeline.push({
                      type: "content",
                      text: content,
                    });
                  }
                }
              }
              break;

            case "TOOL_CALL":
              if (event.toolCalls) {
                for (const tc of event.toolCalls) {
                  if (isSubAgent) {
                    next.timeline = appendToToolChildren(
                      next.timeline,
                      event.parentToolCallId!,
                      (children) => {
                        const exists = children.some(
                          (c) => c.type === "tool" && c.id === tc.id
                        );
                        if (exists) return children;
                        return [
                          ...children,
                          {
                            type: "tool" as const,
                            id: tc.id,
                            name: tc.name,
                            arguments: tc.arguments,
                            status: "calling" as const,
                          },
                        ];
                      }
                    );
                  } else {
                    const exists = next.timeline.some(
                      (item) => item.type === "tool" && item.id === tc.id
                    );
                    if (!exists) {
                      next.timeline.push({
                        type: "tool",
                        id: tc.id,
                        name: tc.name,
                        arguments: tc.arguments,
                        status: "calling",
                        agentName: event.agentName,
                      });
                    }
                  }
                }
              }
              break;

            case "TOOL_FINISHED":
              if (event.toolCallId) {
                const toolStatus =
                  event.toolStatus === "error"
                    ? ("error" as const)
                    : ("done" as const);

                if (isSubAgent) {
                  next.timeline = appendToToolChildren(
                    next.timeline,
                    event.parentToolCallId!,
                    (children) =>
                      children.map((c) =>
                        c.type === "tool" && c.id === event.toolCallId
                          ? {
                              ...c,
                              status: toolStatus,
                              result: event.toolResult,
                            }
                          : c
                      ),
                    event.agentName || undefined
                  );
                } else {
                  const exists = next.timeline.some(
                    (item) =>
                      item.type === "tool" && item.id === event.toolCallId
                  );
                  if (exists) {
                    next.timeline = next.timeline.map((item) =>
                      item.type === "tool" && item.id === event.toolCallId
                        ? {
                            ...item,
                            status: toolStatus,
                            result: event.toolResult,
                            // 补充工具名（占位节点可能为 unknown_sub_agent）
                            ...(event.toolName ? { name: event.toolName } : {}),
                          }
                        : item
                    );
                  } else if (event.toolName) {
                    // 容错：TOOL_CALL 已被裁剪，补创建已完成工具节点
                    next.timeline.push({
                      type: "tool",
                      id: event.toolCallId,
                      name: event.toolName,
                      arguments: "",
                      status: toolStatus,
                      result: event.toolResult,
                    });
                  }
                }
              }
              // 收集 invalidation
              if (
                event.toolName &&
                event.toolStatus !== "error" &&
                TOOL_INVALIDATION_MAP[event.toolName]
              ) {
                invalidations.push(TOOL_INVALIDATION_MAP[event.toolName]);
              }
              break;

            case "SUB_AGENT_FINISHED":
              if (isSubAgent) {
                // 回填真实 agentName 到占位节点
                const agentName = event.agentName || undefined;
                next.timeline = next.timeline.map((item) =>
                  item.type === "tool" && item.id === event.parentToolCallId
                    ? {
                        ...item,
                        status: "done" as const,
                        ...(agentName && (item.name === "unknown_sub_agent" || item.name === "子Agent（恢复中）")
                          ? { name: agentName }
                          : {}),
                      }
                    : item
                );
              }
              break;

            case "TOKEN_USAGE":
              if (event.tokenUsage) {
                const tu = event.tokenUsage;
                const usageItem = {
                  type: "token_usage" as const,
                  inputTokens: tu.inputTokens,
                  outputTokens: tu.outputTokens,
                  totalTokens: tu.totalTokens,
                  modelCode: tu.modelCode,
                };
                if (isSubAgent) {
                  next.timeline = appendToToolChildren(
                    next.timeline,
                    event.parentToolCallId!,
                    (children) => [...children, usageItem],
                    event.agentName || undefined
                  );
                } else {
                  next.timeline.push(usageItem);
                }
              }
              break;

            case "AGENT_MODEL_USAGE":
              if (event.agentModelUsage) {
                const amu = event.agentModelUsage;
                const modelUsageItem = {
                  type: "agent_model_usage" as const,
                  agentType: amu.agentType,
                  agentName: amu.agentName,
                  modelId: amu.modelId,
                  modelCode: amu.modelCode,
                  modelName: amu.modelName,
                  modelSource: amu.modelSource,
                  subAgent: amu.subAgent,
                  parentAgentType: amu.parentAgentType,
                };
                if (isSubAgent) {
                  next.timeline = appendToToolChildren(
                    next.timeline,
                    event.parentToolCallId!,
                    (children) => [...children, modelUsageItem],
                    event.agentName || undefined
                  );
                } else {
                  next.timeline.push(modelUsageItem);
                }
              }
              break;

            case "DONE":
              next.status = "done";
              if (event.content) {
                const last = next.timeline[next.timeline.length - 1];
                if (last && last.type === "content") {
                  next.timeline[next.timeline.length - 1] = {
                    ...last,
                    text: mergeContentText(
                      last.text,
                      event.content,
                      contentMergeMode
                    ),
                  };
                } else {
                  next.timeline.push({ type: "content", text: event.content });
                }
              }
              break;

            case "ERROR":
              if (isSubAgent) {
                const agentName = event.agentName || undefined;
                // 回填真实 agentName 并标记 error
                next.timeline = next.timeline.map((item) =>
                  item.type === "tool" && item.id === event.parentToolCallId
                    ? {
                        ...item,
                        status: "error" as const,
                        ...(agentName && (item.name === "unknown_sub_agent" || item.name === "子Agent（恢复中）")
                          ? { name: agentName }
                          : {}),
                      }
                    : item
                );
                next.timeline = appendToToolChildren(
                  next.timeline,
                  event.parentToolCallId!,
                  (children) => [
                    ...children,
                    {
                      type: "content" as const,
                        text: `${event.agentName || "子Agent"} 出错：${event.error || "未知错误"}`,
                    },
                  ],
                  agentName
                );
              } else if (event.agentName) {
                next.timeline.push({
                  type: "content",
                  text: `${event.agentName} 出错：${event.error || "未知错误"}`,
                });
              } else {
                next.status = "error";
                next.error = event.error || "未知错误";
              }
              break;

            case "CANCELLED":
              next.status = "cancelled";
              break;
          }
        } // end for batch

        // 内存保护：截断 timeline 和 reasoningText
        next.timeline = truncateTimeline(next.timeline);
        next.reasoningText = truncateReasoningText(next.reasoningText);

        const newStatus: PipelineTask["status"] =
          next.status === "done"
            ? "done"
            : next.status === "error"
              ? "error"
              : next.status === "cancelled"
                ? "cancelled"
                : "running";

        const isFinished = newStatus !== "running" && t.status === "running";
        return {
          ...t,
          status: newStatus,
          state: next,
          ...(isFinished ? { finishedAt: Date.now() } : {}),
        };
      });

      // 仅在有实际 invalidation 时才创建新对象，避免无谓的重渲染
      const hasInvalidations = hasTerminalEvent || invalidations.length > 0;
      if (hasInvalidations) {
        const newInvalidation = { ...s.invalidation };
        if (hasTerminalEvent) {
          newInvalidation.assets = (newInvalidation.assets || 0) + 1;
          newInvalidation.scripts = (newInvalidation.scripts || 0) + 1;
          newInvalidation.storyboards = (newInvalidation.storyboards || 0) + 1;
        }
        for (const inv of invalidations) {
          newInvalidation[inv] = (newInvalidation[inv] || 0) + 1;
        }
        return { tasks, invalidation: newInvalidation };
      }

      return { tasks };
    });

    // 完成/错误/取消时触发后续回调
    for (const event of batch) {
      if (event.outputType === "DONE" && isMainAgentTerminalEvent(event)) {
        abortControllers.delete(id);
        onComplete?.();
        onSettled?.("done");
        scheduleAutoCleanup(set, get);
      }
      if (
        (event.outputType === "ERROR" || event.outputType === "CANCELLED") &&
        isMainAgentTerminalEvent(event)
      ) {
        abortControllers.delete(id);
        onSettled?.(
          event.outputType === "CANCELLED" ? "cancelled" : "error"
        );
        scheduleAutoCleanup(set, get);
      }
    }
  }

  return (event: AiChatStreamEvent) => {
    eventQueue.push(event);
    if (!flushScheduled) {
      flushScheduled = true;
      setTimeout(flushEvents, FLUSH_INTERVAL);
    }
  };
}

function settleTaskIfRunning(
  set: (fn: (s: PipelineStoreState) => Partial<PipelineStoreState>) => void,
  get: () => PipelineStoreState,
  id: string,
  status: "done" | "error" | "cancelled",
  options?: {
    error?: string;
    onComplete?: () => void;
    onSettled?: (status: "done" | "error" | "cancelled") => void;
  }
) {
  let transitioned = false;
  set((s) => {
    const nextTasks = s.tasks.map((t) => {
      if (t.id !== id || t.status !== "running") {
        return t;
      }
      transitioned = true;
      return {
        ...t,
        status,
        finishedAt: Date.now(),
        state: {
          ...t.state,
          status,
          ...(status === "error"
            ? { error: options?.error || t.state.error || "任务失败" }
            : {}),
        },
      };
    });

    if (transitioned) {
      return {
        tasks: nextTasks,
        invalidation: {
          assets: (s.invalidation.assets || 0) + 1,
          scripts: (s.invalidation.scripts || 0) + 1,
          storyboards: (s.invalidation.storyboards || 0) + 1,
        },
      };
    }
    return { tasks: nextTasks };
  });
  abortControllers.delete(id);
  if (transitioned) {
    if (status === "done") {
      options?.onComplete?.();
    }
    options?.onSettled?.(status);
    scheduleAutoCleanup(set, get);
  }
}

export const usePipelineStore = create<PipelineStoreState>()((set, get) => ({
  tasks: [],
  notificationOpen: false,
  panelExpanded: false,
  expandedTaskId: null,
  reconnected: false,
  invalidation: { assets: 0, scripts: 0, storyboards: 0 },

  addSimpleTask: ({ label, projectId, initialNote, onComplete }) => {
    const id = generateId();
    const initialState: PipelineState = {
      status: "running",
      reasoningText: "",
      timeline: initialNote ? [{ type: "content", text: initialNote }] : [],
    };
    const task: PipelineTask = {
      id,
      label,
      projectId,
      status: "running",
      state: initialState,
      createdAt: Date.now(),
      cancellable: false,
    };
    set((s) => ({ tasks: [...s.tasks, task] }));
    if (onComplete) {
      simpleTaskCallbacks.set(id, onComplete);
    }
    return id;
  },

  markSimpleTask: (id, update) => {
    let finished = false;
    set((s) => {
      const nextTasks = s.tasks.map((t) => {
        if (t.id !== id) return t;
        const isFinishingFromRunning = t.status === "running";
        if (isFinishingFromRunning) finished = true;
        const newTimeline = [...t.state.timeline];
        if (update.resultText) {
          newTimeline.push({ type: "content", text: update.resultText });
        }
        return {
          ...t,
          status: update.status,
          state: {
            ...t.state,
            status: update.status,
            timeline: newTimeline,
            error: update.errorText,
          },
          ...(isFinishingFromRunning ? { finishedAt: Date.now() } : {}),
        };
      });

      if (finished) {
        return {
          tasks: nextTasks,
          invalidation: {
            assets: (s.invalidation.assets || 0) + 1,
            scripts: (s.invalidation.scripts || 0) + 1,
            storyboards: (s.invalidation.storyboards || 0) + 1,
          },
        };
      }
      return { tasks: nextTasks };
    });
    if (update.status === "done") {
      const cb = simpleTaskCallbacks.get(id);
      if (cb) {
        try {
          cb();
        } catch (e) {
          console.error("[simpleTask] onComplete error", e);
        }
        simpleTaskCallbacks.delete(id);
      }
    } else {
      simpleTaskCallbacks.delete(id);
    }
    if (finished) {
      scheduleAutoCleanup(set, get);
    }
  },

  addPipeline: ({ label, projectId, request, onComplete }) => {
    const id = generateId();
    const initialState: PipelineState = {
      status: "running",
      reasoningText: "",
      timeline: [],
    };

    const task: PipelineTask = {
      id,
      label,
      projectId,
      status: "running",
      state: initialState,
      createdAt: Date.now(),
      cancellable: true,
    };

    set((s) => ({ tasks: [...s.tasks, task] }));

    // onComplete 可能从两条路径触发：
    //   1. createEventHandler 的 flushEvents 处理 DONE 事件（有 100ms debounce 延迟）
    //   2. pipelineStream 的 onComplete（SSE 流关闭时立即触发）
    // 用闭包级 once 守卫确保只触发一次，避免竞态导致重复创建后续 pipeline
    let onCompleteFired = false;
    const fireOnComplete = () => {
      if (onCompleteFired) return;
      onCompleteFired = true;
      onComplete?.();
    };

    const handleEvent = createEventHandler(id, set, get, fireOnComplete);

    // 启动 SSE 流
    const controller = pipelineStream(request, {
      onEvent: handleEvent,
      onError: (err) => {
        // 仅在 running 状态时标记错误，避免覆盖已取消/已完成的状态
        set((s) => ({
          tasks: s.tasks.map((t) =>
            t.id === id && t.status === "running"
              ? {
                  ...t,
                  status: "error" as const,
                  finishedAt: Date.now(),
                  state: {
                    ...t.state,
                    status: "error" as const,
                    error: err.message,
                  },
                }
              : t
          ),
        }));
        abortControllers.delete(id);
        scheduleAutoCleanup(set, get);
      },
      onComplete: () => {
        // SSE 流结束，如果还在 running 则标记为 done
        set((s) => ({
          tasks: s.tasks.map((t) =>
            t.id === id && t.status === "running"
              ? {
                  ...t,
                  status: "done" as const,
                  finishedAt: Date.now(),
                  state: { ...t.state, status: "done" as const },
                }
              : t
          ),
        }));
        abortControllers.delete(id);
        fireOnComplete();
        scheduleAutoCleanup(set, get);
      },
    });

    abortControllers.set(id, controller);

    return id;
  },

  attachTaskStream: ({
    label,
    projectId,
    taskId,
    cancellable = false,
    onComplete,
    onSettled,
  }) => {
    const id = generateId();
    const task: PipelineTask = {
      id,
      label,
      projectId,
      status: "running",
      state: {
        status: "running",
        reasoningText: "",
        timeline: [{ type: "content", text: "正在连接任务流……" }],
        conversationId: taskId,
      },
      createdAt: Date.now(),
      cancellable,
    };

    set((s) => ({ tasks: [...s.tasks, task] }));

    void (async () => {
      try {
        const streamStatus = await getTaskStreamStatus(taskId);
        if (streamStatus === "NONE") {
          settleTaskIfRunning(set, get, id, "error", {
            error: "任务流不存在",
            onSettled,
          });
          return;
        }

        const handleEvent = createEventHandler(
          id,
          set,
          get,
          onComplete,
          onSettled,
          "paragraph"
        );

        set((s) => ({
          tasks: s.tasks.map((t) =>
            t.id === id
              ? { ...t, state: { ...t.state, timeline: [] } }
              : t
          ),
        }));

        const controller = reconnectTaskStream(taskId, {
          onEvent: handleEvent,
          onError: (err) => {
            settleTaskIfRunning(set, get, id, "error", {
              error: err.message,
              onSettled,
            });
          },
          onComplete: () => {
            const fallbackStatus =
              streamStatus === "ERROR"
                ? "error"
                : streamStatus === "COMPLETED"
                  ? "done"
                  : "done";
            settleTaskIfRunning(set, get, id, fallbackStatus, {
              onComplete,
              onSettled,
            });
          },
        });

        abortControllers.set(id, controller);
      } catch (err) {
        settleTaskIfRunning(set, get, id, "error", {
          error: err instanceof Error ? err.message : "连接任务流失败",
          onSettled,
        });
      }
    })();

    return id;
  },

  cancelPipeline: async (id: string) => {
    const task = get().tasks.find((t) => t.id === id);
    const controller = abortControllers.get(id);

    // 先标记为 cancelled，防止 abort 触发的 onError/onComplete 回调覆盖状态
    set((s) => ({
      tasks: s.tasks.map((t) =>
        t.id === id
          ? {
              ...t,
              status: "cancelled" as const,
              finishedAt: Date.now(),
              state: { ...t.state, status: "cancelled" as const },
            }
          : t
      ),
      invalidation: {
        assets: (s.invalidation.assets || 0) + 1,
        scripts: (s.invalidation.scripts || 0) + 1,
        storyboards: (s.invalidation.storyboards || 0) + 1,
      },
    }));

    controller?.abort();
    abortControllers.delete(id);
    scheduleAutoCleanup(set, get);

    if (task?.state.conversationId) {
      try {
        await cancelPipeline(task.state.conversationId);
      } catch {
        // 忽略取消错误
      }
    }
  },

  removePipeline: (id: string) => {
    abortControllers.get(id)?.abort();
    abortControllers.delete(id);
    simpleTaskCallbacks.delete(id);
    set((s) => ({
      tasks: s.tasks.filter((t) => t.id !== id),
      expandedTaskId: s.expandedTaskId === id ? null : s.expandedTaskId,
    }));
  },

  clearCompleted: () => {
    const removableIds = get().tasks
      .filter((t) => t.status !== "running")
      .map((t) => t.id);
    for (const id of removableIds) {
      abortControllers.delete(id);
      simpleTaskCallbacks.delete(id);
    }
    set((s) => ({
      tasks: s.tasks.filter((t) => t.status === "running"),
      expandedTaskId:
        s.expandedTaskId &&
        s.tasks.find((t) => t.id === s.expandedTaskId)?.status === "running"
          ? s.expandedTaskId
          : null,
    }));
  },

  setNotificationOpen: (open: boolean) => {
    set({ notificationOpen: open });
    // 关闭通知时同时关闭大面板
    if (!open) set({ panelExpanded: false });
  },

  setPanelExpanded: (expanded: boolean) => {
    set({ panelExpanded: expanded });
    // 打开大面板时确保 notificationOpen 为 true
    if (expanded) set({ notificationOpen: true });
  },

  setExpandedTaskId: (id: string | null) => {
    set({ expandedTaskId: id });
  },

  /**
   * 页面加载时调用：查询后端 running 对话 → 检查 Redis 流状态 → reconnect SSE
   */
  tryReconnect: () => {
    if (get().reconnected) return;
    set({ reconnected: true });

    // 异步查询后端
    (async () => {
      try {
        const runningConvs = await listRunningTaskStreams();
        if (runningConvs.length === 0) {
          console.log("[Pipeline] 无运行中任务");
          return;
        }

        console.log(
          `[Pipeline] 后端有 ${runningConvs.length} 个 running 对话，开始重连`
        );

        for (const conv of runningConvs) {
          const conversationId = conv.conversationId;
          const id = `reconnect-${conversationId}`;

          // 检查是否已存在（当前页面已有该任务的 SSE 连接）
          const existing = get().tasks.find(
            (t) => t.state.conversationId === conversationId
          );
          if (existing) continue;

          // 创建占位 task
          const placeholder: PipelineTask = {
            id,
            label: conv.title || "AI 任务",
            projectId: conv.projectId,
            status: "running",
            state: {
              status: "running",
              reasoningText: "",
              timeline: [{ type: "content", text: "正在重连……" }],
              conversationId,
            },
            createdAt: conv.createTime
              ? new Date(conv.createTime).getTime()
              : Date.now(),
            cancellable: conv.category !== "task",
          };

          set((s) => ({ tasks: [...s.tasks, placeholder] }));

          // 检查 Redis 流状态
          try {
            const streamStatus = await getTaskStreamStatus(conversationId);
            console.log(
              `[Pipeline] 对话 ${conversationId} Redis 状态: ${streamStatus}`
            );

            if (streamStatus === "ACTIVE") {
              // 后台仍在运行 → 重连 SSE
              const handleEvent = createEventHandler(
                id,
                set,
                get,
                undefined,
                undefined,
                conv.category === "task" ? "paragraph" : "stream"
              );

              // 清空占位文本
              set((s) => ({
                tasks: s.tasks.map((t) =>
                  t.id === id
                    ? { ...t, state: { ...t.state, timeline: [] } }
                    : t
                ),
              }));

              const controller = reconnectTaskStream(conversationId, {
                onEvent: handleEvent,
                onError: (err) => {
                  settleTaskIfRunning(set, get, id, "error", { error: err.message });
                },
                onComplete: () => {
                  settleTaskIfRunning(set, get, id, "done");
                },
              });

              abortControllers.set(id, controller);
            } else {
              // Redis 流已结束 → 标记
              const finalStatus: PipelineTask["status"] =
                streamStatus === "COMPLETED" ? "done" : "error";
              set((s) => ({
                tasks: s.tasks.map((t) =>
                  t.id === id
                    ? {
                        ...t,
                        status: finalStatus,
                        finishedAt: Date.now(),
                        state: {
                          ...t.state,
                          status:
                            finalStatus === "done"
                              ? ("done" as const)
                              : ("error" as const),
                          timeline: [
                            {
                              type: "content" as const,
                              text:
                                finalStatus === "done"
                                  ? "任务已完成"
                                  : "任务异常结束",
                            },
                          ],
                        },
                      }
                    : t
                ),
              }));
              scheduleAutoCleanup(set, get);
            }
          } catch (err) {
            console.error(`[Pipeline] 重连 ${conversationId} 失败:`, err);
            set((s) => ({
              tasks: s.tasks.map((t) =>
                t.id === id
                  ? {
                      ...t,
                      status: "error" as const,
                      finishedAt: Date.now(),
                      state: {
                        ...t.state,
                        status: "error" as const,
                        error: "重连失败",
                        timeline: [
                          {
                            type: "content" as const,
                            text: "重连失败，任务可能已结束",
                          },
                        ],
                      },
                    }
                  : t
              ),
            }));
            scheduleAutoCleanup(set, get);
          }
        }
      } catch (err) {
        console.error("[Pipeline] 查询运行中任务失败:", err);
      }
    })();
  },
}));
