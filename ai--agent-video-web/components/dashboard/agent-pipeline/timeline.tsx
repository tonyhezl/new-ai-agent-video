"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  Ban,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Coins,
  Cpu,
  Loader2,
  Network,
  Wrench,
  XCircle,
} from "lucide-react";
import { StreamMarkdown } from "@/components/dashboard/stream-markdown";
import { StreamThink } from "@/components/dashboard/stream-think";
import { cn } from "@/lib/utils";
import {
  getToolDisplayName,
  isSubAgentTool,
} from "../shared/ai-task-display";
import { ToolResultDisplay } from "./results";
import type { SubTimelineItem, TimelineItem } from "./types";

function SubTimelineToolItem({
  child,
}: {
  child: Extract<SubTimelineItem, { type: "tool" }>;
}) {
  return (
    <div
      className={cn(
        "rounded-lg border text-xs px-3 py-2 flex items-center gap-2",
        child.status === "calling" && "border-blue-500/20 bg-blue-500/5",
        child.status === "done" && "border-green-500/20 bg-green-500/5",
        child.status === "error" && "border-destructive/20 bg-destructive/5"
      )}
    >
      {child.status === "calling" ? (
        <Loader2 className="h-3 w-3 animate-spin text-blue-400 shrink-0" />
      ) : child.status === "done" ? (
        <CheckCircle2 className="h-3 w-3 text-green-400 shrink-0" />
      ) : (
        <XCircle className="h-3 w-3 text-destructive shrink-0" />
      )}
      {isSubAgentTool(child.name) ? (
        <Bot className="h-3 w-3 text-purple-400 shrink-0" />
      ) : (
        <Wrench className="h-3 w-3 text-muted-foreground shrink-0" />
      )}
      <span className="font-medium">{getToolDisplayName(child.name)}</span>
      <span className="ml-auto text-muted-foreground/60">
        {child.status === "calling" ? "执行中..." : child.status === "done" ? "✓" : "✗"}
      </span>
    </div>
  );
}

function ToolTimelineItem({
  item,
  isExpanded,
  onToggle,
}: {
  item: Extract<TimelineItem, { type: "tool" }>;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  const hasResult =
    (item.status === "done" || item.status === "error") && item.result;
  const hasChildren = !!item.children?.length;
  const canExpand = hasResult || hasChildren;

  return (
    <motion.div
      initial={{ opacity: 0, x: -8 }}
      animate={{ opacity: 1, x: 0 }}
      className={cn(
        "rounded-xl text-sm border overflow-hidden",
        item.status === "calling" && "border-blue-500/20 bg-blue-500/5",
        item.status === "done" && "border-green-500/20 bg-green-500/5",
        item.status === "error" && "border-destructive/20 bg-destructive/5"
      )}
    >
      <div
        className={cn(
          "flex items-center gap-3 px-4 py-2.5",
          canExpand && "cursor-pointer hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
        )}
        onClick={() => canExpand && onToggle()}
      >
        {item.status === "calling" ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-400 shrink-0" />
        ) : item.status === "done" ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />
        ) : (
          <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />
        )}
        {item.agentName || isSubAgentTool(item.name) ? (
          <Bot className="h-3.5 w-3.5 text-purple-400 shrink-0" />
        ) : (
          <Wrench className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
        )}
        <span className="font-medium text-xs">{getToolDisplayName(item.name)}</span>
        {item.status === "calling" && (
          <span className="text-xs text-muted-foreground ml-auto">执行中...</span>
        )}
        {item.status === "done" && (
          <span className="flex items-center gap-1.5 text-xs text-green-400/80 ml-auto">
            ✓ 完成
            {canExpand &&
              (isExpanded ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              ))}
          </span>
        )}
        {item.status === "error" && (
          <span className="flex items-center gap-1.5 text-xs text-destructive ml-auto">
            ✗ 失败
            {canExpand &&
              (isExpanded ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              ))}
          </span>
        )}
      </div>

      <AnimatePresence>
        {isExpanded && (hasResult || hasChildren) && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div
              className={cn(
                "border-t px-4 py-3 space-y-2",
                item.status === "error"
                  ? "border-destructive/10"
                  : "border-green-500/10"
              )}
            >
              {item.children && item.children.length > 0 && (
                <div className="space-y-2 pl-2 border-l-2 border-purple-500/20">
                  {item.children.map((child, childIndex) => {
                    const childCount = item.children?.length ?? 0;
                    if (child.type === "reasoning") {
                      const childIsStreaming =
                        item.status === "calling" && childIndex === childCount - 1;
                      const childTitle = child.durationMs
                        ? `子Agent 思考 (${(child.durationMs / 1000).toFixed(1)}s)`
                        : childIsStreaming
                          ? "子Agent 思考中"
                          : "子Agent 思考";
                      return (
                        <div key={`sub-reasoning-${childIndex}`} className="text-xs">
                          <StreamThink
                            title={childTitle}
                            content={child.text}
                            compact
                            maxHeight={120}
                            streaming={childIsStreaming}
                          />
                        </div>
                      );
                    }

                    if (child.type === "tool") {
                      return (
                        <SubTimelineToolItem
                          key={`sub-tool-${child.id}`}
                          child={child}
                        />
                      );
                    }

                    if (child.type === "token_usage") {
                      return (
                        <TokenUsageBadge
                          key={`sub-token-${childIndex}`}
                          inputTokens={child.inputTokens}
                          outputTokens={child.outputTokens}
                          totalTokens={child.totalTokens}
                          modelCode={child.modelCode}
                          compact
                        />
                      );
                    }

                    if (child.type === "agent_model_usage") {
                      return (
                        <AgentModelUsageBadge
                          key={`sub-model-${childIndex}`}
                          agentType={child.agentType}
                          agentName={child.agentName}
                          modelCode={child.modelCode}
                          modelName={child.modelName}
                          modelSource={child.modelSource}
                          subAgent={child.subAgent}
                          parentAgentType={child.parentAgentType}
                          compact
                        />
                      );
                    }

                    return (
                      <div
                        key={`sub-content-${childIndex}`}
                        className="rounded-lg border border-border/20 bg-card/20 p-3 text-xs leading-relaxed"
                      >
                        <StreamMarkdown content={child.text} compact />
                      </div>
                    );
                  })}
                </div>
              )}
              {hasResult && (
                <ToolResultDisplay toolName={item.name} result={item.result!} />
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}

export function AgentPipelineTimeline({
  timeline,
  isActive,
}: {
  timeline: TimelineItem[];
  isActive: boolean;
}) {
  const [expandedTools, setExpandedTools] = useState<Set<string>>(new Set());
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [timeline]);

  if (timeline.length === 0) {
    return null;
  }

  return (
    <div ref={scrollRef} className="space-y-2 max-h-[60vh] overflow-y-auto">
      {timeline.map((item, index) => {
        if (item.type === "reasoning") {
          const title = item.durationMs
            ? `思考 (${(item.durationMs / 1000).toFixed(1)}s)`
            : isActive && index === timeline.length - 1
              ? "思考中"
              : "思考";
          return (
            <motion.div
              key={`reasoning-${index}`}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
            >
              <StreamThink
                title={title}
                content={item.text}
                streaming={isActive && index === timeline.length - 1}
              />
            </motion.div>
          );
        }

        if (item.type === "tool") {
          const isExpanded = expandedTools.has(item.id);
          return (
            <ToolTimelineItem
              key={`tool-${item.id}`}
              item={item}
              isExpanded={isExpanded}
              onToggle={() => {
                setExpandedTools((prev) => {
                  const next = new Set(prev);
                  if (next.has(item.id)) {
                    next.delete(item.id);
                  } else {
                    next.add(item.id);
                  }
                  return next;
                });
              }}
            />
          );
        }

        if (item.type === "token_usage") {
          return (
            <TokenUsageBadge
              key={`token-${index}`}
              inputTokens={item.inputTokens}
              outputTokens={item.outputTokens}
              totalTokens={item.totalTokens}
              modelCode={item.modelCode}
            />
          );
        }

        if (item.type === "agent_model_usage") {
          return (
            <AgentModelUsageBadge
              key={`model-${index}`}
              agentType={item.agentType}
              agentName={item.agentName}
              modelCode={item.modelCode}
              modelName={item.modelName}
              modelSource={item.modelSource}
              subAgent={item.subAgent}
              parentAgentType={item.parentAgentType}
            />
          );
        }

        const prevItem = index > 0 ? timeline[index - 1] : null;
        if (
          prevItem?.type === "tool" &&
          prevItem.children &&
          prevItem.children.length > 0 &&
          prevItem.result &&
          item.text.trim() === prevItem.result.trim()
        ) {
          return null;
        }

        return (
          <motion.div
            key={`content-${index}`}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className={cn(
              "rounded-xl border border-border/30 bg-card/30 p-4",
              "text-sm leading-relaxed"
            )}
          >
            <StreamMarkdown
              content={item.text}
              streaming={isActive && index === timeline.length - 1}
            />
          </motion.div>
        );
      })}
    </div>
  );
}

// ========== AFP 抵扣系数表（火山方舟 Agent Plan） ==========
// 文本生成模型：AFP = (input * inputCoeff + output * outputCoeff) / 10000
const AFP_TEXT_COEFFICIENTS: Record<string, { input: number; output: number }> = {
  "doubao-seed-2.0-mini": { input: 0.25, output: 0.25 },
  "doubao-seed-2.0-lite": { input: 0.5, output: 0.5 },
  "deepseek-v4-flash": { input: 0.5, output: 0.5 },
  "doubao-seed-2.1-turbo": { input: 2.5, output: 2.5 },
  "doubao-seed-evolving": { input: 2.5, output: 2.5 },
  "doubao-seed-2.0-code": { input: 2.5, output: 2.5 },
  "minimax-m2.7": { input: 2.5, output: 2.5 },
  "minimax-m3": { input: 2.5, output: 2.5 },
  "kimi-k2.6": { input: 4.5, output: 4.5 },
  "kimi-k2.7-code": { input: 4.5, output: 4.5 },
  "glm-5.2": { input: 1.125, output: 1.125 }, // 限时2.5折
  "glm-latest": { input: 1.125, output: 1.125 },
  "deepseek-v4-pro": { input: 5.5, output: 5.5 },
  "kimi-k3": { input: 10, output: 10 },
};

function estimateAFP(
  inputTokens: number,
  outputTokens: number,
  modelCode?: string
): number | null {
  if (!modelCode) return null;
  const coeff = AFP_TEXT_COEFFICIENTS[modelCode];
  if (!coeff) return null;
  return (inputTokens * coeff.input + outputTokens * coeff.output) / 10000;
}

function formatTokens(n: number): string {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

function TokenUsageBadge({
  inputTokens,
  outputTokens,
  totalTokens,
  modelCode,
  compact,
}: {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  modelCode?: string;
  compact?: boolean;
}) {
  const afp = estimateAFP(inputTokens, outputTokens, modelCode);

  if (compact) {
    return (
      <div className="flex items-center gap-1.5 text-[10px] text-amber-600/80 dark:text-amber-400/80 px-2 py-1">
        <Coins className="h-3 w-3 shrink-0" />
        <span>
          ↑{formatTokens(inputTokens)} ↓{formatTokens(outputTokens)}
          {afp && ` · ${afp.toFixed(2)} AFP`}
        </span>
      </div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      className={cn(
        "rounded-lg border border-amber-500/20 bg-amber-500/5",
        "flex items-center gap-3 px-3 py-2 text-xs"
      )}
    >
      <Coins className="h-3.5 w-3.5 text-amber-500 shrink-0" />
      <div className="flex items-center gap-3 flex-wrap">
        <span className="text-muted-foreground">
          输入 <span className="font-medium text-foreground">{formatTokens(inputTokens)}</span>
        </span>
        <span className="text-muted-foreground">
          输出 <span className="font-medium text-foreground">{formatTokens(outputTokens)}</span>
        </span>
        <span className="text-muted-foreground">
          合计 <span className="font-medium text-foreground">{formatTokens(totalTokens)}</span>
        </span>
        {modelCode && (
          <span className="text-muted-foreground/60 text-[10px]">{modelCode}</span>
        )}
        {afp !== null && (
          <span className="text-amber-600 dark:text-amber-400 font-medium">
            ≈ {afp.toFixed(2)} AFP
          </span>
        )}
      </div>
    </motion.div>
  );
}

const MODEL_SOURCE_LABELS: Record<string, { label: string; color: string }> = {
  explicit: { label: "手动指定", color: "text-sky-400" },
  config: { label: "绑定配置", color: "text-emerald-400" },
  default: { label: "默认模型", color: "text-muted-foreground" },
  inherited: { label: "继承父Agent", color: "text-amber-400" },
};

function AgentModelUsageBadge({
  agentType,
  agentName,
  modelCode,
  modelName,
  modelSource,
  subAgent,
  parentAgentType,
  compact,
}: {
  agentType: string;
  agentName: string;
  modelCode: string | null;
  modelName: string | null;
  modelSource: string;
  subAgent: boolean;
  parentAgentType: string | null;
  compact?: boolean;
}) {
  const sourceInfo = MODEL_SOURCE_LABELS[modelSource] || {
    label: modelSource,
    color: "text-muted-foreground",
  };

  if (compact) {
    return (
      <div className="flex items-center gap-1.5 text-[10px] text-purple-600/80 dark:text-purple-400/80 px-2 py-1">
        <Network className="h-3 w-3 shrink-0" />
        <span className="font-medium">{agentName}</span>
        {modelName && <span className="text-muted-foreground">→ {modelName}</span>}
        <span className={sourceInfo.color}>· {sourceInfo.label}</span>
      </div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      className={cn(
        "rounded-lg border border-purple-500/20 bg-purple-500/5",
        "flex items-center gap-3 px-3 py-2 text-xs"
      )}
    >
      <Network className="h-3.5 w-3.5 text-purple-500 shrink-0" />
      <div className="flex items-center gap-3 flex-wrap">
        <span className="font-medium text-foreground">{agentName}</span>
        {subAgent && (
          <span className="text-[10px] text-muted-foreground bg-muted/30 px-1.5 py-0.5 rounded">
            子Agent{parentAgentType ? ` ← ${parentAgentType}` : ""}
          </span>
        )}
        <span className="text-muted-foreground">→</span>
        <span className="font-medium text-foreground">
          {modelName || "未配置"}
        </span>
        {modelCode && (
          <span className="text-muted-foreground/60 text-[10px]">{modelCode}</span>
        )}
        <span className={cn("font-medium", sourceInfo.color)}>
          · {sourceInfo.label}
        </span>
      </div>
    </motion.div>
  );
}