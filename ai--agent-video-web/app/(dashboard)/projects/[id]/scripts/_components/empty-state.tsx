"use client";

import { BookOpen, Plus, FileText, Sparkles } from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { CreateScriptDialog } from "@/components/dashboard/create-script-dialog";

export function EmptyState({
  projectId,
  projectName,
  showCreateDialog,
  onShowCreateDialog,
  onCreated,
  onParseScript,
  onGenerateScript,
}: {
  projectId: number;
  projectName?: string;
  showCreateDialog: boolean;
  onShowCreateDialog: (show: boolean) => void;
  onCreated: () => void;
  onParseScript: () => void;
  onGenerateScript: () => void;
}) {
  return (
    <>
      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col items-center justify-center py-20"
      >
        <div className="h-24 w-24 rounded-2xl bg-[linear-gradient(135deg,rgba(124,92,255,0.15)_0%,rgba(0,229,255,0.1)_100%)] flex items-center justify-center mb-6 border border-border/50 shadow-[0_0_40px_rgba(124,92,255,0.15)]">
          <BookOpen className="h-12 w-12 text-cyan-400/80" />
        </div>
        <h2 className="text-2xl font-bold mb-2 tracking-tight">还没有剧本</h2>
        <p className="text-muted-foreground text-sm mb-8 max-w-md text-center">
          手动创建空白剧本并编辑，或让 AI 根据你的创意自动生成完整剧本文本
        </p>
        <div className="flex flex-col sm:flex-row items-center gap-3">
          <button
            onClick={() => onShowCreateDialog(true)}
            className={cn(
              "flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-medium",
              "bg-primary text-primary-foreground",
              "hover:opacity-90 hover:scale-[1.02]",
              "active:scale-[0.98] transition-all duration-200"
            )}
          >
            <Plus className="h-4 w-4" />
            手动创建
          </button>
          <button
            onClick={onParseScript}
            className={cn(
              "flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-medium",
              "bg-muted/50 border border-border/40 text-foreground",
              "hover:bg-muted hover:scale-[1.02]",
              "active:scale-[0.98] transition-all duration-200"
            )}
          >
            <FileText className="h-4 w-4" />
            粘贴剧本解析
          </button>
          <button
            onClick={onGenerateScript}
            className={cn(
              "flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-medium",
              "bg-linear-to-r from-purple-600 to-pink-600",
              "text-white shadow-lg shadow-purple-500/20",
              "hover:shadow-purple-500/30 hover:scale-[1.02]",
              "active:scale-[0.98] transition-all duration-200"
            )}
          >
            <Sparkles className="h-4 w-4" />
            AI 创作剧本
          </button>
        </div>
      </motion.div>
      <CreateScriptDialog
        open={showCreateDialog}
        projectId={projectId}
        projectName={projectName}
        onClose={() => onShowCreateDialog(false)}
        onCreated={onCreated}
      />
    </>
  );
}
