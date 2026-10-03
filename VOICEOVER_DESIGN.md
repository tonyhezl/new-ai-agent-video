# AI 配音功能设计方案（现状对齐 + MVP 可实施版）

> **修订说明**：本版基于 2026-08-09 代码库实际状态重写。原版设计存在数据源双轨、字段语义误判、时间轴缺失、Agent 定位过重等问题，已全部修正。
>
> **落地更新（2026-08-10）**：Phase 1 + Phase 2 已全部落地，详见 [八、落地实现记录](#八落地实现记录)。

## 一、需求概述

为项目中的分镜提供 AI 配音能力，覆盖从台词到最终音频合成的全流程：

1. **角色音色绑定**：为角色资产分配固定 TTS 音色，保证全剧声音一致
2. **批量配音生成**：按分镜镜头的 `dialogue` 逐句生成语音
3. **配音试听与重生成**：前端可试听、可单独重生成某条配音
4. **对白音轨合成**：将一集所有镜头的对白音频按时间轴拼合成完整对白轨
5. **与视频合流**：视频合成阶段将对白轨叠加到最终视频

## 二、现有架构现状（2026-08-09 实测）

### 已具备

| 能力 | 位置 | 说明 |
|------|------|------|
| TTS/STT 模型类型枚举 | `AiModelTypeEnum` | `TTS(4)` / `STT(5)` 已定义 |
| 前端模型类型选项 | `ai-model.ts` MODEL_TYPE_OPTIONS | 含"语音合成""语音识别" |
| TTS 模型记录 | DB `afv_ai_model` id=121 | `doubao-seed-tts-2.0`，但 `config=NULL` |
| 视频参考音频 | `VideoTask.referenceAudioUrls` | DashScope/Volcengine 策略已支持 |
| 视频生成音频 | `VideoTask.generateAudio` | 部分策略已支持 |
| FFmpeg 合成 | `VideoComposeService` | 已用 ffmpeg/ffprobe，含音频流归一化 |
| Redis 队列 Consumer | `ImageGenerationConsumer` / `VideoGenerationConsumer` | 可复用模式 |
| Strategy + Router | `VideoGenerationStrategyRouter` 等 | 可复用模式 |
| Media 存储 | `MediaStorageService` | 可复用 |
| Agent + Tool | `AiAgentRegistry` + `*ToolExecutor` | 可复用 |
| `get_storyboard_scene_items` | 已存在 | 返回 dialogue / characterRefs / propRefs |
| `Asset.properties` JSON | 已存在 | 可扩展 `voiceConfig`，无需改表 |

### 已落地（2026-08-10 更新）

| 原缺口 | 落地状态 | 实现位置 |
|------|------|------|
| `AudioTask` 表与实体 | ✅ 已实现 | `afv_audio_task` 表 + `AudioTask` 实体 + Mapper + Service |
| `AudioGenerationStrategy` 接口 + 实现 | ✅ 已实现 | `AudioGenerationStrategy` + `VolcengineAudioStrategy` |
| `AudioGenerationConsumer` | ✅ 已实现 | Redis 队列消费，按 modelId 分队列 |
| `StoryboardItem.audio_url` 字段 | ✅ 已实现 | `audio_url` + `audio_status` 字段 |
| 角色音色 UI | ✅ 已实现 | `VoiceConfigDialog` 组件 |
| 分镜配音面板 | ✅ 已实现 | `VoiceoverPanelDialog` 组件 |
| 对白音轨合成 | ✅ 已实现 | `VideoComposeService.mixVoiceoverForItem` |
| TTS 模型 config | ✅ 已配置 | `afv_ai_model` id=121 已填入 voiceList |
| 台词解析器 | ✅ 已实现 | `DialogueParser.parse()` |
| 批量配音 API | ✅ 已实现 | `VoiceoverController` |
| 配音预览 API | ✅ 已实现 | `previewDialogue` 支持 sceneId 过滤 |
| 视频合流 | ✅ 已实现 | 合成时自动混入配音音频 |
| `storyboard_voiceover` Agent | ⏸ 暂缓 | Agent 降为可选增强，当前用服务端 API |
| `CapabilityRuleService` 支持 TTS | ⏸ 暂缓 | 当前直接按 modelType=4 路由 |

### 关键数据模型现状

| 实体 | 字段 | 类型 | 说明 |
|------|------|------|------|
| `StoryboardItem` | `dialogue` | text | **纯文本**，格式如 `角色名：台词`，一镜可多句 |
| `StoryboardItem` | `sound` | text | **文本描述**（如"低沉 BGM"），非 URL |
| `StoryboardItem` | `soundEffect` | varchar(512) | **文本描述**，非 URL |
| `StoryboardItem` | `music` | varchar(512) | **文本描述**，非 URL |
| `StoryboardItem` | `characterIds` | json | 子资产 ID 列表，可解析为角色名 |
| `StoryboardItem` | `duration` | decimal | 镜头预估时长（秒） |
| `StoryboardItem` | `audio_url` | — | **不存在**，需新增 |
| `DialogueElement` | `type` | string | 1-对白 2-动作 3-画外音 4-镜头指令 5-环境描写 |
| `DialogueElement` | `character` | string | 角色名（type=1/3 时有值） |
| `DialogueElement` | `content` | string | 文本内容 |
| `Asset` | `properties` | json | 可扩展 `voiceConfig` |

### 现有生成流程

```
剧本 → script_full_parse → 场次(SceneItem) + 对白(DialogueElement)
                                    ↓
分镜 → storyboard_gen → 镜头(StoryboardItem) + dialogue + sound + soundEffect
                                    ↓
生图 → generate_image → 首帧/尾帧
生视频 → generate_video → 视频片段（可选 generateAudio）
视频合成 → VideoComposeService → FFmpeg concat → 最终视频
```

## 三、设计方案

### 3.1 核心原则

1. **唯一配音源：分镜镜头级 `dialogue`**——与成片时间线一致
2. **剧本 `DialogueElement` 仅作辅助**：补全角色名、校验漏句、旁白/对白分类
3. **一镜可多句**：每句对白独立生成一个音频文件，按 `seq` 排序
4. **主路径：服务端批量 API**，不依赖 Agent 逐条调用
5. **MVP 只做对白轨**：BGM/SFX 混音后置（`sound`/`soundEffect` 是文本描述，非 URL）
6. **TTS 策略同步优先**：多数 TTS API 同步返回音频字节

### 3.2 整体流程

```
角色管理页绑定音色
  → Asset.properties 写入 voiceConfig {voiceId, speed, pitch, emotion}

用户点击"AI 配音"
  → POST /api/storyboard/{storyboardId}/voiceover/generate
  → 服务端扫描该集所有镜头
  → 解析每个镜头 dialogue → 拆分为 N 条 AudioLine（角色/文本/seq）
  → 匹配角色 → 获取 voiceConfig（未绑定用模型默认音色）
  → 逐条创建 AudioTask → 入 Redis 队列
  → AudioGenerationConsumer 消费 → 调用 TTS Strategy → 生成音频文件
  → 前端可试听、重生成
  → 全部完成 → 可选"合成对白音轨"（FFmpeg adelay + amix 按时间轴拼合）
  → 视频合成时将对白轨叠加到最终视频
```

### 3.3 配音范围与台词解析

#### 配音范围

| DialogueElement.type | 含义 | 是否配音 | 说明 |
|---|---|---|---|
| 1 | 对白 | ✅ | 角色台词 |
| 2 | 动作描写 | ❌ | 无语音 |
| 3 | 画外音 | ✅ | 使用旁白音色 |
| 4 | 镜头指令 | ❌ | 运镜指导，不是台词 |
| 5 | 环境描写 | ⚠️ 可配置 | 常含对讲机/旁白式内容，按项目配置决定 |

#### 台词解析规则（确定性，非 LLM）

分镜 `StoryboardItem.dialogue` 是纯文本，常见格式：

```
顾还生：急什么。收完地上的——下一个,轮到天。
天音：找到了……天地的化身……
（旁白）远古传说……
```

解析步骤：
1. 按换行拆分多句
2. 每句用 `角色名：` 或 `（旁白）：` 前缀匹配角色
3. 无前缀的句子：继承上一句的角色
4. 角色名匹配：优先用 `characterIds` 对应的主资产 `name`，其次模糊匹配
5. 旁白：使用项目级 `defaultNarratorVoice`（无角色资产匹配）

### 3.4 角色音色绑定

**方案**：在 `Asset.properties` JSON 中新增 `voiceConfig` 字段，无需改表。

```json
{
  "voiceConfig": {
    "voiceId": "zh_female_qingxin",
    "voiceName": "清新女声",
    "speed": 1.0,
    "pitch": 1.0,
    "emotion": "neutral",
    "customVoiceId": null,
    "referenceAudioUrl": null
  }
}
```

**绑定层级**：`voiceConfig` 绑在 **主 Asset（type=character）**，不是 AssetItem。同一角色的所有子资产共享同一音色。

**旁白音色**：项目级配置，存在 `Project.properties` 中：
```json
{
  "narratorVoiceConfig": {
    "voiceId": "zh_male_wennuan",
    "voiceName": "温暖男声",
    "speed": 0.9
  }
}
```

**前端交互**：
- 角色管理页 → 角色卡片 → "音色设置"按钮
- 弹窗：预设音色下拉（从 TTS 模型 config.voiceList 加载）+ 语速/音调滑块 + 情感选择 + 试听
- 项目设置 → 旁白音色（可选，默认用模型 defaultVoice）

### 3.5 TTS 模型配置

#### 模型类型

`AiModelTypeEnum.TTS(4)` 已存在，无需新增枚举。

#### 模型 config 模板（火山引擎 Doubao TTS）

```json
{
  "voiceList": [
    {"id": "zh_female_qingxin", "name": "清新女声"},
    {"id": "zh_male_wennuan", "name": "温暖男声"},
    {"id": "zh_female_wanwanxiaohe", "name": "弯弯小何"}
  ],
  "defaultVoice": "zh_female_qingxin",
  "supportVoiceClone": true,
  "supportEmotion": true,
  "emotions": ["happy", "sad", "angry", "neutral", "surprised"],
  "defaultSpeed": 1.0,
  "defaultPitch": 1.0,
  "sampleRate": 24000,
  "audioFormat": "mp3",
  "maxTextLength": 500,
  "apiMode": "sync"
}
```

> **注意**：现网 `doubao-seed-tts-2.0` 的 `config=NULL`，需在 AI 服务管理页填入上述 config 后才能使用。

#### 支持的 TTS 平台

| 平台 | 模型示例 | apiMode | 特点 |
|------|---------|---------|------|
| `volcengine` | `doubao-seed-tts-2.0` | sync | 火山引擎 TTS，支持音色克隆、多情感 |
| `dashscope` | `cosyvoice` | sync | 阿里 CosyVoice，支持零样本音色克隆 |
| `openai_compatible` | `gpt-4o-mini-tts` | sync | OpenAI TTS，通用性好 |

### 3.6 新增实体：AudioTask

> **设计变更**：原版 `source_type` 含"场次对白/分镜台词"双轨，现统一为分镜镜头级。一镜多句时一个 `StoryboardItem` 对应多条 `AudioTask`。

```sql
CREATE TABLE afv_audio_task (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                 VARCHAR(64) NOT NULL UNIQUE COMMENT '业务任务ID（UUID）',
    project_id              BIGINT NOT NULL,
    user_id                 BIGINT,

    -- 关联来源（统一为分镜镜头级）
    storyboard_id           BIGINT NOT NULL COMMENT '分镜ID',
    storyboard_episode_id   BIGINT NOT NULL COMMENT '分镜集ID',
    storyboard_item_id      BIGINT NOT NULL COMMENT '关联 StoryboardItem.id',
    seq                     INT NOT NULL DEFAULT 0 COMMENT '该镜头内第几句对白（从0开始）',

    -- TTS 输入
    text                    TEXT NOT NULL COMMENT '待合成文本',
    character_name          VARCHAR(100) COMMENT '角色名（解析自 dialogue）',
    character_asset_id      BIGINT COMMENT '匹配到的角色主资产ID',
    voice_config            TEXT COMMENT '音色配置 JSON（voiceId/speed/pitch/emotion）',
    dialogue_type           VARCHAR(10) COMMENT '1-对白 3-画外音 5-环境描写(可配音)',

    -- 模型
    model_id                BIGINT COMMENT '关联 AiModel.id',
    model_code              VARCHAR(200),

    -- 输出
    audio_url               VARCHAR(1024) COMMENT '生成的音频文件URL',
    duration_ms             INT COMMENT '音频时长（毫秒）',
    sample_rate             INT COMMENT '采样率',

    -- 状态
    status                  INT DEFAULT 0 COMMENT '0-待处理 1-处理中 2-成功 3-失败',
    error_message           TEXT,

    -- 重试
    retry_count             INT DEFAULT 0,
    parent_task_id          VARCHAR(64) COMMENT '重试时的原任务ID',

    -- 逻辑删除（对齐项目惯例）
    deleted                 TINYINT NOT NULL DEFAULT 0,

    create_time             DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_project (project_id),
    INDEX idx_item (storyboard_item_id),
    INDEX idx_episode (storyboard_episode_id),
    INDEX idx_status (status)
);
```

### 3.7 StoryboardItem 新增字段

```sql
ALTER TABLE afv_storyboard_item
    ADD COLUMN audio_url VARCHAR(1024) COMMENT '对白混音URL（该镜头所有对白拼合后的预览音频）',
    ADD COLUMN audio_status INT DEFAULT 0 COMMENT '0-未生成 1-生成中 2-已生成 3-失败';
```

> **设计变更**：原版 `audio_duration_ms` 移入 `AudioTask`（每句有独立时长）。`StoryboardItem.audio_url` 仅存该镜头所有句拼合后的预览音频，便于前端直接播放。`audio_status` 用于前端显示生成状态。

### 3.8 TTS 生成策略

```java
public interface AudioGenerationStrategy {
    String getName();

    /**
     * 同步合成（主路径）。多数 TTS API 直接返回音频字节。
     * @return 音频文件 URL（已持久化到 MediaStorage）
     */
    String synthesize(AudioTask task, ApiConfig apiConfig);

    /**
     * 获取模型支持的音色列表（从 config.voiceList 解析或调 API 获取）。
     */
    List<VoiceOption> listVoices(AiModel model);
}
```

> **设计变更**：原版照搬视频异步 `submit/poll` 模式。多数 TTS（含豆包）是同步返回音频字节，强行 poll 别扭。改为同步优先；如遇特定平台需异步，可在 Strategy 内部封装 poll 逻辑后返回。

**火山引擎 TTS 策略**（`VolcengineAudioStrategy`）：
- 同步调用 TTS API → 返回音频二进制 → 存 MediaStorage → 返回 URL
- 支持音色克隆：先调克隆接口注册 → 得到 `customVoiceId` → 后续 TTS 用此 ID

### 3.9 AudioGenerationConsumer

复用 `ImageGenerationConsumer` 模式：

```java
@Component
public class AudioGenerationConsumer {
    // Redis 队列：audio_generation:model:{modelId}
    // 按 modelId 分队列，支持并发控制

    public void processTask(String queueName, String taskId) {
        AudioTask task = audioTaskService.getByTaskId(taskId);
        // 1. 选择策略（按 model.platform 路由）
        // 2. 校验文本长度（超长分段）
        // 3. strategy.synthesize(task, apiConfig) → 返回 audio_url
        // 4. 更新 task.status=2, audio_url, duration_ms
        // 5. 检查该镜头所有句是否完成 → 若完成则拼合预览音频 → 更新 StoryboardItem.audio_url
    }
}
```

### 3.10 服务端批量配音 API（主路径）

> **设计变更**：原版用 Agent 逐条调 `generate_audio`，慢/贵/不可靠。改为服务端批量 API。

```java
@RestController
@RequestMapping("/api/storyboard")
public class VoiceoverController {

    /**
     * 批量生成配音（按分镜集）
     * POST /api/storyboard/{storyboardId}/voiceover/generate
     * Body: { "episodeId": 17 }
     */
    @PostMapping("/{storyboardId}/voiceover/generate")
    public String generateVoiceover(...);

    /**
     * 单条重生成
     * POST /api/storyboard/voiceover/{audioTaskId}/regenerate
     */
    @PostMapping("/voiceover/{audioTaskId}/regenerate")
    public String regenerate(...);

    /**
     * 查询配音列表（按集）
     * GET /api/storyboard/voiceover/list?episodeId=17
     */
    @GetMapping("/voiceover/list")
    public String list(...);
}
```

**批量生成流程**：
1. 查该集所有镜头（按 sortOrder）
2. 逐镜解析 `dialogue` → 拆分为 AudioLine 列表
3. 每条 AudioLine 匹配角色 → 获取 voiceConfig
4. 创建 `AudioTask` → 入 Redis 队列
5. 返回任务总数 + 任务 ID 列表

### 3.11 AI Agent（可选增强，非主路径）

> **设计变更**：Agent 从"主路径"降为"可选增强"。主路径用服务端 API。Agent 仅用于：情感标注、旁白改写、难句拆分等智能辅助。

```java
private void registerStoryboardVoiceoverAgent() {
    register(AiAgentDefinition.builder()
        .type("storyboard_voiceover_assist")
        .name("配音辅助")
        .toolNames(List.of(
            "get_storyboard_scene_items",
            "get_project",
            "list_audio_tasks",
            "update_audio_task_emotion"
        ))
        .systemPrompt(loadPrompt("storyboard-voiceover-assist.system.md"))
        .build());
}
```

Agent 不直接调 TTS，只做：
- 分析台词情感 → 更新 AudioTask.emotion
- 识别旁白 vs 对白 → 更新 dialogue_type
- 难句拆分建议

### 3.12 时间轴与对白音轨合成

> **新增章节**（原版完全缺失）

#### 时间轴模型

```
镜头时间线：
  镜头1 (0s - 8s)     → 句1 start=0ms, 句2 start=3000ms
  镜头2 (8s - 30s)    → 句1 start=8000ms, 句2 start=15000ms
  ...

集对白轨 = 所有句按 startMs 排列，静音填充间隔
```

#### 句内偏移规则

- 每句 `startOffsetMs` = 镜头起点 + 前序句时长 + 间隔（默认 200ms）
- 若 `audio_duration_ms` > 剩余镜头时长 → 调 speed（0.8-1.5 范围）或告警
- 镜头间默认无间隔（紧接上一镜头结束）

#### FFmpeg 合成命令

```bash
# 对白轨合成（adelay 定位 + amix 混合）
ffmpeg -i clip1.mp3 -i clip2.mp3 ... \
  -filter_complex "[0:a]adelay=0|0[a0]; \
                   [1:a]adelay=3000|3000[a1]; \
                   ... \
                   [a0][a1]...amix=inputs=N:duration=longest[aout]" \
  -map "[aout]" dialogue_track.mp3
```

#### 与视频合流

在 `VideoComposeService` 现有合成流程末尾新增一步：

```bash
# 方案 A：替换原片音频（推荐）
ffmpeg -i composed_video.mp4 -i dialogue_track.mp3 \
  -c:v copy -c:a aac -map 0:v -map 1:a final.mp4

# 方案 B：混合原片音频（保留模型生成音效）
ffmpeg -i composed_video.mp4 -i dialogue_track.mp3 \
  -filter_complex "[0:a]volume=0.3[orig];[1:a]volume=1.0[voice];[orig][voice]amix=inputs=2[aout]" \
  -c:v copy -c:a aac -map 0:v -map "[aout]" final.mp4
```

**插入点**：`VideoComposeService.doCompose()` 拼接完镜头视频后、返回最终 URL 前。

### 3.13 前端交互设计

#### 角色音色设置

- **位置**：角色管理页 → 角色卡片 → "音色设置"按钮
- **弹窗内容**：
  - 预设音色下拉（从 TTS 模型 config.voiceList 加载，带试听按钮）
  - 语速、音调滑块
  - 情感选择（若模型支持）
  - 试听输入框（输入文字 → 实时合成预览）

#### 分镜配音面板

- **位置**：分镜页面 → 镜头详情中新增"配音"区域
- **功能**：
  - 批量配音按钮：调用 `POST /api/storyboard/{id}/voiceover/generate`
  - 每个镜头显示：台词文本（按句拆分）+ 每句音频播放器 + 时长 + 状态
  - 单条重生成按钮
  - 镜头级预览播放（拼合后的 `audio_url`）
  - 集级"合成对白音轨"按钮

#### 配音进度展示

复用现有 `TaskStreamService` + pipeline 面板：
```
AI 配音生成
  ├─ 镜头1 句1 "急什么。收完地上的…" → ✅ 2.3s
  ├─ 镜头1 句2 "下一个,轮到天。" → ✅ 1.1s
  ├─ 镜头2 句1 "找到了……天地的化身…" → ⏳ 生成中
  └─ 镜头3 无台词 → ⏭️ 跳过
```

## 四、数据库变更汇总

```sql
-- 1. StoryboardItem 新增音频字段
ALTER TABLE afv_storyboard_item
    ADD COLUMN audio_url VARCHAR(1024) COMMENT '对白混音URL（该镜头所有对白拼合后的预览音频）',
    ADD COLUMN audio_status INT DEFAULT 0 COMMENT '0-未生成 1-生成中 2-已生成 3-失败';

-- 2. 音频任务表（一镜多句，每句一行）
CREATE TABLE afv_audio_task (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                 VARCHAR(64) NOT NULL UNIQUE COMMENT '业务任务ID（UUID）',
    project_id              BIGINT NOT NULL,
    user_id                 BIGINT,
    storyboard_id           BIGINT NOT NULL COMMENT '分镜ID',
    storyboard_episode_id   BIGINT NOT NULL COMMENT '分镜集ID',
    storyboard_item_id      BIGINT NOT NULL COMMENT '关联 StoryboardItem.id',
    seq                     INT NOT NULL DEFAULT 0 COMMENT '该镜头内第几句对白（从0开始）',
    text                    TEXT NOT NULL COMMENT '待合成文本',
    character_name          VARCHAR(100) COMMENT '角色名',
    character_asset_id      BIGINT COMMENT '匹配到的角色主资产ID',
    voice_config            TEXT COMMENT '音色配置 JSON',
    dialogue_type           VARCHAR(10) COMMENT '1-对白 3-画外音 5-环境描写',
    model_id                BIGINT COMMENT '关联 AiModel.id',
    model_code              VARCHAR(200),
    audio_url               VARCHAR(1024) COMMENT '生成的音频文件URL',
    duration_ms             INT COMMENT '音频时长（毫秒）',
    sample_rate             INT COMMENT '采样率',
    status                  INT DEFAULT 0 COMMENT '0-待处理 1-处理中 2-成功 3-失败',
    error_message           TEXT,
    retry_count             INT DEFAULT 0,
    parent_task_id          VARCHAR(64) COMMENT '重试时的原任务ID',
    deleted                 TINYINT NOT NULL DEFAULT 0,
    create_time             DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project (project_id),
    INDEX idx_item (storyboard_item_id),
    INDEX idx_episode (storyboard_episode_id),
    INDEX idx_status (status)
);

-- 3. TTS 模型 config 由用户在 AI 服务管理页配置（模型记录已存在，需补充 config JSON）
-- 4. CapabilityRuleService 需扩展支持 modelType=4
```

> **不在 MVP 范围**：`AudioTrack` 表（BGM/SFX 混音）后置到 Phase 3，因 `sound`/`soundEffect` 是文本描述而非 URL，需先建音频资源库。

## 五、实施路线

### Phase 1：基础 TTS 能力 + 批量配音（MVP）✅ 已落地

- [x] Flyway: `afv_audio_task` 表 + `afv_storyboard_item` 新增字段
- [x] `AudioTask` 实体 + Mapper + Service
- [x] `AudioGenerationStrategy` 接口 + `VolcengineAudioStrategy`（火山引擎 Agent Plan TTS）
- [x] `AudioGenerationConsumer`（复用 Redis 队列模式）
- [x] `VoiceoverController`：批量生成 / 单条重生成 / 查询列表 / 预览对白
- [x] 台词解析器：`DialogueParser` → `dialogue` → AudioLine 列表（确定性解析）
- [x] 角色匹配：`characterIds` → 主资产 name → voiceConfig
- [x] `Asset.properties` 支持 `voiceConfig`（后端读写）
- [x] TTS 模型 config 已配置（voiceList 含 seed-tts-2.0 合法音色）
- [x] 前端：角色音色设置弹窗（`VoiceConfigDialog`）
- [x] 前端：分镜配音面板（`VoiceoverPanelDialog`，支持按场次过滤、勾选生成）

### Phase 2：对白音轨合成 + 视频合流 ✅ 已落地

- [x] 镜头级音频混入（`mixVoiceoverForItem`：多句拼接 + ffmpeg 混入视频）
- [x] `VideoComposeService` 合成时自动混入配音（方案 A：替换原片音频）
- [x] 时长匹配策略（音频 < 视频 → apad 补静音；音频 ≥ 视频 → -shortest）
- [x] 重生成任务状态修正（`checkItemCompletion` 按 seq 取最新任务）
- [ ] 时间轴精确计算服务（句偏移 + 镜头时长 + 超时处理）— 当前用镜头级直接拼接，未做精确时间轴
- [ ] 前端：集级"合成对白音轨"按钮 — 当前合成视频时自动混入，无需单独按钮

### Phase 3：BGM / SFX 混音（需音频资源库前置）

- [ ] 音频资源库设计（BGM/SFX 资产类型 + URL 存储）
- [ ] `AudioTrack` 实体和表
- [ ] FFmpeg 三轨混音（对白 + BGM + SFX）
- [ ] 前端：音量调节 + 混音预览
- [ ] 视频合流改为方案 B（混合原片音频）

### Phase 4：高级能力（可选）

- [ ] 音色克隆（上传参考音频 → 生成自定义音色）
- [ ] Agent 辅助：情感标注 / 旁白改写 / 难句拆分
- [ ] 多语言 TTS
- [ ] 自动时间轴对齐（根据镜头 duration 自动调整语速）
- [ ] BGM 智能推荐

## 六、技术选型参考

| 组件 | 选型 | 理由 |
|------|------|------|
| TTS 引擎 | 火山引擎 doubao-seed-tts-2.0 | 项目已有火山 API 配置 + 模型记录已存在 |
| 备选 TTS | CosyVoice / OpenAI TTS | 支持零样本音色克隆 / 通用性好 |
| 音频格式 | MP3（存储） | 存储省空间，TTS API 默认输出 MP3 |
| 混音工具 | FFmpeg | `VideoComposeService` 已用，成熟稳定 |
| 音频前端播放 | HTML5 `<audio>` | MVP 够用；WaveSurfer.js 可选增强 |
| 队列 | Redis（复用现有） | 与 Image/Video Consumer 一致 |
| 台词解析 | 确定性 Java 解析器 | 不用 LLM，避免不稳定 |

## 七、风险与注意事项

1. **TTS 文本长度限制**：豆包 TTS 单次上限约 500 字，超长对白需分段合成后拼接
2. **音色一致性**：同一角色在不同镜头中必须使用相同 `voiceId`，`voiceConfig` 持久化在 `Asset.properties`
3. **并发控制**：TTS API 可能有 QPS 限制，复用 Redis 队列按 modelId 控制并发
4. **音频文件存储**：复用 `MediaStorageService`，存储路径 `/media/audio/`
5. **FFmpeg 依赖**：`VideoComposeService` 已依赖 ffmpeg/ffprobe，无需额外安装
6. **`sound`/`soundEffect` 是文本描述**：MVP 不做 BGM/SFX 混音，Phase 3 需先建音频资源库
7. **一镜多句**：一个 `StoryboardItem` 可对应多条 `AudioTask`，前端需按句展示
8. **角色匹配兜底**：`characterIds` 为空或匹配失败时，用模型 `defaultVoice`
9. **TTS 模型 config 为空**：现网 `doubao-seed-tts-2.0` 的 `config=NULL`，需先在管理页填入 voiceList 等
10. **视频合流策略**：MVP 用方案 A（替换原片音频），Phase 3 可选方案 B（混合）
11. **音色兼容性**：`seed-tts-2.0` 资源只支持 `_uranus_bigtts` 结尾的音色，1.0 音色（`_mars_bigtts`、`_moon_bigtts` 等）会报 `resource ID is mismatched with speaker` 错误
12. **重生成状态**：重生成创建新 `AudioTask`（`parentTaskId` 指向旧任务），`checkItemCompletion` 按 seq 分组只看最新任务，避免旧失败任务影响镜头状态

## 八、落地实现记录（2026-08-10）

### 8.1 实际架构

```
角色管理页绑定音色（VoiceConfigDialog）
  → Asset.properties 写入 voiceConfig {voiceId, speed, pitch}

用户在分镜页点击"批量生成配音"
  → VoiceoverPanelDialog 展示对白列表（按场次过滤，每句对应镜头）
  → 勾选镜头 → POST /api/storyboard/voiceover/{storyboardId}/generate
  → 服务端解析 dialogue → DialogueParser 拆分为 AudioLine
  → 匹配角色 → 获取 voiceConfig（未绑定用模型默认音色）
  → 创建 AudioTask → 入 Redis 队列
  → AudioGenerationConsumer 消费 → VolcengineAudioStrategy 调用 TTS API
  → 生成音频文件 → 存 MediaStorage → 更新 task.status=2
  → checkItemCompletion 检查镜头所有 seq 最新任务 → 更新 StoryboardItem.audioStatus

用户点击"合成本集视频"
  → VideoComposeService.doCompose()
  → 逐镜头下载视频 → mixVoiceoverForItem() 混入配音
    → prepareItemAudio() 按 seq 取最新成功任务，拼接多句台词
    → ffmpeg 将音频叠加到视频（apad 补静音 / -shortest 截断）
  → 拼接所有镜头 → 最终成片
```

### 8.2 TTS 集成细节

**火山引擎 Agent Plan TTS API**：
- 端点：`https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional`
- 认证：`X-Api-Key`（Ark API Key）
- 资源 ID：`X-Api-Resource-Id: seed-tts-2.0`
- 响应格式：流式 JSON，`code=0` 为音频数据（base64），`code=20000000` 为合成完成
- `VolcengineAudioStrategy.extractAudioFromResponse()` 解析拼接 JSON 对象

**合法音色（seed-tts-2.0）**：

| Speaker ID | 名称 |
|---|---|
| `zh_female_vv_uranus_bigtts` | Vivi 通用女声 |
| `zh_male_m191_uranus_bigtts` | 云舟 通用男声 |
| `zh_female_cancan_uranus_bigtts` | 灿灿 知性女声 |
| `zh_male_taocheng_uranus_bigtts` | 小天 青年男声 |
| `zh_female_tianmeixiaoyuan_uranus_bigtts` | 小源 甜美女声 |
| `zh_female_xiaohe_uranus_bigtts` | 小何 通用女声 |

### 8.3 视频合成混入配音

**实现位置**：`VideoComposeService`

**核心方法**：
- `mixVoiceoverForItem(video, itemId, index, workDir)`：为单个镜头混入配音
- `prepareItemAudio(itemId, index, workDir)`：下载并拼接镜头的所有配音音频

**流程**：
1. 查询该镜头所有成功的 `AudioTask`（按 seq 取最新，忽略旧失败任务）
2. 单句台词直接下载；多句按 seq 用 ffmpeg concat 拼接
3. 用 ffmpeg 将音频叠加到视频：
   - 音频 < 视频 → `apad` 补静音到视频时长
   - 音频 ≥ 视频 → `-shortest` 以视频时长为准
4. 容错：配音混入失败不影响视频合成，自动回退到无音频原视频

**触发点**：`doCompose()` 和 `doComposeSegments()` 中，每个镜头视频下载后自动调用

### 8.4 重生成状态修正

**问题**：重生成创建新 `AudioTask`，但 `checkItemCompletion` 检查所有任务（含旧失败任务），导致镜头状态仍为失败。

**修复**：
- 后端 `checkItemCompletion`：按 seq 分组，每组只取 id 最大的（最新）任务状态
- 前端 `tasksByItemId`：按 `(itemId, seq)` 去重，只保留 id 最大的任务

### 8.5 前端实现

**组件**：
- `VoiceoverPanelDialog`：配音管理面板
  - 按左侧选中的场次过滤对白列表
  - 每句台词显示：镜头号 + 场次名 + 角色名 + 台词文本 + 音色配置状态
  - 支持全选/单选镜头，批量生成选中
  - 生成后实时展示任务状态（待处理/生成中/已完成/失败）
  - 单条重生成
- `VoiceConfigDialog`：角色音色配置弹窗
  - 预设音色下拉（仅 `_uranus_bigtts` 合法音色）
  - 语速/音调调节
  - 自定义音色 ID 输入

**API 客户端**（`lib/api/storyboard.ts`）：
- `previewDialogue(storyboardId, episodeId, sceneId?)`：预览对白列表
- `generateVoiceover(storyboardId, episodeId, itemIds?)`：批量生成（支持指定镜头）
- `regenerateVoiceover(audioTaskId)`：单条重生成

### 8.6 文件清单

**后端**：

| 文件 | 职责 |
|---|---|
| `VoiceoverController.java` | REST API：预览、批量生成、重生成、查询、音色配置 |
| `VoiceoverService.java` | 配音编排：解析对白、匹配角色、创建任务、预览 |
| `DialogueParser.java` | 确定性台词解析：`dialogue` → AudioLine 列表 |
| `AudioGenerationService.java` | AudioTask CRUD |
| `AudioGenerationConsumer.java` | Redis 队列消费 + `checkItemCompletion` |
| `VolcengineAudioStrategy.java` | 火山引擎 Agent Plan TTS 请求与响应解析 |
| `VideoComposeService.java` | 视频合成 + 配音混入 |

**前端**：

| 文件 | 职责 |
|---|---|
| `voiceover-panel-dialog.tsx` | 配音管理面板 |
| `voice-config-dialog.tsx` | 角色音色配置弹窗 |
| `lib/api/storyboard.ts` | API 客户端类型与方法 |
| `storyboards/page.tsx` | 分镜页面集成（传 sceneId/sceneLabel/sceneNameMap） |

### 8.7 常见错误排查

| 错误码 | 含义 | 解决方案 |
|---|---|---|
| `45000010` | Invalid X-Api-Key | 检查 `afv_api_config` 中的 `api_key` 是否完整 |
| `45000030` | AgentPlanDeductNotEnabled | 火山方舟控制台 Agent Plan 套餐计费未开启 |
| `55000000` | resource ID mismatch | 音色 ID 不兼容 `seed-tts-2.0`，需用 `_uranus_bigtts` 结尾 |
| UI 显示失败但任务成功 | 旧任务干扰 | `checkItemCompletion` 已按 seq 取最新任务修复 |
