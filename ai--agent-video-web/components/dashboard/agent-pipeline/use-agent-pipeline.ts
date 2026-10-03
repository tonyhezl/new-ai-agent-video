"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelPipeline,
  pipelineStream,
  type AiChatStreamEvent,
} from "@/lib/api/ai-pipeline";
import {
  createInitialPipelineState,
  createPendingPipelineState,
  reducePipelineEvent,
} from "./state";
import type { AgentPipelineProps, AgentPipelineState } from "./types";

export function useAgentPipeline({
  request,
  autoStart = false,
  onComplete,
  onError,
}: AgentPipelineProps) {
  const [state, setState] = useState<AgentPipelineState>(
    createInitialPipelineState
  );
  const abortRef = useRef<AbortController | null>(null);
  const startedRef = useRef(false);

  const handleEvent = useCallback((event: AiChatStreamEvent) => {
    setState((prev) => reducePipelineEvent(prev, event));
  }, []);

  const startStream = useCallback(() => {
    if (startedRef.current) return;
    startedRef.current = true;

    setState(createPendingPipelineState());

    const controller = pipelineStream(request, {
      onEvent: handleEvent,
      onError: (err) => {
        setState((prev) => ({
          ...prev,
          status: "error",
          error: err.message,
        }));
        onError?.(err.message);
      },
      onComplete: () => {
        setState((prev) => {
          if (prev.status === "running" || prev.status === "reasoning") {
            return { ...prev, status: "done" };
          }
          return prev;
        });
      },
    });

    abortRef.current = controller;
  }, [request, handleEvent, onError]);

  useEffect(() => {
    if (autoStart && !startedRef.current) {
      queueMicrotask(() => {
        if (!startedRef.current) {
          startStream();
        }
      });
    }
  }, [autoStart, startStream]);

  useEffect(() => {
    if (state.status === "done") {
      onComplete?.(state.conversationId);
    }
  }, [state.status, state.conversationId, onComplete]);

  const cancelStream = useCallback(async () => {
    abortRef.current?.abort();
    if (state.conversationId) {
      try {
        await cancelPipeline(state.conversationId);
      } catch {
        // 忽略取消错误
      }
    }
    setState((prev) => ({ ...prev, status: "cancelled" }));
  }, [state.conversationId]);

  const isActive =
    state.status === "reasoning" || state.status === "running";

  return {
    state,
    isActive,
    startStream,
    cancelStream,
  };
}