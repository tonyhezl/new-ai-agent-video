"use client";

import { useState, useEffect, useMemo, useCallback } from "react";
import {
  Network,
  Save,
  Loader2,
  AlertTriangle,
  CheckCircle2,
  Brain,
  Sparkles,
  Zap,
  Cpu,
  Wrench,
  Eye,
  Lightbulb,
} from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/lib/store/auth-store";
import { agentModelConfigApi, type AgentModelConfig } from "@/lib/api/agent-model-config";
import { aiModelApi, type AiModel } from "@/lib/api/ai-model";
import { containerVariants, itemVariants } from "../_shared";

// ========== Agent 分组定义 ==========

interface AgentGroup {
  key: string;
  label: string;
  description: string;
  icon: typeof Brain;
  iconColor: string;
  agentTypes: string[];
}

const AGENT_GROUPS: AgentGroup[] = [
  {
    key: "pov_stories",
    label: "第一人称猎奇故事体系 (POV)",
    description: "4段式高完播率猎奇编剧、5维悬疑门禁与摄影级机位提示词导演",
    icon: Eye,
    iconColor: "text-amber-400",
    agentTypes: [
      "pov_story_architect",
      "pov_suspense_reviewer",
      "pov_visual_director",
    ],
  },
  {
    key: "script_and_storyboard",
    label: "短剧剧本与分镜创作体系",
    description: "短剧大纲编写、深度全量解析、场次分集编排与分镜拆解",
    icon: Sparkles,
    iconColor: "text-violet-400",
    agentTypes: [
      "script_writer",
      "story_to_script",
      "script_full_parse",
      "script_doctor",
      "script_to_storyboard",
      "episode_storyboard_writer",
      "episode_scene_writer",
      "episode_script_creator",
      "concept_visualizer",
    ],
  },
  {
    key: "generation_executors",
    label: "画面与视频生成执行体系",
    description: "角色/场景资产生成、分镜首尾帧图片与视频生成总控及执行器",
    icon: Wrench,
    iconColor: "text-cyan-400",
    agentTypes: [
      "asset_image_gen",
      "asset_image_executor",
      "storyboard_frame_gen",
      "storyboard_frame_executor",
      "storyboard_video_gen",
      "storyboard_video_executor",
      "storyboard_asset_preprocessor",
    ],
  },
  {
    key: "review_and_assistant",
    label: "影视质检门禁与通用助手体系",
    description: "提示词合规校正、分镜内容复核、上传解析与默认媒体助手",
    icon: Brain,
    iconColor: "text-emerald-400",
    agentTypes: [
      "prompt_reviewer",
      "storyboard_content_reviewer",
      "script_episode_upload",
      "script_episode_parse",
      "script_assistant",
      "ai_media",
    ],
  },
];

// ========== 推荐配置 ==========

const RECOMMENDED_CONFIG: Record<string, number | null> = {};
// 推荐配置需要根据用户已配置的模型来动态匹配，这里仅作为 UI 提示

export default function AgentModelConfigPage() {
  const currentUser = useAuthStore((state) => state.user);
  const isAdmin = currentUser?.roles?.includes("admin") ?? false;

  const [configs, setConfigs] = useState<AgentModelConfig[]>([]);
  const [models, setModels] = useState<AiModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  // 本地编辑状态：agentType → modelId
  const [editMap, setEditMap] = useState<Record<string, number | null>>({});
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [configList, modelList] = await Promise.all([
        agentModelConfigApi.list(),
        aiModelApi.listByType(1),
      ]);
      setConfigs(configList);
      setModels(modelList);
      const map: Record<string, number | null> = {};
      for (const c of configList) {
        map[c.agentType] = c.modelId;
      }
      setEditMap(map);
      setDirty(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载数据失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleModelChange = useCallback((agentType: string, modelId: number | null) => {
    setEditMap(prev => {
      const next = { ...prev, [agentType]: modelId };
      setDirty(true);
      return next;
    });
    setSuccess(false);
  }, []);

  const handleSave = useCallback(async () => {
    setSaving(true);
    setError(null);
    setSuccess(false);
    try {
      const changedConfigs: Array<{ agentType: string; modelId: number }> = [];
      for (const config of configs) {
        const newModelId = editMap[config.agentType];
        if (newModelId != null && newModelId !== config.modelId) {
          changedConfigs.push({ agentType: config.agentType, modelId: newModelId });
        }
      }
      if (changedConfigs.length === 0) {
        setSaving(false);
        return;
      }
      await agentModelConfigApi.batchSave({ configs: changedConfigs });
      setSuccess(true);
      setDirty(false);
      // 重新加载以同步服务端状态
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }, [configs, editMap, loadData]);

  // 按分组组织 Agent
  const groupedConfigs = useMemo(() => {
    const configMap = new Map(configs.map(c => [c.agentType, c]));
    return AGENT_GROUPS.map(group => ({
      ...group,
      agents: group.agentTypes
        .map(type => configMap.get(type))
        .filter(Boolean) as AgentModelConfig[],
    })).filter(g => g.agents.length > 0);
  }, [configs]);

  // 根据当前选中的业务体系过滤
  const displayedGroups = useMemo(() => {
    if (selectedCategory === "ALL") return groupedConfigs;
    return groupedConfigs.filter(g => g.key === selectedCategory);
  }, [groupedConfigs, selectedCategory]);

  // 未分组的 Agent
  const ungroupedConfigs = useMemo(() => {
    if (selectedCategory !== "ALL") return [];
    const groupedTypes = new Set(AGENT_GROUPS.flatMap(g => g.agentTypes));
    return configs.filter(c => !groupedTypes.has(c.agentType));
  }, [configs, selectedCategory]);

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] gap-2">
        <AlertTriangle className="h-8 w-8 text-amber-400" />
        <p className="text-sm text-muted-foreground">仅管理员可配置 Agent 模型绑定</p>
      </div>
    );
  }

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="space-y-6"
    >
      {/* 标题区 */}
      <motion.div variants={itemVariants} className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/10">
            <Network className="h-5 w-5 text-violet-400" />
          </div>
          <div>
            <h1 className="text-xl font-semibold">Agent 模型配置</h1>
            <p className="text-sm text-muted-foreground">为每个 Agent 类型指定使用的 AI 模型</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {dirty && (
            <span className="text-xs text-amber-400 flex items-center gap-1">
              <AlertTriangle className="h-3.5 w-3.5" />
              有未保存的更改
            </span>
          )}
          {success && !dirty && (
            <span className="text-xs text-emerald-400 flex items-center gap-1">
              <CheckCircle2 className="h-3.5 w-3.5" />
              已保存
            </span>
          )}
          <button
            onClick={handleSave}
            disabled={!dirty || saving}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              "border border-border/40",
              dirty
                ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/20"
                : "bg-muted/30 text-muted-foreground cursor-not-allowed",
            )}
          >
            {saving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            保存配置
          </button>
        </div>
      </motion.div>

      {/* 说明卡片 */}
      <motion.div variants={itemVariants}
        className="rounded-xl border border-border/40 bg-muted/20 p-4 space-y-2"
      >
        <div className="flex items-start gap-2">
          <Lightbulb className="h-4 w-4 text-amber-400 mt-0.5 shrink-0" />
          <div className="text-xs text-muted-foreground space-y-1">
            <p>
              <span className="text-foreground font-medium">模型优先级</span>：
              请求显式指定 &gt; Agent 绑定配置 &gt; 系统默认文本模型
            </p>
            <p>
              <span className="text-foreground font-medium">子 Agent 独立解析</span>：
              每个子 Agent 会根据自己的类型查找绑定模型，未配置则复用父 Agent 模型
            </p>
            <p>
              <span className="text-foreground font-medium">注意</span>：
              需要工具调用的 Agent 请勿使用纯 reasoning 模型（如 Grok-4.6），可能导致工具调用被跳过
            </p>
          </div>
        </div>
      </motion.div>

      {/* 错误提示 */}
      {error && (
        <motion.div variants={itemVariants}
          className="rounded-xl border border-red-500/30 bg-red-500/10 p-3 flex items-center gap-2"
        >
          <AlertTriangle className="h-4 w-4 text-red-400 shrink-0" />
          <span className="text-sm text-red-400">{error}</span>
        </motion.div>
      )}

      {/* 业务体系分类快速切换 Tab */}
      <motion.div variants={itemVariants} className="flex items-center gap-2 overflow-x-auto pb-1">
        {[
          { key: "ALL", label: "全部体系" },
          { key: "pov_stories", label: "第一人称猎奇 (POV)" },
          { key: "script_and_storyboard", label: "短剧剧本与分镜" },
          { key: "generation_executors", label: "画面与视频生成" },
          { key: "review_and_assistant", label: "质检门禁与通用" },
        ].map((tab) => (
          <button
            key={tab.key}
            onClick={() => setSelectedCategory(tab.key)}
            className={cn(
              "px-3.5 py-1.5 rounded-lg text-xs font-medium transition-all",
              selectedCategory === tab.key
                ? "bg-violet-500 text-white shadow-sm font-semibold"
                : "bg-muted/40 text-muted-foreground hover:text-foreground hover:bg-muted/70 border border-border/40"
            )}
          >
            {tab.label}
          </button>
        ))}
      </motion.div>

      {/* 分组列表 */}
      {displayedGroups.map(group => {
        const GroupIcon = group.icon;
        return (
          <motion.div key={group.key} variants={itemVariants}
            className="rounded-xl border border-border/40 bg-card/30 overflow-hidden"
          >
            {/* 分组标题 */}
            <div className="flex items-center gap-3 px-5 py-3 border-b border-border/30 bg-muted/10">
              <div className={cn("flex h-8 w-8 items-center justify-center rounded-lg bg-muted/30", group.iconColor)}>
                <GroupIcon className="h-4 w-4" />
              </div>
              <div className="flex-1">
                <h2 className="text-sm font-semibold">{group.label}</h2>
                <p className="text-xs text-muted-foreground">{group.description}</p>
              </div>
              <span className="text-xs text-muted-foreground">{group.agents.length} 个 Agent</span>
            </div>

            {/* Agent 列表 */}
            <div className="divide-y divide-border/20">
              {group.agents.map(agent => (
                <AgentConfigRow
                  key={agent.agentType}
                  agent={agent}
                  models={models}
                  selectedModelId={editMap[agent.agentType] ?? null}
                  onChange={modelId => handleModelChange(agent.agentType, modelId)}
                />
              ))}
            </div>
          </motion.div>
        );
      })}

      {/* 未分组的 Agent */}
      {ungroupedConfigs.length > 0 && (
        <motion.div variants={itemVariants}
          className="rounded-xl border border-border/40 bg-card/30 overflow-hidden"
        >
          <div className="flex items-center gap-3 px-5 py-3 border-b border-border/30 bg-muted/10">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-muted/30 text-muted-foreground">
              <Cpu className="h-4 w-4" />
            </div>
            <div className="flex-1">
              <h2 className="text-sm font-semibold">其他 Agent</h2>
              <p className="text-xs text-muted-foreground">未归入预设分组的 Agent</p>
            </div>
          </div>
          <div className="divide-y divide-border/20">
            {ungroupedConfigs.map(agent => (
              <AgentConfigRow
                key={agent.agentType}
                agent={agent}
                models={models}
                selectedModelId={editMap[agent.agentType] ?? null}
                onChange={modelId => handleModelChange(agent.agentType, modelId)}
              />
            ))}
          </div>
        </motion.div>
      )}

      {/* 底部保存按钮 */}
      {dirty && (
        <motion.div variants={itemVariants}
          className="sticky bottom-4 z-10 flex justify-end"
        >
          <button
            onClick={handleSave}
            disabled={saving}
            className={cn(
              "flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-medium transition-all",
              "bg-emerald-500/20 text-emerald-400 border border-emerald-500/40",
              "hover:bg-emerald-500/30 shadow-lg backdrop-blur",
            )}
          >
            {saving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            保存配置
          </button>
        </motion.div>
      )}
    </motion.div>
  );
}

// ========== 单行 Agent 配置组件 ==========

function AgentConfigRow({
  agent,
  models,
  selectedModelId,
  onChange,
}: {
  agent: AgentModelConfig;
  models: AiModel[];
  selectedModelId: number | null;
  onChange: (modelId: number | null) => void;
}) {
  const selectedModel = models.find(m => m.id === selectedModelId);
  const isUnconfigured = selectedModelId == null;

  return (
    <div className="flex items-center gap-4 px-5 py-3 hover:bg-muted/10 transition-colors">
      {/* Agent 信息 */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium truncate">{agent.agentName}</span>
          <code className="text-[10px] text-muted-foreground bg-muted/30 px-1.5 py-0.5 rounded">
            {agent.agentType}
          </code>
        </div>
        <div className="flex items-center gap-2 mt-1">
          {agent.enableTools ? (
            <span className="text-[10px] flex items-center gap-1 text-sky-400">
              <Wrench className="h-3 w-3" />
              工具调用
            </span>
          ) : (
            <span className="text-[10px] flex items-center gap-1 text-muted-foreground">
              <Brain className="h-3 w-3" />
              纯推理
            </span>
          )}
          {selectedModel?.supportReasoning && (
            <span className="text-[10px] flex items-center gap-1 text-violet-400">
              <Brain className="h-3 w-3" />
              reasoning
            </span>
          )}
          {selectedModel?.supportVision && (
            <span className="text-[10px] flex items-center gap-1 text-teal-400">
              <Eye className="h-3 w-3" />
              vision
            </span>
          )}
        </div>
      </div>

      {/* 模型选择 */}
      <div className="flex items-center gap-2 shrink-0">
        {isUnconfigured && (
          <span className="text-xs text-muted-foreground">使用默认模型</span>
        )}
        <select
          value={selectedModelId ?? ""}
          onChange={e => {
            const val = e.target.value;
            onChange(val ? Number(val) : null);
          }}
          className={cn(
            "px-3 py-1.5 rounded-lg text-sm transition-all",
            "bg-muted/50 border border-border/40",
            "focus:outline-none focus:ring-1 focus:ring-violet-500/50",
            isUnconfigured && "text-muted-foreground",
          )}
        >
          <option value="">使用默认模型</option>
          {models.map(model => (
            <option key={model.id} value={model.id}>
              {model.name}
              {model.supportReasoning ? " (reasoning)" : ""}
              {model.defaultModel ? " ★" : ""}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
