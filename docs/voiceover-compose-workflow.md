# 配音生成与视频合成工作流

## 整体流程

```
分镜文本 → 生成视频片段（无声）
         → 生成配音音频（TTS）
         → 合并：视频 + 音频 → 最终视频
```

### 1. 视频生成阶段

**不需要预设台词嘴形**。当前主流 AI 视频模型（可灵、Runway 等）生成的是无声视频片段，只负责"画面和动作"，不涉及台词和嘴型。

### 2. 配音阶段（TTS）

- 每个镜头（`StoryboardItem`）的 `dialogue` 字段存储该镜头的对白
- `DialogueParser` 解析对白，按角色拆分为多句台词（`AudioLine`）
- 每句台词生成独立的 `AudioTask`，通过 `storyboardItemId` 和 `seq` 关联到对应镜头
- TTS 使用火山引擎 Agent Plan API（`seed-tts-2.0`），音色必须以 `_uranus_bigtts` 结尾

### 3. 音视频合并阶段

合成视频时，自动为每个镜头混入对应的配音音频：

1. 查询该镜头所有成功的 `AudioTask`（按 `seq` 取最新，忽略被重生成覆盖的旧任务）
2. 多句台词按 `seq` 顺序拼接成单个音频文件
3. 用 ffmpeg 将音频叠加到视频上
4. 拼接所有镜头 → 最终成片

### 4. 口型同步（可选，进阶）

当前流程**不做精确口型同步**。如需角色说话时嘴型匹配，需要额外的 lip-sync 模型（如 Wav2Lip、MuseTalk 等）：

- 远景/背影镜头不需要
- 旁白/画外音不需要
- 只有近景且角色面对镜头说话才需要

## 时长匹配策略

| 情况 | ffmpeg 处理 |
|---|---|
| 音频 < 视频 | `apad` 补静音到视频时长 |
| 音频 > 视频 | `-shortest` 以视频时长为准 |
| 大致相等 | 直接合并 |

## 关键组件

### 后端

| 组件 | 职责 |
|---|---|
| `VoiceoverService` | 配音预览、批量生成、重生成 |
| `VoiceoverController` | REST API：预览对白、批量生成、重生成 |
| `AudioGenerationConsumer` | 消费 TTS 任务，调用策略生成音频，更新镜头状态 |
| `VolcengineAudioStrategy` | 火山引擎 Agent Plan TTS 请求与响应解析 |
| `VideoComposeService` | 视频合成：混入配音 → 拼接镜头 → 输出成片 |
| `DialogueParser` | 解析分镜对白文本，按角色拆分台词行 |

### 前端

| 组件 | 职责 |
|---|---|
| `VoiceoverPanelDialog` | 配音管理面板：展示对白列表、勾选镜头、生成/重生成 |
| `VoiceConfigDialog` | 角色音色配置：选择预设音色、自定义音色ID |
| `storyboardApi` | API 客户端：`previewDialogue`、`generateVoiceover`、`regenerateVoiceover` |

## 数据模型

```
StoryboardItem (镜头)
  ├── dialogue: "陈默：下来！案子还没结，你跑不了！"
  ├── audioUrl: "/media/audio/xxx.mp3"     ← 单句台词时直接存储
  ├── audioStatus: 0未生成 / 1生成中 / 2已完成 / 3失败
  └── AudioTask[] (配音任务)
        ├── seq: 0, 1, 2...                ← 台词序号
        ├── characterName: "陈默"
        ├── text: "下来！案子还没结，你跑不了！"
        ├── voiceConfig: {"voiceId":"zh_male_m191_uranus_bigtts",...}
        ├── audioUrl: "/media/audio/xxx.mp3"
        ├── status: 0/1/2/3
        └── parentTaskId: null             ← 重生成时指向旧任务
```

## 重生成机制

- 重生成会创建**新的** `AudioTask`（`parentTaskId` 指向旧任务）
- `checkItemCompletion` 按 `seq` 分组，每组只看 `id` 最大的（最新）任务状态
- 前端 `tasksByItemId` 同样按 `(itemId, seq)` 去重，只保留最新任务
- 旧失败任务不会影响镜头整体状态

## 合法音色列表（seed-tts-2.0）

> **重要**：`seed-tts-2.0` 资源只支持 `_uranus_bigtts` 结尾的音色，1.0 音色（`_mars_bigtts`、`_moon_bigtts` 等）不兼容。

| Speaker ID | 名称 | 场景 |
|---|---|---|
| `zh_female_vv_uranus_bigtts` | Vivi 通用女声 | 通用，多语种/方言 |
| `zh_male_m191_uranus_bigtts` | 云舟 通用男声 | 通用/旁白 |
| `zh_female_cancan_uranus_bigtts` | 灿灿 知性女声 | 角色扮演 |
| `zh_male_taocheng_uranus_bigtts` | 小天 青年男声 | 通用 |
| `zh_female_tianmeixiaoyuan_uranus_bigtts` | 小源 甜美女声 | 通用 |
| `zh_female_xiaohe_uranus_bigtts` | 小何 通用女声 | 通用 |

## API 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/storyboard/voiceover/{storyboardId}/preview?episodeId=&sceneId=` | 预览对白列表 |
| POST | `/api/storyboard/voiceover/{storyboardId}/generate` | 批量生成配音 |
| POST | `/api/storyboard/voiceover/{audioTaskId}/regenerate` | 重生成单条 |
| GET | `/api/storyboard/voiceover/list?episodeId=` | 查询配音任务列表 |
| GET | `/api/storyboard/voiceover/voice-config/{assetId}` | 获取角色音色配置 |
| POST | `/api/storyboard/voiceover/voice-config/{assetId}` | 设置角色音色配置 |

## 常见问题

### Invalid X-Api-Key (code 45000010)

检查 `afv_api_config` 中的 `api_key` 是否为完整的 Ark API Key（`ark-xxx` 格式）。

### AgentPlanDeductNotEnabled (code 45000030)

火山方舟控制台 Agent Plan 套餐计费未开启或余额不足，需检查费用中心。

### resource ID is mismatched with speaker (code 55000000)

音色 ID 与资源 ID 不匹配。`seed-tts-2.0` 只支持 `_uranus_bigtts` 结尾的音色。

### 合成后视频没有声音

检查该镜头是否有状态为 `2`（已完成）的 `AudioTask`。配音混入时只取成功的任务。
