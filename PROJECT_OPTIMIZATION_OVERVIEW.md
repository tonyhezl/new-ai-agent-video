# AI Agent 视频生成平台 — 关键优化总览

> 本文档汇总项目历次优化的核心改动点，按模块分类，便于一目了然掌握当前系统能力边界与质量保障机制。

---

## 一、AI Agent 体系架构

系统采用 **主 Agent + 子 Agent** 两层（部分场景三层）调度架构，所有 Agent 在 `AiAgentRegistry.java` 中代码级注册，提示词从 `resources/prompts/agents/` 加载。

### Agent 全景

| Agent 类型 | 名称 | 职责 | 子 Agent |
|---|---|---|---|
| `script_full_parse` | 剧本全量解析 | 结构化解析剧本 → 创建资产 → 诊断 | `script_doctor`, `episode_script_creator` |
| `story_to_script` | 故事转剧本 | 从故事文本生成结构化剧本 | `script_doctor`, `episode_script_creator` |
| `script_doctor` | 剧本医生 | 诊断剧本病灶、评分、开处方 | 无（纯推理） |
| `episode_script_creator` | 分集剧本创作器 | 逐集创作场次对白并保存 | 无 |
| `episode_scene_writer` | 分集场次编写器 | 解析场次数据 | 无 |
| `script_to_storyboard` | 剧本转分镜 | 预处理子资产 → 并行分集分镜 | `storyboard_asset_preprocessor`, `episode_storyboard_writer` |
| `storyboard_asset_preprocessor` | 子资产预处理器 | 分析剧本识别变体、批量创建子资产 | 无 |
| `episode_storyboard_writer` | 分集分镜编写器 | 设计镜头 → 复核 → 保存分镜 | `review_storyboard_content` |
| `storyboard_content_reviewer` | 分镜内容复核 | 保存前复核镜头内容质量 | 无（纯推理） |
| `asset_image_gen` | 资产图片生成 | 分阶段调度子资产生图 | `generate_asset_image` |
| `asset_image_executor` | 资产图片执行器 | 单子资产：查资产→编排prompt→校正→生图→回填 | `review_frame_prompt` |
| `storyboard_frame_gen` | 分镜首尾帧生成 | 按镜头分发首尾帧生图 | `generate_storyboard_frame` |
| `storyboard_frame_executor` | 首尾帧执行器 | 单镜头：查上下文→编排prompt→校正→生图→回填 | `review_frame_prompt` |
| `storyboard_video_gen` | 分镜视频生成 | 按镜头分发视频生成 | `generate_storyboard_video` |
| `storyboard_video_executor` | 视频执行器 | 单镜头：查上下文→编排prompt→校正→生视频→回填 | `review_video_prompt` |
| `prompt_reviewer` | Prompt 校正 | 校正生图/生视频提示词 | 无（纯推理） |

### 架构关键设计

- **子 Agent 并行**：同一阶段可同时调用多个子 Agent 实例（如多镜头并行生图）
- **串行阶段隔离**：资产图生成中 initial → three_view 严格分阶段，不可并行
- **递归子 Agent**：支持孙级 Agent 递归注册（如 executor → prompt_reviewer）
- **取消机制**：工具执行前检查 `AgentCancellationToken`，已取消则跳过
- **流式事件 Hook**：子 Agent 通过 `StreamingEventHook` 共享流式输出，前端实时可见

---

## 二、提示词质检 Agent 体系

### 2.1 Prompt 校正 Agent (`prompt_reviewer`)

所有生图/生视频执行器在调用 `generate_image` / `generate_video` **之前必须调用** `review_frame_prompt` / `review_video_prompt`。

**6 项必检 Checklist：**

- **空间一致性**：人物方位、前后景层次、机位高度与分镜 content 一致
- **景别运镜**：景别/运镜描述与 shotType/cameraMovement 匹配，缺失自动补全
- **参考图编号**：prompt 中"图片N"编号与参考图列表一一对应，role 描述匹配，**参考图交互一致性**（首尾帧：角色/道具/场景参考图需描述交互关系而非孤立罗列）
- **能力合规**：不支持参考图时移除引用，maxImageInputs 超限时截断
- **题材画风**：画风词不篡改剧本题材/时代/文化
- **负向提示词与运动**（视频）：负面清单完整、运动幅度与时长匹配、一镜一运动、可读信息、角色锁定标签

### 2.2 分镜内容复核 Agent (`storyboard_content_reviewer`)

分镜编写器在 **保存前** 调用 `review_storyboard_content`，复核每个镜头的 content 和 sceneExpectation。

**5 项必检：**

- **场景锚点**：content 是否明确主体在场景中的空间位置
- **资产唯一性**：同一场景资产不可被描述为多个实例
- **空间关系**：多人物时是否有左右关系和面对方向
- **道具锚点**：关联道具的镜头是否写明道具位置
- **剧情忠实度**：画面描述与剧本场次情节一致，不擅自添加/遗漏

**流程顺序（已修正）：** 设计镜头 → 复核 → 用修正后的内容保存（先 review 再 save）

---

## 三、剧本诊断 Agent (`script_doctor`)

在剧本解析完成后、分镜生成前执行，输出诊断报告。

**诊断范围（12 项全覆盖）：**
钩子留存、欲望动机、冲突升级、因果逻辑、人物塑造、对白质量、信息管理、结构节拍、可拍性/AIGC 友好、情绪主题、平台合规、篇幅效率

**输出格式：** 总判 → 评分卡 → 致命伤 → 优化项 → 处方优先级 → 最小改动方案 → 改写示范 → 复检清单

**职责边界：** 只诊断和开处方，不擅自改写全文，不生成分镜，不篡改题材。

---

## 四、资产图片生成链路

### 4.1 分阶段调度

- **Phase 1（初始图）**：所有 `initial` 子资产并行生成
- **Phase 2（衍生图）**：`three_view` / `costume` / `variant` 等，**必须等 Phase 1 全部完成后**才调度
- **同资产合并**：同一资产的 initial + three_view 可合并为一次调用，executor 按顺序处理

### 4.1.1 角色基体-服装分层（initial / costume 分离）

**核心痛点**：原方案角色初始图同时承载长相+服装，导致换装时无法复用长相、分镜生图时角色着装不一致。

**分层模型**：

| 子资产类型 | 语义 | 服装描述 | 用途 |
|---|---|---|---|
| `initial` | 素体基体 | 素色紧身衣/中性内衣 | 角色长相、体型、五官参考 |
| `costume`（默认常服） | 日常着装 | 从 `properties.clothing` 提取 | 角色默认服装参考 |
| `costume`（特殊换装） | 特定场合服装 | 如警服、婚纱 | 分镜 `costumeIds` 引用 |
| `variant` | 状态变化 | 含可能伴随的服装变化 | 受伤/年龄/变身等 |
| `three_view` | 多角度参考 | 与 initial 一致（素体） | 三视图参考 |

**默认常服自动创建**：
- `AssetService.create` 创建角色时自动生成 `itemType=costume`、名称为「XX 常服」的子资产
- 从主资产 `properties.clothing` 提取日常着装写入常服 `properties`（过滤空值/素色内衣/「无」）
- `clothing` 字段语义澄清为「默认常服」（非初始基体内衣）

**常服生图描述来源优先级**：
1. 目标子资产 `properties.clothing`
2. 子资产 name 中的服装语义
3. 主资产 `properties.clothing` / 剧本日常着装

**生图门禁规则更新**：
- lead/support：生 initial + three_view + 默认常服 costume
- extra：生 initial + 默认常服 costume，不生 three_view
- background：不生图

### 4.2 参考图编排规则

| 生图类型 | 参考图组织 | Prompt 引用声明 |
|---|---|---|
| 初始图 + 无风格图 | 不传参考图 | 无 |
| 初始图 + 有风格图 | `[风格图URL]` | 仅参考画风，不参考物品和构图 |
| 衍生图 | `[初始图URL, 风格图URL, ...]` | 参考角色长相/设计，画风仅参考技法 |
| 跨资产参考 | 关联资产初始图放参考图列表**末尾** | 场景图可引用关联道具图，道具图可引用关联场景图 |

### 4.3 硬约束

- **`requireReferenceImages: true`**：衍生图生成时必须传此标记，工具层校验参考图已传入，未传则直接报错
- **模型不支持参考图时**：`failOnUnsupportedRef` 可配置，默认直接报错而非静默降级为纯文生图
- **跨资产参考图显式传递**：调度器通过 `relatedReferenceImageUrls` 在 message 中传入关联资产初始图 URL

### 4.4 资产查询 NPE 修复

`QueryAssetItemsToolExecutor.buildAssetResult` 在获取 `initial` 类型资产的 `imageUrl` 时，未过滤 null/blank 值即调用 `findFirst()`，当资产尚未生成图片时抛出 `NullPointerException`。

**修复方式：** 在 `findFirst()` 前增加 `.filter(StrUtil::isNotBlank)` 过滤，安全返回 `null` 而非抛异常。

---

## 五、分镜首尾帧生成链路

### 5.1 批量生成结构化

- 前端传 `itemFramePrompts: [{ storyboardItemId, framePrompt }]` 结构化数组
- 主 Agent 按 `storyboardItemId` 精确匹配每个镜头的 prompt
- **不再使用 `---` 字符串拼接**，避免拆分错位

### 5.2 参考图强制

- `suggestedReferenceImages` 非空时，执行器**必须传 `requireReferenceImages: true`**
- 工具层校验：模型支持参考图但未传 imageUrls → 直接报错
- 提示用户从 `suggestedReferenceImages` 提取 URL 传入 `imageUrls`

### 5.3 generate_image 工具 Schema 修复

`GenerateImageToolExecutor.getParametersSchema()` 缺少 `requireReferenceImages` 字段，且 `imageUrls` 描述不够明确，导致 AI agent 在生成分镜首帧时遗漏参考图（`afv_image_task.ref_image_urls` 为空）。

**修复方式：**

- 新增 `requireReferenceImages` boolean 字段到工具 schema，描述"设为 true 时未传 imageUrls 将直接报错"
- `imageUrls` 描述改为动态：模型不支持时提示"请不要传该字段"，支持时明确"当有角色/场景/道具/画风参考图时必须传入"
- `imageUrls` 描述根据当前默认模型能力动态生成（`capability.supportsReferenceImages()`）

### 5.4 参考图优先级

系统预计算 `suggestedReferenceImages`，按优先级排序：
画风参考图（最多2张）> 角色三视图 > 角色初始图 > 服装（costume）> 道具图 > 场景图

**默认常服回落**：当镜头 `costumeIds` 为空时，系统自动回落到角色的默认「XX 常服」costume 子资产 imageUrl，确保分镜生图时角色始终有服装参考（initial 是素体，不自带成衣）。

**动态上限**：`maxReferenceImages` 不再硬编码为 5，而是从图片模型能力配置动态获取（`GenerationModelCapabilityService.resolveImageCapability`），默认回退 10。`generate_image` 工具 schema 的 `imageUrls` 描述同步显示当前模型支持的最大参考图数量。

### 5.5 参考图动态交互组织

首尾帧 prompt 中的参考图引用从**固定模板**改为**动态组织**：根据镜头 `content` 描述角色/道具/场景之间的交互关系（如"角色手中拿着道具，走在场景中"），而非孤立罗列。校正 Agent 新增参考图交互一致性检查项，验证并补全缺失的交互描述。详见 §6.9。

---

## 六、分镜视频生成链路

### 6.1 视频提示词校正

视频执行器在调用 `generate_video` 前必须调用 `review_video_prompt`，额外检查：
- 负向提示词完整性（脸崩、肢体畸形、水印等默认清单 + 镜头类型专属负面项）
- 运动幅度与时长匹配（2-3 秒镜头不可大范围运动）
- 一镜一运动（禁止动作堆叠）

### 6.2 模型能力适配

- Seedance 系列精确兜底：code 包含 `seedance` 时，已知支持首帧/尾帧/参考图/参考视频/参考音频
- 视频 trim 支持 `failOnUnsupportedRef`：不支持的字段可配置直接抛异常
- `get_generation_model_capabilities` 工具返回当前模型能力快照，指导 Agent 正确传参

### 6.3 本地媒体路径转 base64

火山引擎 Ark Content Generation API 要求 `image_url`/`video_url`/`audio_url` 为公网可访问 URL 或 base64 data URL。系统无公网地址时，`VolcengineVideoStrategy` 直接传本地相对路径（`/media/...`）会导致 API 报 `image_url is not valid`。

**修复方式：** `VolcengineVideoStrategy` 新增 `toMediaUrl()` 方法，与 `DashScopeVideoStrategy`、`OpenAiImageStrategy` 等策略保持一致：

- **`/media/...`** → 读取本地文件 → 转 `data:image/jpeg;base64,...`
- **`/api/art-styles/...`** → 通过 `PresetArtStyleResourceResolver` 读取 classpath 资源 → 转 base64
- **`http(s)://`** 或 **`data:`** → 直接透传

`buildContents` 中所有 `image_url`、`video_url`、`audio_url` 字段均通过 `toMediaUrl()` 转换后发送。

### 6.4 首帧/尾帧与参考图互斥

火山引擎 API 不允许 `first_frame`/`last_frame` 与 `reference_image` 在同一次请求中混用，会返回 400 错误：`first/last frame content cannot be mixed with reference media content`。

`trimVideoTaskInputs` 仅按 `maxImageInputs` 裁剪，未考虑 API 的混用限制。

**修复方式：** `VolcengineVideoStrategy.buildContents` 中，当任务有首帧或尾帧时，跳过参考图并记录 warn 日志，在策略层兜底防止 API 报错。

### 6.5 视频生成策略路由机制

系统支持多平台视频生成，通过 `VideoGenerationStrategyRouter` 统一路由。图片生成策略路由逻辑同理。

#### 路由决策流程（两层匹配）

```
VideoGenerationConsumer.processTask()
  │
  ├─ videoGenerationStrategyRouter.resolve(model)
  │    │
  │    ├─ Step 1: 查 API 配置，获取 platform
  │    │   apiConfigService.getById(model.apiConfigId)
  │    │   → platform = "openai_compatible" / "volcengine" / "dashscope" / ...
  │    │
  │    ├─ Step 2: 按 platform 在 strategyMap 中匹配
  │    │   strategyMap = {
  │    │     "openai_compatible"      → OpenAiVideoStrategy,
  │    │     "volcengine"             → VolcengineVideoStrategy,
  │    │     "dashscope"              → DashScopeVideoStrategy,
  │    │     "newapi"                 → NewApiVideoStrategy,
  │    │     "google_flow_reverse_api"→ GoogleFlowReverseApiVideoStrategy,
  │    │   }
  │    │   → 命中策略
  │    │
  │    └─ Step 3: 协议级兜底（仅当 platform == "openai_compatible" 时触发）
  │        metadata = aiModelMetadataResolver.resolve(model)
  │        protocol = metadata.effectiveProtocol()  // "seedance" / "wan" / "kling" / ...
  │        preferredStrategy = PROTOCOL_TO_STRATEGY.get(protocol)
  │        if preferredStrategy exists in strategyMap:
  │            → 返回专用策略（优先于平台匹配的通用策略）
  │
  ├─ strategy.submit(task)   // 提交到平台
  ├─ strategy.poll(...)      // 轮询结果
  └─ persistVideoItems(task) // 持久化视频文件
```

#### 协议→策略映射表

| 模型协议 | 路由到的策略 | 策略类 | 适用场景 |
|---|---|---|---|
| `seedance` | `volcengine` | `VolcengineVideoStrategy` | 火山方舟 Seedance 系列（使用 ArkService SDK） |
| `wan` | `dashscope` | `DashScopeVideoStrategy` | 阿里通义万相 |
| `kling` | `kling` | (如有) | 快手可灵 |
| `jimeng` | `jimeng` | (如有) | 字节即梦 |
| `sora` | `openai_compatible` | `OpenAiVideoStrategy` | OpenAI Sora（multipart/form-data） |
| 其他/`generic` | 按平台路由 | `OpenAiVideoStrategy` | 通用兼容平台 |

#### 为什么需要协议级兜底

典型场景：火山方舟 API 通过 `openai_compatible` 平台接入（API URL 为 `https://ark.cn-beijing.volces.com/api/v3`），但模型协议为 `seedance`。

- **无兜底时**：平台匹配到 `OpenAiVideoStrategy` → 按 Sora 协议拼接 `POST /v1/videos` → 火山方舟无此端点 → **HTTP 404**
- **有兜底后**：检测到协议 `seedance` → 路由到 `VolcengineVideoStrategy` → 使用火山方舟 SDK `ArkService.createContentGenerationTask()` → 正确调用 `POST /api/v3/contents/generations/tasks`

#### 协议解析来源

`AiModelMetadataResolver.resolve()` 按优先级推断协议：

1. **显式配置**：`AiModel.modelProtocol` 数据库字段（最高优先级）
2. **推断**：`inferProtocol(platform, family, modelType)` — 按平台+家族自动推断
   - `volcengine` + modelType=3 → `seedance`
   - `dashscope` + modelType=3 → `wan`
   - `newapi` + family=`jimeng`/`kling`/`sora` → 对应协议
   - 其他 → `generic`

#### 已注册策略一览

| 策略类 | getName() | 提交方式 | 轮询方式 |
|---|---|---|---|
| `OpenAiVideoStrategy` | `openai_compatible` | HTTP POST `/v1/videos` (multipart) | HTTP GET `/v1/videos/{id}` |
| `VolcengineVideoStrategy` | `volcengine` | ArkService SDK `createContentGenerationTask` | ArkService SDK `getContentGenerationTask` |
| `DashScopeVideoStrategy` | `dashscope` | DashScope API | DashScope API |
| `NewApiVideoStrategy` | `newapi` | HTTP POST `/v1/video/generations` | HTTP GET `/v1/video/generations/{id}` |
| `GoogleFlowReverseApiVideoStrategy` | `google_flow_reverse_api` | 自定义 HTTP 客户端 | 自定义轮询 |

### 6.6 视频合成：选配裁剪 + 逐段转场效果

#### 功能概述

合成本集视频时，用户可通过配置对话框：
- **勾选要合成的视频段**（支持全选/取消全选）
- **裁剪每段视频的起止时间**（双滑块，帧级精度）
- **逐段独立选择转场效果**（每两个视频之间可用不同转场，20 种 FFmpeg xfade 转场 + 可调时长）

#### 后端实现

**请求体（`ComposeVideoReqVO`）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `segments` | `List<ComposeSegmentReqVO>` | 选配的视频段列表，null/空表示使用全部视频 |
| `transition` | `String` | 全局转场回退值（当 segment 未指定转场时使用） |
| `transitionDuration` | `Double` | 全局转场持续时间回退值（秒），默认 0.5 |

**`ComposeSegmentReqVO` 字段：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `itemId` | `Long` | 分镜条目 ID |
| `startSec` | `Double` | 裁剪起始时间（秒），null 表示从开头 |
| `endSec` | `Double` | 裁剪结束时间（秒），null 表示到结尾 |
| `transition` | `String` | 从上一段到此段的转场效果（仅对第 2 段及之后生效） |
| `transitionDuration` | `Double` | 转场持续时间（秒），null 表示用默认值 0.5 |

**合成流程（`VideoComposeService`）：**

```
submitCompose(episodeId, userId, segments, globalTransition, globalTransitionDuration)
  │
  ├─ collectSegments()  // 按 itemId 查视频 URL，按分镜 sortOrder 排序，复制 transition 字段
  │
  └─ doComposeSegments()
       ├─ 构建逐段转场数组 transitions[i]:
       │    优先取 segment[i+1].transition，为空回退到 globalTransition
       │    同理 transitionDurations[i]
       │
       ├─ 下载视频 → 如有裁剪参数 → runFfmpegTrim()（-ss/-to 在 -i 后，重编码保证帧精度）
       │
       ├─ 有任何转场 != "none" 且段数 >= 2:
       │    ├─ 段数 <= 4: runFfmpegXfadeConcat(transitions, transitionDurations)  // 逐对 xfade
       │    └─ 段数 > 4:  runFfmpegPairwiseMerge(transitions, transitionDurations) // 两两归并
       │
       ├─ 全部转场为 "none" 或仅 1 段:
       │    └─ runFfmpegFilterConcat()  // 普通 concat 拼接
       │
       └─ 所有策略失败 → runFfmpegConcatDemuxer() 流拷贝兜底
```

**逐段转场在两两合并中的索引追踪：**

`runFfmpegPairwiseMerge` 通过 `rightBoundaries` 列表追踪每个合并文件包含的最后一个原始视频索引。合并 a+b 时，用 `rightBoundaries[a]` 作为转场数组索引，确保归并排序中每一对都选到正确的转场效果。

**转场效果列表（FFmpeg xfade）：**

| 分类 | 效果 |
|---|---|
| 基础 | 无转场、淡入淡出、溶解 |
| 擦除 | 向左/右/上/下擦除、平滑左/右/上/下擦 |
| 滑动 | 向上/下/左/右滑动 |
| 圆形 | 圆形展开、圆形收缩 |
| 其他 | 淡入黑场、淡入白场、放大过渡 |

#### 前端实现

- **`compose-config-dialog.tsx`**：合成配置对话框
  - 视频列表：缩略图 + 镜号 + 时长 + checkbox 勾选
  - 全选/取消全选按钮
  - 双滑块裁剪起止时间（实时显示裁剪后总时长）
  - **逐段转场选择器**：每个视频项（第 2 段及之后）下方独立显示转场下拉框 + 时长输入（0.1~3 秒），第一段无转场
- **`page.tsx`**：合成按钮先打开配置对话框，确认后调用 `handleComposeEpisodeVideo(segments)`（转场信息随 segments 传递）
- **`storyboard.ts`**：`composeEpisodeVideo` API 传 `{ segments }`，每个 segment 内含 `transition` 和 `transitionDuration`

### 6.7 视频合成 Bug 修复

#### anullsrc 无限时长导致输出 19 小时

**问题：** `runFfmpegFilterConcat` 中 `anullsrc=r=48000:cl=stereo` 未限制 duration，生成无限长静音音频流，concat 后输出时长暴涨至 19 小时，ffmpeg 一直编码空帧。

**修复：** 用 `probeDuration()` 获取每段视频时长，给 `anullsrc` 加 `d={duration}` 参数，静音音频长度与视频一致。同时保留 `-shortest` 兜底。

**影响范围（所有 anullsrc 使用点均已修复）：**

| 方法 | 修复方式 |
|---|---|
| `runFfmpegFilterConcat` | `anullsrc=r=48000:cl=stereo:d={duration}` |
| `runFfmpegXfadeConcat` | `anullsrc=r=48000:cl=stereo,duration={duration}`（原有，确认正确） |
| `runFfmpegXfadePair` | `anullsrc=r=48000:cl=stereo,duration={duration}`（原有，确认正确） |
| `runFfmpegTwoStepConcat` | 作为 `-i` 输入 + `-shortest`（原有，确认正确） |

#### anullsrc 参数语法错误导致转场失效

**问题：** `runFfmpegXfadeConcat` 和 `runFfmpegXfadePair` 中 `anullsrc` 滤镜的参数分隔符用了逗号（`,`）而非冒号（`:`），导致 FFmpeg `filter_complex` 解析失败。xfade 解析失败后系统静默回退到无转场拼接，表现为不同转场设置但输出效果相同。

**修复：** 将 `anullsrc=r=48000:cl=stereo,duration=...` 改为 `anullsrc=r=48000:cl=stereo:d=...`，使用冒号分隔参数，同时 duration 值格式化为 `%.3f`。

#### 服务重启后合成状态残留

**问题：** Java 服务重启后，`compose_status=1`（合成中）的记录和 `afv_agent_conversation.status=running` 的任务会话不被清理，导致 UI 永远显示"合成中"。

**修复：** `VideoComposeService` 新增 `@PostConstruct cleanupStaleComposeStatus()`，启动时自动：
- 将 `compose_status=1` 的 episode 重置为 `3`（失败）
- 将 `agent_type=storyboard_episode_compose` 且 `status=running` 的任务会话标记为 `failed`

---

## 6.8 火山方舟 Agent Plan 接入

### 背景与问题分析

火山方舟 Agent Plan 是面向 AI 编程助手/Agent 框架的服务包，兼容 OpenAI v3 接口协议，但与普通火山方舟（`volcengine` 平台）有关键差异：

| 维度 | 普通火山方舟 (`volcengine`) | Agent Plan (`volcengine_plan`) |
|---|---|---|
| Base URL | `https://ark.cn-beijing.volces.com` | `https://ark.cn-beijing.volces.com/api/plan/v3` |
| Completions Path | `/api/v3/chat/completions` | `/chat/completions`（相对于 plan/v3） |
| API Key | 方舟模型 API Key | Agent Plan 专属 API Key（不通用） |
| 模型 ID | 具体模型 endpoint ID | `ark-code-latest` 或具体模型名 |

原有 `volcengine` 平台硬编码了 `/api/v3/chat/completions` 路径，若用户填入 Agent Plan 的 Base URL，会导致路径重复拼接（`/api/plan/v3/api/v3/chat/completions`），请求 404。

### 解决方案：新增 `volcengine_plan` 平台

#### 后端改动

**`OpenAiCompatibleAiProvider.java`：**
- `SUPPORTED_PLATFORMS` 新增 `volcengine_plan`
- `resolveCompletionsPath` → `/chat/completions`（不含 `/api/v3` 前缀，已在 base URL 中）
- `resolveEmbeddingsPath` → `/embeddings`
- `resolveModelsPath` → `/models`
- `inferRootBaseUrl` → `https://ark.cn-beijing.volces.com/api/plan/v3`
- AgentScope 模型的 `stream(true)` 改为 `shouldStream(context)`，支持通过 config `stream: false` 关闭流式（Agent Plan 可能不支持 SSE）

**`ApiConfigService.java`：**
- `getPlatformDefaultApiUrl` 新增 `volcengine_plan` → `https://ark.cn-beijing.volces.com/api/plan/v3`

**`AiProviderContextFactory.java`：**
- URL 自动检测中，含 `/api/plan/` 的 URL 识别为 `volcengine_plan`（优先于 `volcengine` 判断）

**`AiModelMetadataResolver.java`：**
- family 推断中 `volcengine_plan` 归入 `doubao` 系列（文本模型）或 `seedance`（视频模型）

**`VideoGenerationStrategyRouter.java`：**
- `volcengine_plan` 平台路由到 `volcengine` 视频生成策略

**`ImageGenerationConsumer.java`：**
- `volcengine_plan` 平台路由到 `volcengine` 图片生成策略

**`VolcengineVideoStrategy.java` / `VolcengineImageStrategy.java`：**
- `buildArkService` 支持 `volcengine_plan` 默认 base URL（`https://ark.cn-beijing.volces.com/api/plan/v3`）

#### 前端改动

**`ai-model.ts`：**
- `PLATFORM_OPTIONS` 新增 `volcengine_plan`（标签：火山方舟 Agent Plan）
- `PLATFORM_LABELS` 新增 `volcengine_plan`

**`_shared.ts`：**
- `platformIconColors` 新增 `volcengine_plan`（与 `volcengine` 同色系）
- `getPlatformFields` 中 `volcengine_plan` 共用 `openai_compatible` 的字段配置（apiUrl + apiKey）

**`ai-models/page.tsx`：**
- `OPENAI_REASONING_PLATFORMS` 新增 `volcengine_plan`
- 视频 family 选项、协议推断、协议选项列表中 `volcengine_plan` 映射到 `seedance`

#### 兼容性

所有改动均为**纯新增**，不修改任何 `volcengine` 或 `openai_compatible` 的已有逻辑。现有配置完全不受影响。

#### 使用限制

Agent Plan 套餐明确限制：**文本生成模型及向量化模型不可用于 API 调用**，违规可能导致订阅停用或账号封禁。图片生成和视频生成模型无此限制，可正常用于后端 API 调用。

#### 已配置模型列表

| 模型类型 | 模型 Code | 说明 |
|---|---|---|
| 对话(1) | `minimax-m3` | 上下文 1024k / 输出 128k |
| 对话(1) | `glm-5.2` / `glm-latest` | 上下文 1024k / 输出 128k |
| 对话(1) | `kimi-k2.7-code` | 上下文 256k / 输出 32k |
| 对话(1) | `deepseek-v4-pro` | 上下文 1024k / 输出 384k（尝鲜版） |
| 对话(1) | `kimi-k3` | 上下文 1024k / 输出 128k |
| 图片(2) | `doubao-seedream-5.0-lite` | Seedream 5.0 Lite 文生图 |
| 视频(3) | `doubao-seedance-2.0` | Seedance 2.0 |
| 视频(3) | `doubao-seedance-2.0-fast` | Seedance 2.0 快速版 |
| 视频(3) | `doubao-seedance-2.0-mini` | Seedance 2.0 Mini |
| 语音合成(4) | `doubao-seed-tts-2.0` | Seed TTS 2.0 |
| 语音识别(5) | `doubao-seed-asr-2.0` | Seed ASR 2.0 |

> 文本模型已配置 `{"stream": false}`，因 Agent Plan 可能不支持 SSE 流式。

---

## 6.9 参考图动态交互组织（首尾帧提示词优化）

### 背景与问题

分镜首尾帧生成时，`storyboard_frame_executor` 引用 `suggestedReferenceImages`（角色图、道具图、场景图）的方式原为**固定模板**：

```
仅参考图片1的画面风格…，参考图片2的角色长相和设计，参考图片3的道具外观和细节，参考图片4的场景环境和氛围，
```

这种写法**孤立罗列**每张参考图，图片模型无法理解角色、道具、场景在画面中如何组合。例如镜头内容是"陈大海拿着外卖盒走过走廊"，理想 prompt 应描述交互关系：

```
参考图片2的角色（陈大海_灰蓝外卖服_湿发）手中拿着参考图片3的道具（外卖盒），走在参考图片4的场景（老旧走廊）中，
```

### 改动方案

**职责分工**：frame executor 负责动态组织（有完整上下文信息），prompt reviewer 负责验证和补全。

#### `storyboard-frame-executor.system.md`（核心改动）

参考图引用规则从固定模板改为**动态组织**：
- **画风参考图**：保持固定引用 `仅参考图片{index}的画面风格…`
- **角色+服装组合规则（重要）**：当 `suggestedReferenceImages` 同时包含 `role=character`（或 `character_three_view`）和 `role=costume` 时，**必须组合描述**：`参考图片{charIndex}的角色长相和面部特征，穿着参考图片{costumeIndex}的服装（{服装名}）`。角色初始图是素色内衣基体，不包含具体服装。
- **角色/道具/场景参考图**：根据镜头 `content` 和 `sceneExpectation` 动态组织交互关系
  - 角色+服装+道具+场景：`参考图片2的角色长相和面部特征，穿着参考图片3的服装（XX），手中拿着参考图片4的道具（XX），走在参考图片5的场景（XX）中`
  - 角色+服装+场景：`参考图片2的角色长相和面部特征，穿着参考图片3的服装（XX），站在参考图片4的场景（XX）旁`
  - 多角色+服装+场景：描述角色站位 + 各自服装 + 背景场景
  - 仅场景/仅角色+服装：简单描述
  - **兜底规则**：镜头无明确交互信息时退化为简单组合 `参考图片{index}的角色出现在参考图片{index}的场景中`

#### `prompt-reviewer.system.md`

`refImageIndex` 检查项新增**参考图交互一致性**子项：
- 检查 prompt 是否孤立罗列参考图（错误）vs 描述了交互关系（正确）
- **角色+服装组合校验（强制）**：若 referenceImages 同时含 character/character_three_view 与 costume，prompt 须组合为长相+服装引用。禁止把成衣写进 character 引用或漏引 costume。
- role 描述对齐分层：`character` → 只描述长相和面部特征（禁止挂成衣）；`character_three_view` → 长相和设计细节（禁止从三视图参考服装）；`costume` → 服装款式/材质/配色
- 给出正确/错误示例对比（含错误示例：把成衣写进 character、漏引 costume）
- 修复规则：结合 `shotContext.content` 重写为交互语句；漏引 costume 则补上角色+服装组合句
- 不适用场景：纯场景镜头或无交互信息时不强制

#### `asset-image-executor.system.md`

补充说明：资产生图（初始图/三视图/变体图）是**设定图**，画面主体单一，不存在角色与道具、场景之间的交互关系，保持固定模板引用即可。

### 数据流

```
frame executor 编写 draftPrompt（动态组织参考图交互）
    → prompt reviewer 校正（验证交互一致性，补全缺失的交互描述）
    → generate_image（图片模型理解角色/道具/场景如何组合）
```

---

## 6.10 首帧+参考图同时传能力开关（`supportsFirstFrameWithReference`）

### 背景与问题

`VolcengineVideoStrategy` 原硬编码 `!hasFrame` 条件 — 有首帧时参考图被完全丢弃。但不同模型对"首帧+参考图同时传"的支持不同：
- **Seedance 2.0**：首帧和参考图互斥（图生视频 vs 多模态参考是两种独立模式）
- **Seedance 2.5**：支持首帧 + 参考图同时传

原代码无法按模型配置区分，需要能力化驱动。

### 改动方案

#### 后端

- **`VideoModelCapability` record** 新增 `supportsFirstFrameWithReference` 字段
- **`GenerationModelCapabilityService`**：
  - `resolveVideoCapability`：解析模型 config 中的 `supportFirstFrameWithReference` 配置项
  - `inferVideoCapability`：从能力规则中读取 `supportFirstFrameWithReference`
  - `trimVideoTaskInputs`：`supportsFirstFrameWithReference=false` 且有首帧时，自动清空参考图
  - `buildVideoCapabilitySnapshot` / `describeVideoCapability`：输出新字段
- **`VolcengineVideoStrategy`**：去掉硬编码 `!hasFrame`，改为直接传参考图（互斥裁剪已由上游 `trimVideoTaskInputs` 处理）
- **`GetGenerationModelCapabilitiesToolExecutor`**：`toolGuidance` 中告知 Agent 首帧和参考图是否可同时传
- **`storyboard-video-executor.system.md`**：prompt 新增 `supportsFirstFrameWithReference` 说明

#### 前端

- **`settings/ai-models/page.tsx`**：
  - 视频模型配置面板新增"首帧+参考图同时传"开关（`ToggleSettingCard`）
  - 能力概览 chips 和 summary 中展示新字段状态

#### 能力规则

- 能力规则 JSON 中新增 `supportFirstFrameWithReference` 字段，默认 `false`
- Seedance 2.0 精确兜底中也默认 `false`（首帧/参考图互斥）

### 使用方式

AI 模型管理页面 → 编辑视频模型 → 高级配置 → 多模态输入能力区域：
- **开启**"首帧+参考图同时传"：首帧和参考图都传给 API（适用于 Seedance 2.5 等支持组合的模型）
- **关闭**（默认）：有首帧时自动丢弃参考图（适用于 Seedance 2.0 等首帧/参考图互斥的模型）

---

## 七、Agent Token 消耗监控与 AFP 估算

基于火山方舟 Agent Plan 套餐内 AFP 抵扣规则，实现 **每次 LLM 推理调用的 token 用量实时追踪 + AFP 成本估算**，覆盖主 Agent 和所有子 Agent。

### 7.1 数据流

```
LLM 返回 ChatUsage（inputTokens / outputTokens）
  → PostReasoningEvent.reasoningMessage.getChatUsage()
  → StreamingEventHook 推送 TOKEN_USAGE SSE 事件（含 agentName / parentToolCallId）
  → pipeline-store 累积到 TimelineItem / SubTimelineItem
  → timeline.tsx 渲染 TokenUsageBadge（输入/输出/合计 token + AFP 估算）
  → 同时持久化到 afv_agent_message 表（input_tokens / output_tokens / model_code）
```

### 7.2 后端实现

| 文件 | 改动 |
|---|---|
| `AiChatStreamRespVO.java` | 新增 `TokenUsageVO` 内部类（inputTokens, outputTokens, totalTokens, modelCode） |
| `StreamingEventHook.java` | `handlePostReasoning` 从 `Msg.getChatUsage()` 提取 token 用量，推送 `TOKEN_USAGE` 事件 |
| `AgentMessage.java` | 实体新增 `inputTokens`, `outputTokens`, `modelCode` 字段 |
| `AgentMessageMapper.java` | 新增 `selectTokenUsageByConversation` — 按 `parent_tool_call_id` 分组统计每个 Agent 的 token 消耗 |
| `AiAssistantController.java` | 新增 `GET /api/ai/assistant/conversations/{conversationId}/token-usage` 接口 |
| `AgentMessageService.java` | `saveAssistantMessage` 支持传递 token 字段 |
| `AgentScopeAssistantService.java` | `AssistantMessageAccumulator` 累积 token，`persistStreamEvent` 处理 `TOKEN_USAGE` 事件，`flushAssistantMessage` 传递到 DB |
| `V1.0.6.1.6__agent_message_token_usage.sql` | DB migration：`afv_agent_message` 表新增 `input_tokens`, `output_tokens`, `model_code` 列 |

### 7.3 前端实现

| 文件 | 改动 |
|---|---|
| `ai-assistant.ts` | `AiChatStreamEvent` 新增 `tokenUsage` 字段，`OutputType` 新增 `TOKEN_USAGE`，导出 `TokenUsageInfo` 接口 |
| `ai-pipeline.ts` | 导出 `TokenUsageInfo` 类型 |
| `pipeline-store.ts` | `TimelineItem` / `SubTimelineItem` 新增 `token_usage` 类型；reducer 处理 `TOKEN_USAGE` 事件，主 Agent 推入主时间线，子 Agent 推入对应工具节点的 children |
| `timeline.tsx` | 新增 `TokenUsageBadge` 组件，展示输入/输出/合计 token 数 + AFP 实时估算；内置 AFP 抵扣系数表 |
| `notification-panel/timeline.tsx` | 兼容 `token_usage` 类型（跳过渲染，避免类型错误） |

### 7.4 AFP 估算公式

```
AFP = (inputTokens × inputCoeff + outputTokens × outputCoeff) / 10000
```

内置 Agent Plan 文本模型 AFP 抵扣系数表：

| 模型 | 输入系数 | 输出系数 |
|---|---|---|
| `doubao-seed-2.0-mini` | 0.25 | 0.25 |
| `doubao-seed-2.0-lite` / `deepseek-v4-flash` | 0.5 | 0.5 |
| `doubao-seed-2.1-turbo` / `doubao-seed-evolving` / `doubao-seed-2.0-code` | 2.5 | 2.5 |
| `minimax-m2.7` / `minimax-m3` | 2.5 | 2.5 |
| `kimi-k2.6` / `kimi-k2.7-code` | 4.5 | 4.5 |
| `glm-5.2` / `glm-latest`（限时 2.5 折） | 1.125 | 1.125 |
| `deepseek-v4-pro` | 5.5 | 5.5 |
| `kimi-k3` | 10 | 10 |

### 7.5 可视化效果

- **主 Agent**：每次推理完成后在时间线中显示金色徽章，展示 `↑输入 ↓输出 · 合计 · ≈ X.XX AFP`
- **子 Agent**：在子 Agent 工具节点的展开区域中显示紧凑版 token 徽章
- **API 查询**：`GET /conversations/{conversationId}/token-usage` 返回按 Agent 分组的汇总数据，可用于后续仪表盘或报表

---

## 八、模型能力管理

### 8.1 能力解析链路

```
模型 code + 元数据 → CapabilityRuleService 规则匹配 → inferImage/inferVideoCapability
    → 合并 model config / preset config → 最终能力（支持覆盖）
```

### 8.2 能力裁剪策略 (`trimImageTaskInputs` / `trimVideoTaskInputs`)

- 不支持的字段：默认清空 + warn 日志；`failOnUnsupportedRef=true` 时直接抛异常
- 超限截断：参考图超过 maxReferenceImages 时截断到前 N 张
- 画风参考图降级：裁剪时将 `/api/art-styles/` 路径的画风图降到末尾，优先保留角色/道具/场景图
- 首帧优先：maxImageInputs 限制时，优先保留首帧，其次参考图

### 8.3 默认模型门禁

- `get_generation_model_capabilities` 返回 `toolGuidance`：
  - 支持参考图 → 正常引导
  - **不支持参考图 → ⚠️ 警告**：建议切换到支持参考图的模型，无法保证角色/场景一致性

### 8.4 Seedance 精确兜底

当能力规则未命中但 code 包含 `seedance` 时，自动推断全能力为 true，避免因规则缺失导致 Seedance 模型被误判为不支持参考图。

### 8.5 错误分类

生图/生视频工具的异常捕获区分：
- **`[能力校验]`**：本地 BusinessException，模型能力不匹配（可预防）
- **`[生成失败]`**：上游 API 或系统错误（需排查）

### 8.6 日志降噪

能力规则命中的 `log.info` 降级为 `log.debug`，仅在未命中（warn）和 Seedance 兜底（info）时输出，减少高频日志噪音。

---

## 九、资产关联关系

### 9.1 数据模型

资产的 `properties` JSON 字段中增加 `relatedAssetIds` 数组，存储关联资产的主资产 ID。

### 9.2 自动写入

分镜保存时（`save_storyboard_scene_shots`），从镜头的 `sceneAssetItemId` 和 `propIds` 自动反写：
- 场景资产 → 关联道具资产 ID
- 道具资产 → 关联场景资产 ID
- 已有关联不重复写入，失败不影响分镜保存

### 9.3 查询时返回

`query_asset_items` 返回结果中包含 `relatedAssets` 列表（含关联资产的 initialImageUrl），供 executor 编排跨资产参考图。

---

## 十、可观测性

### 10.1 参考图实际使用可观测

- `generate_image` 返回结果包含 `referenceImageCount` 和 `referenceImageUrls`
- 前端任务中心 `ImageGenerateResult` 组件展示：
  - 参考图数量标签（N 张参考图 / 无参考图）
  - 参考图缩略图列表（最多 5 张）
  - 生成结果图 + 提示词

### 10.2 子 Agent 事件展示

- 前端 `pipeline-store` 处理子 Agent 事件时，优先用事件 `agentName` 替代 `unknown_sub_agent` 占位名
- `SUB_AGENT_FINISHED` 和 `ERROR` 事件回填真实 agentName
- `appendToToolChildren` 支持 `fallbackAgentName` 兜底

### 10.3 能力解析日志

- 规则命中：`log.debug`（降噪）
- 规则未命中：`log.warn`（需要关注）
- Seedance 兜底：`log.info`（首次确认）
- 参考图被裁剪：`log.warn`（需要排查模型配置）

---

## 十一、前端 UI 优化

### 11.1 赛博朋克浅色主题

- 全局 CSS 变量从暗色切换为浅色赛博朋克霓虹色板（青色/品红 accent）
- `layout.tsx` 默认主题从 dark 改为 light
- 认证页面（登录/注册/忘记密码/初始化）使用主题变量替代硬编码暗色
- `auth-layout` 背景和霓虹色适配浅色
- `app-header` 和 `glow-menu` 正确显示浅色主题
- Dashboard 渐变文字适配

### 11.2 任务中心展示增强

- `ImageGenerateResult`：参考图数量 + 缩略图
- `VideoGenerateResult`：封面图 + 视频预览 + 时长
- `GenerationModelCapabilitiesResult`：模型能力快照可视化
- `AssetListResult`：资产列表摘要展示
- `MutationResult`：写入操作结果友好展示

---

## 十二、关键文件索引

### 后端 Java

| 文件 | 职责 |
|---|---|
| `AiAgentRegistry.java` | 所有 Agent 定义与注册 |
| `AiAgentDefinition.java` | Agent 定义数据结构 |
| `AiAssistantService.java` | Agent 调度（LangChain4j 路径） |
| `AgentScopeAssistantService.java` | Agent 调度（AgentScope 路径） |
| `GenerateImageToolExecutor.java` | 图片生成工具（含 requireReferenceImages、错误分类、动态 maxReferenceImages schema） |
| `GenerateVideoToolExecutor.java` | 视频生成工具（含错误分类） |
| `VolcengineVideoStrategy.java` | 火山引擎视频策略（本地路径转 base64、首帧+参考图能力驱动） |
| `GenerationModelCapabilityService.java` | 模型能力解析、裁剪、校验、Seedance 兜底、supportsFirstFrameWithReference、maxReferenceImages 默认 10 |
| `CapabilityRuleService.java` | 能力规则匹配引擎 |
| `SaveStoryboardSceneShotsToolExecutor.java` | 分镜保存（含 relatedAssetIds 反写、costumeIds 透传） |
| `QueryAssetItemsToolExecutor.java` | 资产查询（含关联资产、依赖警告、子资产 properties 返回） |
| `GetGenerationModelCapabilitiesToolExecutor.java` | 模型能力查询工具（含门禁警告） |
| `VideoComposeService.java` | 视频合成服务（选配裁剪、转场效果、anullsrc 修复、启动清理 stale 状态） |
| `ComposeSegmentReqVO.java` | 合成视频段配置请求体（itemId + startSec + endSec + transition + transitionDuration） |
| `ComposeVideoReqVO.java` | 合成视频请求体（segments + transition + transitionDuration） |
| `OpenAiCompatibleAiProvider.java` | OpenAI 兼容提供商（支持 volcengine_plan 平台、stream 开关） |
| `AiProviderContextFactory.java` | AI 提供商上下文工厂（URL 自动检测区分 volcengine_plan） |
| `AiModelMetadataResolver.java` | 模型元数据解析（volcengine_plan family 推断） |
| `VideoGenerationStrategyRouter.java` | 视频生成策略路由（volcengine_plan → volcengine 策略） |
| `ImageGenerationConsumer.java` | 图片生成消费者（volcengine_plan → volcengine 策略） |
| `StreamingEventHook.java` | AgentScope 流式事件 Hook（提取 ChatUsage 推送 TOKEN_USAGE 事件） |
| `AgentMessageMapper.java` | Agent 消息 Mapper（selectTokenUsageByConversation 按 Agent 分组统计 token） |
| `AiAssistantController.java` | AI 助手 Controller（新增 token-usage 查询接口） |

### 后端 Prompt

| 文件 | 职责 |
|---|---|
| `prompt-reviewer.system.md` | 提示词校正 Agent（6 项 checklist + 参考图交互一致性 + 角色/服装分层校验） |
| `storyboard-content-reviewer.system.md` | 分镜内容复核 Agent（5 项 checklist） |
| `script-doctor.system.md` | 剧本诊断 Agent（12 项诊断） |
| `episode-storyboard-writer.system.md` | 分镜编写器（含复核流程、空间锚点规则、costumeIds 匹配与默认常服回落） |
| `asset-image-generation.system.md` | 资产图调度器（分阶段、跨资产、同资产合并） |
| `asset-image-executor.system.md` | 资产图执行器（参考图编排、requireReferenceImages、设定图固定模板、costume 描述来源优先级） |
| `storyboard-frame-gen.system.md` | 首尾帧调度器（itemFramePrompts 匹配） |
| `storyboard-frame-executor.system.md` | 首尾帧执行器（参考图动态交互组织、角色+服装组合规则、requireReferenceImages） |
| `storyboard-video-executor.system.md` | 视频执行器（负向提示词、运动匹配、supportsFirstFrameWithReference、costume role 引用） |

### 前端

| 文件 | 职责 |
|---|---|
| `pipeline-store.ts` | Pipeline 事件处理（子 Agent 名称回填） |
| `generation-media-result.tsx` | 生成结果展示（参考图缩略图） |
| `results.tsx` | 工具结果路由与展示 |
| `storyboards/page.tsx` | 批量首尾帧生成（itemFramePrompts 结构化）、合成配置对话框集成 |
| `storyboards/_components/compose-config-dialog.tsx` | 合成配置对话框（勾选视频、裁剪滑块、转场选择、全选） |
| `storyboard-frame-reference-dialog.tsx` | 首尾帧 prompt 构建（buildDefaultFramePrompt） |
| `globals.css` | 赛博朋克浅色主题变量 |
| `auth-layout.tsx` | 认证页面布局（浅色适配） |
| `app-header.tsx` | 顶部导航（浅色适配） |
| `glow-menu.tsx` | 霓虹菜单（浅色适配） |
| `ai-model.ts` | AI 模型 API 与常量（PLATFORM_OPTIONS 含 volcengine_plan） |
| `settings/_shared.ts` | 设置页共享工具（平台颜色、字段配置含 volcengine_plan） |
| `settings/ai-models/page.tsx` | AI 模型管理页（volcengine_plan reasoning/family/protocol 支持、supportsFirstFrameWithReference 开关） |
| `ai-assistant.ts` | AI 对话 API（AiChatStreamEvent 新增 tokenUsage 字段、TokenUsageInfo 接口） |
| `agent-pipeline/timeline.tsx` | Pipeline 时间线（TokenUsageBadge 组件 + AFP 抵扣系数表 + 实时估算） |
| `notification-panel/timeline.tsx` | 通知面板时间线（兼容 token_usage 类型） |

---

## 十三、质量保障机制总结

```
剧本创作
  └─ script_doctor 诊断（12 项）
       │
分镜编写
  └─ episode_storyboard_writer
       ├─ 空间锚点 / 资产唯一性 强制规则
       ├─ storyboard_content_reviewer 复核（5 项，保存前）
       └─ relatedAssetIds 自动反写
            │
资产图片生成
  └─ asset_image_gen
       ├─ 分阶段调度（initial → three_view/costume 硬串行）
       ├─ 角色基体-服装分层（initial 素体 + costume 常服 + 特殊换装）
       ├─ 默认常服自动创建（properties.clothing → 常服 properties）
       ├─ 跨资产参考图显式传递
       ├─ requireReferenceImages 硬校验
       └─ prompt_reviewer 校正（6 项）
            │
首尾帧生成
  └─ storyboard_frame_gen
       ├─ itemFramePrompts 结构化匹配
       ├─ suggestedReferenceImages 强制传入（动态 maxReferenceImages 上限）
       ├─ 默认常服回落（无 costumeIds 时自动补常服参考图）
       ├─ requireReferenceImages 硬校验
       ├─ 参考图动态交互组织（角色长相+服装组合 + 道具/场景交互关系）
       └─ prompt_reviewer 校正（6 项 + 参考图交互一致性 + 角色/服装分层校验）
            │
视频生成
  └─ storyboard_video_gen
       ├─ 负向提示词 / 运动幅度 / 一镜一运动
       ├─ Seedance 能力精确兜底
       ├─ failOnUnsupportedRef 可配置
       ├─ 本地路径转 base64 data URL（无需公网地址）
       ├─ 首帧+参考图能力开关（supportsFirstFrameWithReference，按模型配置决定是否互斥）
       └─ prompt_reviewer 校正（6 项 + 视频专属）
            │
视频合成
  └─ VideoComposeService
       ├─ 选配裁剪（按 itemId 勾选 + 时间段裁剪，帧级精度）
       ├─ 逐段转场效果（每两段独立配置，20 种 xfade + 可调时长）
       ├─ anullsrc duration 限制（防止无限长音频）
       ├─ 多策略兜底（xfade → filter concat → concat demuxer）
       └─ 启动清理 stale 状态（episode + 任务中心双重清理）
            │
全链路
  ├─ 模型能力门禁（不支持参考图时 ⚠️ 警告）
  ├─ 错误分类（能力校验 vs 上游 API）
  ├─ 参考图实际使用可观测（返回 + 前端展示）
  └─ 能力日志降噪（debug 命中 / warn 未命中）
```
