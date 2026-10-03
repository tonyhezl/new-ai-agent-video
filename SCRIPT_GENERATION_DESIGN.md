# AI 剧本生成功能设计

## 一、需求概述

在现有的"粘贴剧本原文 → AI 解析"流程之上，新增"AI 生成剧本原文 → 再解析"的能力：

1. 用户选择题材、风格、类型、集数等参数
2. 一个独立的 **剧本创作 Agent**（可配置自己的对话模型）生成完整剧本原文
3. 生成完成后自动进入现有的 `script_full_parse` 解析流程

## 二、现有架构分析

### 当前流程

```
用户粘贴剧本原文 → ParseScriptDialog → scriptApi.create(rawContent)
  → addPipeline(agentType: "script_full_parse")
    → AiAssistantService.stream() → script_full_parse Agent
      → 逐集 save_script_episode → episode_scene_writer 子 Agent
      → review_script 剧本诊断
```

### 已有的 story_to_script Agent

系统已注册 `story_to_script` Agent（`AiAgentRegistry.registerScriptStoryToScriptAgent()`），它：
- 从 `get_project_script` 读取 `storySynopsis` 或 `rawContent` 作为故事大纲
- 设计角色/场景/道具 → `batch_create_assets`
- 规划多集大纲 → `save_script_episode`（含 synopsis）
- 调用 `episode_script_creator` 子 Agent 创作对白
- 调用 `review_script` 诊断

**关键区别**：`story_to_script` 是"故事大纲 → 结构化剧本"，不产出完整剧本文本格式（rawContent），而是直接创作场次。用户的需求是"生成剧本原文"再走解析流程，更接近传统编剧格式。

### Agent 模型配置现状

- `AiAgentDefinition` **没有** `modelId` 字段
- 当前所有 Agent 复用请求中传入的 `modelId`（`AiChatReqVO.modelId`），或回退到系统默认对话模型
- 子 Agent 直接复用父 Agent 的 `chatModel`
- **需要新增**：Agent 级别的模型配置能力

## 三、设计方案

### 3.1 整体流程

```
用户点击"AI 生成剧本"
  → ScriptGenerationDialog（选择题材/风格/类型/集数/模型）
    → scriptApi.create(rawContent=null)  // 先建一条空剧本记录，拿到 scriptId
    → addPipeline(agentType: "script_writer", context: { scriptId, genre, style, type, episodeCount, description })
      → script_writer Agent（使用配置的对话模型）
        → 按集创作 → 每完成一集，立即调用 append_script_raw_content 追加写入 Script.rawContent
      → Pipeline 完成后，前端自动触发第二步：
    → addPipeline(agentType: "script_full_parse")
      → 解析 rawContent → 结构化分集/场次
```

**关键调整（相比初版）**：
- 用户选择的创意参数（题材/风格/类型/集数/描述）**直接作为 Pipeline 请求的 context 模板变量传入**，不写入 `storySynopsis` 字段再由 Agent 反查。这样可以省去一次 `get_project_script` 工具调用，同时避免和"故事梗概"字段的语义混用（`storySynopsis` 在解析阶段会被 `script_full_parse` 覆盖为剧情摘要，两种用途混用同一字段容易造成后续维护困惑）。
- 写入方式从"一次性写入完整 rawContent"改为"逐集追加写入"，详见 3.3。

### 3.2 新增 Agent：script_writer

**职责**：根据用户选择的题材、风格、类型、集数等参数，逐集创作剧本文本（传统编剧格式），并追加写入 `Script.rawContent`。

**Agent 定义**（`AiAgentRegistry`）：

```java
private void registerScriptWriterAgent() {
    register(AiAgentDefinition.builder()
        .type("script_writer")
        .name("AI 剧本创作")
        .toolNames(List.of("append_script_raw_content"))
        .systemPrompt(loadPrompt("script-writer.system.md"))
        .instructionTemplate("""
            <task_context>
            <script_id>{scriptId}</script_id>
            <genre>{genre}</genre>
            <style>{style}</style>
            <type>{type}</type>
            <episode_count>{episodeCount}</episode_count>
            <description>{description}</description>
            </task_context>
            """)
        .defaultUserMessage("请根据以上创意参数，为剧本（ID: {scriptId}）创作 {episodeCount} 集完整剧本文本。")
        .enableTools(1)
        .build());
}
```

**改动说明**：不再依赖 `get_project_script` 反查创意需求，题材/风格/类型/集数/描述直接通过 `instructionTemplate` 模板变量注入，减少一次工具往返。`toolNames` 也只保留 `append_script_raw_content` 一个工具，职责单一。

**System Prompt 要点**（`script-writer.system.md`）：

```markdown
你是一位资深编剧。你的任务是根据 <task_context> 中的创意参数，逐集创作完整的剧本文本。

## 工作流程
1. 阅读 <task_context> 中的题材、风格、类型、集数、创意描述
2. 逐集构思并创作，每完成一集，立即调用 append_script_raw_content 追加写入该集正文
3. 不要等全部集数构思完毕后再一次性提交，必须按集顺序、边写边调用工具

## 创作要求
- 【严格遵循集数】必须严格按 episode_count 指定的集数创作，不多写也不少写
- 【禁止跨集剧透】创作第 N 集时，不要提前透露第 N+1 集及之后的关键剧情转折
- 每集正文控制在 800-1500 字左右（对应短剧单集 1-3 分钟片长），不要过短敷衍也不要过度冗长
- 使用标准剧本格式：场景标题（如"场景1·XX地点·日"）、动作描写、角色对白
- 对白格式：角色名 + 冒号，对白内容紧随其后
- 全程使用中文创作
- 每集之间用明确的分集标记分隔（如"第1集"）

## 输出行为规范（必须遵守）
- 每次调用 append_script_raw_content 后，仅用一句话汇报进度（如"第2集已完成，约1200字"），【严禁】在聊天回复中重复输出剧本正文内容
- 全部集数完成后，仅用一句话总结（共几集、约几字），不要罗列剧情细节
```

### 3.3 新增工具：append_script_raw_content

**职责**：将 AI 逐集创作的剧本文本**追加**写入 `Script.rawContent`（而非一次性整体写入）。

**为什么用追加而不是整体写入**：如果 Agent 把全部集数构思完再作为一个大字符串一次性传给工具，LLM 需要把剧本全文在"聊天回复"和"工具调用参数"中各生成一遍，token 成本翻倍，且超长 JSON 参数容易触发部分模型的截断/转义问题。改为逐集追加后：
- 每集正文只需在工具参数里生成一次
- 中途失败也能保留已生成的部分，支持断点续写
- 与 `script_full_parse` 现有的逐集处理习惯（`save_script_episode`）保持一致的设计风格

```java
@Component
@RequiredArgsConstructor
public class AppendScriptRawContentToolExecutor implements ToolExecutor {

    private final ScriptService scriptService;

    @Override
    public String getToolName() {
        return "append_script_raw_content";
    }

    @Override
    public String getToolDescription() {
        return "将单集剧本正文追加到 Script.rawContent 末尾。每完成一集创作就调用一次，不要攒到最后一次性提交。";
    }

    @Override
    public String getParametersSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "scriptId": { "type": "integer", "description": "剧本ID" },
                "episodeNumber": { "type": "integer", "description": "当前集数，从1开始" },
                "episodeContent": { "type": "string", "description": "本集完整正文（含分集标记、场景标题、对白）" }
              },
              "required": ["scriptId", "episodeNumber", "episodeContent"]
            }
            """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        JSONObject params = JSONUtil.parseObj(toolInput);
        Long scriptId = params.getLong("scriptId");
        Integer episodeNumber = params.getInt("episodeNumber");
        String episodeContent = params.getStr("episodeContent");

        Script script = scriptService.getById(scriptId);
        String existing = StrUtil.blankToDefault(script.getRawContent(), "");
        String updated = existing + (existing.isEmpty() ? "" : "\n\n") + episodeContent;
        script.setRawContent(updated);
        scriptService.update(script);

        return JSONUtil.createObj()
                .set("status", "success")
                .set("episodeNumber", episodeNumber)
                .set("totalLength", updated.length())
                .set("message", "第" + episodeNumber + "集已追加写入").toString();
    }
}
```

### 3.4 Agent 级别模型配置（可选，延后实现）

**复核结论**：当前 `AiChatReqVO.modelId` 已支持前端在发起请求时显式指定模型，`ScriptGenerationDialog` 里用户选择的模型可以直接透传到 Pipeline 请求的 `modelId` 字段，**不需要新增 Agent 级别的固定模型配置就能满足"用户为本次创作选择模型"的需求**。

Agent 级别 `modelId`（即"管理员为某个 Agent 类型统一固定模型，用户不可更改"）是一个独立的功能点，与本次剧本生成需求没有强绑定关系，为了减少改动面和引入的风险，**建议延后到有明确该需求时再实现**。若后续需要，再按以下方案落地：

1. `AiAgentDefinition.java` — 新增 `private Long modelId;`
2. `AiAssistantService.java` / `AgentScopeAssistantService.java` — `getChatModel()` 优先级：请求显式 `modelId` > Agent 配置的 `modelId` > 系统默认模型

### 3.5 前端：ScriptGenerationDialog

新增对话框组件，替代当前 `ParseScriptDialog` 的入口（或作为并列选项）。

**UI 结构**：

```
┌─────────────────────────────────────┐
│  AI 创作剧本                         │
├─────────────────────────────────────┤
│  创意描述（textarea）                 │
│  "一个关于穿越到古代的现代程序员..."   │
│                                     │
│  题材：[都市] [古装] [科幻] [悬疑]...  │
│  风格：[轻松] [热血] [虐心] [搞笑]...  │
│  类型：[漫剧] [短剧] [MV] [纪录片]...  │
│  集数：[1集] [3集] [5集] [10集] [自定义]│
│  对话模型：[下拉选择模型]              │
│                                     │
│  [取消]              [开始创作]       │
└─────────────────────────────────────┘
```

**提交逻辑**：

```typescript
const handleGenerate = async () => {
  // 1. 创建空剧本记录，仅用于拿到 scriptId（不再借用 storySynopsis 存创意参数）
  const script = await scriptApi.create({
    projectId,
    title: title.trim() || projectName,
  });

  // 2. 启动 script_writer Pipeline，创意参数直接作为 context 模板变量传入
  const writerPipelineId = addPipeline({
    label: `AI 创作剧本 - ${title}`,
    projectId,
    request: {
      agentType: "script_writer",
      category: "pipeline",
      title: `AI 剧本创作：${title}`,
      projectId,
      context: {
        scriptId: script.id,
        genre,
        style,
        type,
        episodeCount: episodes,
        description,
      },
      modelId: selectedModelId,  // 用户选择的对话模型
    },
    onComplete: () => {
      // 3. 创作完成后自动启动解析 Pipeline
      addPipeline({
        label: `AI 解析剧本 - ${title}`,
        projectId,
        request: {
          agentType: "script_full_parse",
          category: "pipeline",
          title: `AI 剧本解析：${title}`,
          projectId,
          context: { scriptId: script.id },
        },
        onComplete: () => loadScript(),
      });
    },
    onError: () => {
      // 创作阶段失败，不触发解析阶段；提示用户"AI 创作失败"而非"解析失败"，便于区分两个阶段
    },
  });
};
```

### 3.6 EmptyState 入口调整

在 `EmptyState` 组件中，将现有的 "AI 生成剧本" 按钮拆分为两个入口：

- **粘贴剧本解析** → 打开 `ParseScriptDialog`（现有流程）
- **AI 创作剧本** → 打开 `ScriptGenerationDialog`（新流程）

```
┌───────────────────────────────────────┐
│  还没有剧本                             │
│                                       │
│  [手动创建]  [粘贴解析]  [AI 创作剧本]   │
└───────────────────────────────────────┘
```

## 四、改动清单

### 后端

| 文件 | 改动 |
|---|---|
| `AiAgentRegistry.java` | 注册 `script_writer` Agent（工具仅 `append_script_raw_content`） |
| `prompts/agents/script-writer.system.md` | 新建剧本创作 Agent 的 system prompt |
| `AppendScriptRawContentToolExecutor.java` | 新增工具，按集追加写入 Script.rawContent |

> Agent 级别 `modelId` 相关改动（`AiAgentDefinition.java`、`AiAssistantService.java`、`AgentScopeAssistantService.java`）已移至延后事项，本次不实现。

### 前端

| 文件 | 改动 |
|---|---|
| `ScriptGenerationDialog.tsx` | 新建对话框组件 |
| `empty-state.tsx` | 新增"AI 创作剧本"入口按钮 |
| `scripts/page.tsx` | 接入新对话框，处理两阶段 Pipeline |
| `ai-pipeline.ts` | `PIPELINE_AGENT_TYPES` 添加 `script_writer` |
| `ai-task-display.ts` | `agentTypeNames` 添加 `script_writer: "AI 剧本创作"` |

## 五、数据流

```
用户选择参数（题材/风格/类型/集数/描述）
  → scriptApi.create()  // 仅创建空壳，拿到 scriptId
    → Pipeline 1: script_writer Agent（创意参数通过 context 模板变量注入，无需反查数据库）
      → 逐集创作，每集完成后立即调用 append_script_raw_content（追加写入 rawContent）
      → ×N 集（N = episodeCount）
    → Pipeline 1 完成
    → Pipeline 2: script_full_parse Agent
      → get_project_script（读取完整 rawContent）
      → batch_create_assets（创建角色/场景/道具）
      → update_script_info（保存角色表、梗概）
      → save_script_episode × N（逐集保存）
      → episode_scene_writer × N（逐集场次解析）
      → review_script（剧本诊断）
    → Pipeline 2 完成
    → loadScript() 刷新前端
```

## 六、注意事项

1. **模型选择**：`script_writer` Agent 需要较强的创作能力，建议用户在 `ScriptGenerationDialog` 中选择高质量对话模型（如 GPT-4o、Claude 3.5 Sonnet 等），通过请求级 `modelId` 传入即可，无需 Agent 级别的固定模型配置（见 3.4 说明）。

2. **两阶段 Pipeline 的衔接**：第一阶段（创作）完成后自动触发第二阶段（解析）。前端通过 `onComplete` 回调链实现。如果第一阶段失败，通过 `onError` 提示"AI 创作失败"，不触发第二阶段，避免用户误以为是解析阶段的问题。

3. **创意参数传递方式**：不再借用 `storySynopsis` 字段存放创意参数，而是通过 Pipeline 请求的 `context` 直接作为模板变量注入 `script_writer` 的 `instructionTemplate`（题材、风格、类型、集数、描述）。`storySynopsis` 字段的语义保持单一（仅用于"故事梗概"，由 `script_full_parse` 生成）。

4. **与现有 story_to_script 的区别**：
   - `story_to_script`：故事大纲 → 直接创作结构化场次（无 rawContent）
   - `script_writer`（新）：创意参数 → 逐集生成剧本文本并追加写入 rawContent → 再走 `script_full_parse` 解析
   - 两者可以共存，用户选择不同的创作路径

5. **Token 消耗与写入方式**：采用"逐集创作、逐集追加写入"（`append_script_raw_content`），而非一次性生成全部内容再整体写入。原因：
   - 避免 LLM 把剧本全文在"聊天回复"和"工具调用参数"中各生成一遍（token 成本翻倍）
   - 避免超长 JSON 参数触发部分模型的截断/转义问题
   - 中途失败可保留已生成部分，支持后续续写
   - system prompt 中明确要求：工具调用后仅一句话汇报进度，禁止在聊天回复中重复输出剧本正文

6. **防止跨集剧透与集数越界**：system prompt 中需明确"严格按 episode_count 指定集数创作，不多写也不少写""创作第 N 集时不要提前透露后续集数的关键剧情转折"，避免创作阶段出现类似解析阶段"脑补"的问题（参考 `script_full_parse` 的严禁脑补规则）。
