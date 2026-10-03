# 资产去重与一致性优化方案（最终版）

> 状态：已复核，可执行
> 日期：2026-08-10
> 复核范围：script-full-parse / script-story-to-script / script-episode-upload / batch_create_assets / create_asset / AssetService.create / asset_image_gen / save_storyboard_scene_shots / storyboard-asset-preprocessor

---

## 一、问题诊断（已验证）

### 1.1 数据实证（项目 25）

| 类型 | 数量 | 问题 |
|------|------|------|
| character | 10 | 龙套（仙使/仙吏/弟子甲）也建 initial + three_view，生图浪费 |
| scene | 12 | 语义重叠：`九霄云海` ≈ `九霄之外混沌` ≈ `仙界边缘混沌`；元场景 `九州各地`；`青云宗后山闭关洞府` 分镜引用 0 |
| prop | 6 | 穿戴物（黄头盔）与角色描述脱节；场景陈设（餐盒）被拆成独立 prop |

### 1.2 根因链路

```
剧本解析提取资产（规则过粗，无边界/准入）
  → 精确 name 去重（跨类型、近义名全部放行）
  → AssetService.create 固定建 initial(+three_view)（无重要性过滤）
  → asset_image_gen 对所有 imageUrl 空的 item 全量生成
  → 分镜绑定无服务端校验（幻觉ID/类型错位/漏绑/过绑）→ 视觉分裂
```

### 1.3 七个根因

| # | 根因 | 代码位置 |
|---|------|----------|
| R1 | 提取规则无 scene/prop 边界、无角色准入 | `script-full-parse.system.md` / `script-story-to-script.system.md` / `script-episode-upload.system.md` |
| R2 | 去重仅 `projectId + type + name` 精确全等 | `BatchCreateAssetsToolExecutor.java:204` / `AssetService.findByProjectTypeAndName` |
| R3 | 角色一律建 initial + three_view | `AssetService.create:229-238` |
| R4 | 生图无门禁，全量生成 | `asset-image-generation.system.md` |
| R5 | scene↔prop 关联仅在分镜保存后反写 | `SaveStoryboardSceneShotsToolExecutor.writeBackRelatedAssets:342` |
| R6 | 无候选确认/一致性审查闸门 | 全流程缺失 |
| R7 | 分镜镜头资产绑定无服务端校验，prompt 规则过松 | `SaveStoryboardSceneShotsToolExecutor` 无 ID 归属/类型校验；`episode-storyboard-writer.system.md:54-63` 匹配规则仅按 name/description，不交叉校验剧本场次资产 |

---

## 二、设计原则（八条）

1. **先定实体，再生图；图可少于实体** — 库里可以有无图实体（供 name 匹配），不等于必须生图
2. **默认同源** — 一个视觉身份最多一套 base 参考图；地标进 scene.description，不二次文生
3. **地标 ⊂ 场景；离体英雄物 ⊂ 道具；穿戴默认跟角色，离体戏可升格道具** — 三态而非两态
4. **去重：高置信自动拦，低置信告警，禁止静默语义合并** — 避免误合并不同空间
5. **引用源优先级：剧本场次绑定 ≥ 分镜绑定 ≥ 用户 selected** — 生图门禁看剧本场次，不看分镜
6. **关联在解析/场次阶段写，不依赖分镜反写** — `relatedAssetIds` 必须前移
7. **全自动默认跑 Reviewer；人工确认为可选增强** — 不阻塞自动化主路径
8. **所有创建入口同一套准入** — batch_create_assets / create_asset / episode-upload 全覆盖

---

## 三、类型边界与准入规则

### 3.1 scene vs prop 边界

| 视觉实体 | 归属 | 判定条件 |
|----------|------|----------|
| 可进入的空间容器（地点） | **scene** | 角色可在其中活动 |
| 固定地标、建筑构件、陈设 | **scene.description 内文字** | 不可被角色携带 |
| 可携带、跨 ≥2 场复现、需特写一致性 | **prop** | 满足英雄道具测试 ≥2 条 |
| 会离体、被抛、独立运动、单独特写 | **prop** | 即使原本是穿戴物 |

**英雄道具测试（满足 ≥2 条才建 prop）：**
1. 推动剧情
2. 跨 ≥2 场出现
3. 需要特写/手持一致性
4. 可被角色携带或独立运动

**反例（不建 prop）：**
- 广场玉碑、廊柱、祭坛 → 写入 scene.description
- 始终戴在头上的头盔 → 写入角色 description/variant

**正例（建 prop）：**
- 被甩飞的黄色头盔（离体 + 独立运动 + 特写）
- 传讯玉简（跨场 + 剧情关键 + 特写）
- 电动车（跨场 + 可携带 + 剧情工具）

### 3.2 穿戴物三态决策

| 情况 | 建模 | 理由 |
|------|------|------|
| 始终穿戴、无离体戏 | 角色 description / variant | 无需独立参考图 |
| 会离体、被抛、单独特写、跨镜一致 | **prop**，生图与角色外观锁定 | 需要独立参考图 |
| 场景固定地标 | **只进 scene**，不建 prop | 避免双文生图分裂 |

### 3.3 场景粒度

- 一个「可拍地点」一个 scene
- 子区域用命名层级：`青云宗·祭天台`，不要平行建 `祭天台` + `青云宗`
- 同一空间不同状态（破坏/昼夜）→ **同一 scene 的 variant**，不新建主资产
- **禁止元场景**：`九州各地`、`各地`、`若干场景合集`

### 3.4 角色准入

| 重要性 | 建库 | 生 initial | 生 three_view | 说明 |
|--------|------|-----------|---------------|------|
| lead（主角） | ✅ | ✅ | ✅ | |
| support（配角） | ✅ | ✅ | ✅ | |
| extra（龙套） | ✅ | ✅ | ❌ | 有台词/动作但出场少；建库供 name 匹配 |
| background（群演/旁白） | ✅ text-only | ❌ | ❌ | 仅建实体供 name 匹配，不生图 |

**群像合并**：`青云宗弟子（群演）` 一个 character，禁止每人一个。

### 3.5 拍摄设备黑名单

以下关键词出现在 prop 名/描述中时，**拒绝创建**并提示「拍摄设备不进资产库」：
- 穿梭机、无人机、飞行器、轨道车、摇臂、斯坦尼康、手持云台

> 已有代码在 `SaveStoryboardSceneShotsToolExecutor:283-286` 做了 warn 检测，应在创建阶段直接拦截。

---

## 四、服务端硬约束

### 4.1 创建时去重增强（BatchCreateAssetsToolExecutor + AssetCreateToolExecutor）

现有：`projectId + type + name` 精确全等。

**新增高置信规则（自动拦截，不调 LLM）：**

| 规则 | 命中行为 |
|------|----------|
| name 完全相等（同 type） | 复用已有（现有行为） |
| name 去空格/去后缀后相等 | 复用已有 |
| aliases 表命中 | 复用已有 |
| A.name 包含 B.name 且同 type（如 `万宗城` vs `万宗城·广场`） | 返回 conflict 警告，提示父子关系，**不自动删** |
| 新 prop.name 被已有 scene.name 或 scene.description 包含 | 拒绝创建，提示「写入 scene.description」 |
| 新 scene.name 被已有 prop.name 包含 | 警告，提示检查是否同一实体 |

**低置信（仅 WARN，不自动 merge）：**
- 名称语义近义但不同（`九霄云海` vs `九霄之外混沌`）→ 标记 `potentialDuplicate` 供 Reviewer 或人工处理

### 4.2 three_view 创建门禁（AssetService.create）

```java
// 现有：角色一律建 three_view
if ("character".equals(asset.getType())) {
    // 建 three_view
}

// 改为：仅 lead/support 建 three_view
if ("character".equals(asset.getType())) {
    String importance = extractImportance(asset); // from properties or param
    if (!"extra".equals(importance) && !"background".equals(importance)) {
        // 建 three_view
    }
}
```

**注意**：`Asset` 实体当前无 `importance` 字段。方案见 §六数据模型扩展。过渡期可从 `properties` JSON 中读取。

### 4.3 生图门禁（asset-image-generation）

**引用源优先级**：剧本场次绑定 > 分镜绑定 > 用户 selected

**默认跳过：**
- `importance=background` 的所有 item
- `importance=extra` 的 `three_view` item
- 未被任何剧本场次引用 **且** 未被用户 selected 的资产（仅当项目已有分镜数据时才做此检查）

**注意时序**：解析刚完成、尚未分镜时，剧本场次已有 `character_asset_ids` / `scene_asset_id` / `prop_asset_ids`，可以作为引用依据。不要用 `afv_storyboard_item` 的引用做首次生图门禁。

### 4.4 关联前移（scene↔prop）

现有：`SaveStoryboardSceneShotsToolExecutor.writeBackRelatedAssets` 在分镜保存后反写。

**改为**：在 `save_script_scene_items`（剧本场次保存）时也写 `relatedAssetIds`。

- 场次保存时，根据 `scene_asset_id` + `prop_asset_ids` 写 scene↔prop 关联
- 这样资产图生成时（通常在分镜前），`relatedAssetIds` 已可用
- `SaveStoryboardSceneShotsToolExecutor` 的反写保留作为补充

---

## 五、流程改造

### 5.1 现有流程

```
剧本解析 → batch_create_assets（直接落库）
         → AssetService.create（固定 initial + three_view）
         → asset_image_gen（全量生图）
         → 分镜绑定
         → writeBackRelatedAssets（分镜后反写）
```

### 5.2 目标流程

```
剧本解析 → Asset Extractor（提取候选列表）
         → Asset Reviewer Agent（自动审查）
            ├─ 类型纠错（prop 实为地标 → 降级写入 scene.description）
            ├─ 近义合并建议（高置信自动，低置信 WARN）
            ├─ 穿戴物判定（三态决策）
            ├─ 设备黑名单拦截
            └─ 龙套降级（extra → 不建 three_view）
         → 确认落库（stable assetId + aliases）
         → save_script_scene_items（写 scene↔prop 关联）
         → 按依赖序生图：
            1. prop（lead 英雄道具）
            2. scene（可引用 prop 图）
            3. character initial
            4. character three_view / variant
         → 分镜只引用 assetItemId，禁止临时造名
```

### 5.3 Asset Reviewer Agent（新增，全自动）

**职责**：在 `batch_create_assets` 调用前或调用后立即执行一致性审查。

**检查清单**：

| # | 检查项 | 级别 | 处理 |
|---|--------|------|------|
| C1 | 近义 scene 对（名称包含/语义近义） | WARN | 输出合并建议 |
| C2 | prop 实为 scene 地标（prop.name 被 scene.description 包含） | BLOCK | 拒绝创建，提示写入 scene |
| C3 | 穿戴物误建 prop（无离体戏） | WARN | 建议降级为角色 variant |
| C4 | 拍摄设备名混入 prop | BLOCK | 拒绝创建 |
| C5 | 无引用资产（剧本场次未引用且非 lead/support） | WARN | 标记 imagePolicy=skip |
| C6 | 角色双名/别名未合并 | BLOCK | 合并为一个实体 + aliases |
| C7 | 元场景（合集类名称） | BLOCK | 拒绝，要求拆分 |
| C8 | 群演每人一资产 | WARN | 建议合并为群像资产 |

**分镜绑定准确性检查（在 storyboard-content-reviewer 中执行，见 §5.5.3）：**

| # | 检查项 | 级别 | 处理 |
|---|--------|------|------|
| C9 | content 提到角色但未绑定 characterId | WARN | 标记 **UNBOUND_CHARACTER_IN_CONTENT** |
| C10 | content 提到道具但未绑定 propId | WARN | 标记 **UNBOUND_PROP_IN_CONTENT** |
| C11 | 绑定了 content 中未出现的资产 | WARN | 标记 **OVER_BOUND_ASSET**，提示移除 |
| C12 | 非特写镜但无 sceneAssetItemId | WARN | 标记 **MISSING_SCENE_BINDING** |

**BLOCK** = 自动阻止创建，返回错误信息给上游 Agent
**WARN** = 允许创建但标记，供后续人工/Reviewer 复核

### 5.4 地标特写一致性

当地标（如玉碑）在剧本/分镜中出现 **≥2 次特写** 时：

- **方案A（推荐）**：scene 的 `detail` 子资产 — 从场景初始图裁切/重绘，不二次纯文生
- **方案B**：条件 prop — 创建时强制 `relatedAssetIds` 包含所属 scene，生图时必须引用 scene 图

**默认不建 prop**；仅在满足特写频次条件时升格。

### 5.5 分镜镜头资产绑定精准性（新增）

**问题**：当前分镜生成中，镜头的 `characterIds` / `sceneAssetItemId` / `propIds` 绑定存在以下精度问题：

| # | 问题 | 现状 | 影响 |
|---|------|------|------|
| B1 | 绑定 ID 不属于当前项目 | 服务端无校验 | 幻觉 ID 导致下游生图无参考图 |
| B2 | 类型错位（scene ID 填入 characterIds） | 服务端无校验 | 生图引用错误类型参考图 |
| B3 | content 提到角色但未绑定 characterId | prompt 无强制 | 角色外观不一致 |
| B4 | sceneAssetItemId 与剧本场次的 sceneAssetId 不一致 | 无交叉校验 | 同一场次不同镜头用了不同场景资产 |
| B5 | propIds 包含剧本场次未引用的道具 | 无校验 | 过度绑定，生图引入无关元素 |
| B6 | 镜头有场景但 sceneAssetItemId 为 null | 无校验 | 下游生图缺少场景参考 |
| B7 | content 提到道具但未绑定 propId | prompt 无强制 | 道具外观不一致 |
| B8 | Reviewer 不检查绑定准确性 | 仅检查 content 文字 | 绑定错误无法被复核拦截 |

#### 5.5.1 Prompt 层改造（episode-storyboard-writer.system.md）

**强化子资产匹配规则（§54-63 重写）：**

```
## 子资产匹配规则（核心！）

### 匹配源优先级
1. **剧本场次绑定优先**：get_script_scene 返回的 characterAssetIds / sceneAssetId / propAssetIds
   是该场次已绑定的资产。分镜镜头的资产绑定必须以此为准，不得使用剧本场次未绑定的资产ID
   （预处理器新增的变体除外）
2. **变体匹配**：如预处理器为该场次角色创建了变体（如"受伤版张三"），优先使用变体子资产ID
3. **兜底**：如无变体匹配，使用 itemType="initial" 的默认子资产

### 强制绑定规则
- **每个镜头必须绑定 sceneAssetItemId**：不得为 null，必须从剧本场次的 sceneAssetId 对应的
  子资产中选取
- **content 中提到的每个有名角色必须绑定 characterIds**：如 content 写"张三走向祭台"，
  characterIds 必须包含张三的子资产ID
- **content 中提到的每个英雄道具必须绑定 propIds**：如 content 写"角色手持玉简"，
  propIds 必须包含玉简的子资产ID
- **禁止绑定 content 中未出现的资产**：如镜头 content 未提到某道具，不得将其 ID 填入 propIds
  （场景资产除外，场景是空间容器始终存在）

### 禁止项
- 禁止使用 list_project_assets 返回列表之外的 ID（不得编造 ID）
- 禁止将 scene 子资产 ID 填入 characterIds 或 propIds
- 禁止将 character 子资产 ID 填入 sceneAssetItemId 或 propIds
- 禁止将 three_view 子资产 ID 填入 characterIds（仅 initial 或 variant）
- 拍摄载具类资产禁止填入 propIds
```

#### 5.5.2 服务端校验（SaveStoryboardSceneShotsToolExecutor）

**新增校验逻辑（在镜头构建循环中，保存前执行）：**

| 校验 | 级别 | 处理 |
|------|------|------|
| assetItemId 不存在或不属于当前项目 | **ERROR** | 拒绝保存该镜头，返回错误 |
| characterIds 中的 item 类型不是 character | **ERROR** | 拒绝保存，返回错误 |
| sceneAssetItemId 的 item 类型不是 scene | **ERROR** | 拒绝保存，返回错误 |
| propIds 中的 item 类型不是 prop | **ERROR** | 拒绝保存，返回错误 |
| sceneAssetItemId 为 null | **WARN** | 记录警告（不阻塞，可能是有意为之的纯特写镜） |
| characterIds 中的 assetId 不在剧本场次的 characterAssetIds 中 | **WARN** | 记录警告（可能是预处理器新增变体） |
| propIds 中的 assetId 不在剧本场次的 propAssetIds 中 | **WARN** | 记录警告 |

**实现要点**：
- 在 `execute` 方法中，批量查询所有引用的 assetItemIds，校验其 `assetId` 属于当前项目
- 通过 assetItem 的 `itemType` 校验类型匹配
- 可选：通过 `getScriptScene` 获取剧本场次的资产绑定，做交叉校验（需传入 scriptSceneItemId 或从 storyboardEpisodeId 反查）

#### 5.5.3 Reviewer 增强（storyboard-content-reviewer.system.md）

**新增检查项：**

| # | 检查项 | 级别 | 处理 |
|---|--------|------|------|
| C9 | 绑定准确性（bindingAccuracy） | WARN | 检查 content 中提到的角色名是否都在 characterRefs 中；如不在，标记 **UNBOUND_CHARACTER_IN_CONTENT** |
| C10 | 道具绑定准确性（propBindingAccuracy） | WARN | 检查 content 中提到的道具是否都在 propRefs 中；如不在，标记 **UNBOUND_PROP_IN_CONTENT** |
| C11 | 过度绑定（overBinding） | WARN | 检查 characterRefs/propRefs 中是否有 content 完全未提及的资产；如有，标记 **OVER_BOUND_ASSET**，提示 writer 移除 |
| C12 | 场景缺失（missingScene） | WARN | 如镜头非纯特写（shotType 不是 大特写/特写）但无 sceneRef，标记 **MISSING_SCENE_BINDING** |

**注意**：Reviewer 只能标记问题并建议修正，不能直接修改 characterIds/sceneAssetItemId/propIds（这些是结构化字段，由 writer 根据提示修正后重新保存）。

#### 5.5.4 剧本场次→分镜镜头的资产一致性校验链

```
剧本场次（script_scene_item）
  ├─ characterAssetIds: [角色A_id, 角色B_id]
  ├─ sceneAssetId: 场景X_id
  └─ propAssetIds: [道具Y_id]
        ↓
分镜镜头（storyboard_item）
  ├─ characterIds: [角色A_initial_item_id, 角色B_variant_item_id]  ← 必须是上面角色的子资产
  ├─ sceneAssetItemId: 场景X_initial_item_id                        ← 必须是上面场景的子资产
  └─ propIds: [道具Y_initial_item_id]                               ← 必须是上面道具的子资产
```

**校验逻辑**：
1. 从 `get_script_scene` 获取剧本场次的 `characterAssetIds` / `sceneAssetId` / `propAssetIds`（主资产 ID 列表）
2. 从 `list_project_assets` 获取这些主资产下的所有子资产
3. 校验分镜镜头的 `characterIds` / `sceneAssetItemId` / `propIds`（子资产 ID）是否都属于上述主资产的子资产集合
4. 预处理器新增的变体子资产也属于同一主资产，自动通过校验

---

## 六、数据模型扩展

### 6.1 Asset 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `importance` | varchar(20) | `lead` / `support` / `extra` / `background`，默认 `support` |
| `imagePolicy` | varchar(20) | `required` / `optional` / `skip`，默认 `required` |
| `aliases` | json | 别名列表，如 `["王德发", "花非烟"]` |

### 6.2 Asset.properties 约定（无需改表）

| key | 说明 |
|-----|------|
| `relatedAssetIds` | 已有，关联资产 ID 列表 |
| `landmarks` | 场景内结构化地标文字描述列表，供 prompt 引用 |
| `containedProps` | 场景内嵌道具 ID 列表（解析时写，不等分镜反写） |

### 6.3 不新增 parentAssetId

分镜只绑一个 `sceneAssetItemId`，无层级解析逻辑。场景层级用命名约定（`青云宗·祭天台`）即可，不加字段。

---

## 七、多入口覆盖

以下入口必须统一应用 §三 的准入规则：

| 入口 | 文件 | 改动 |
|------|------|------|
| 完整剧本解析 | `script-full-parse.system.md` | 步骤3-5加边界/准入 |
| 故事转剧本 | `script-story-to-script.system.md` | 步骤3-5加边界/准入 |
| 单集上传 | `script-episode-upload.system.md` | 步骤5-6加边界/准入 |
| 批量创建工具描述 | `BatchCreateAssetsToolExecutor.getToolDescription` | 加边界/准入/设备黑名单 |
| 单条创建工具描述 | `AssetCreateToolExecutor.getToolDescription` | 同上 |
| 子资产预处理 | `storyboard-asset-preprocessor.system.md` | 加「不为不该存在的主资产扩 variant」 |
| 资产生图调度 | `asset-image-generation.system.md` | 加生图门禁规则 |
| 分镜编写 | `episode-storyboard-writer.system.md` | 加强制绑定规则 + 剧本场次资产交叉校验（§5.5.1） |
| 分镜内容复核 | `storyboard-content-reviewer.system.md` | 加 C9-C12 绑定准确性检查（§5.5.3） |
| 分镜保存工具 | `SaveStoryboardSceneShotsToolExecutor` | 加服务端 ID 归属/类型校验（§5.5.2） |

---

## 八、落地计划

### P0：规则 + 服务端门禁（本周，改动可控）

| # | 改动 | 文件 | 类型 |
|---|------|------|------|
| P0-1 | 统一 scene/prop/character 准入文案 | 上述 7 个 prompt 文件 | Prompt |
| P0-2 | 创建时跨类型包含冲突检测 + 别名规范化 | `BatchCreateAssetsToolExecutor` / `AssetCreateToolExecutor` | Java |
| P0-3 | three_view 仅 lead/support | `AssetService.create` | Java |
| P0-4 | 生图门禁：跳过 extra three_view + background 全部 | `asset-image-generation.system.md` | Prompt |
| P0-5 | 剧本场次保存时写 relatedAssetIds | `SaveScriptSceneItemsToolExecutor`（需确认是否存在） | Java |
| P0-6 | 设备黑名单在创建阶段拦截 | `BatchCreateAssetsToolExecutor` / `AssetCreateToolExecutor` | Java |
| P0-7 | 分镜保存时校验 assetItemId 归属 + 类型匹配 | `SaveStoryboardSceneShotsToolExecutor` | Java |
| P0-8 | 分镜编写 prompt 加强制绑定规则 + 剧本场次交叉校验 | `episode-storyboard-writer.system.md` | Prompt |

### P1：Reviewer + 地标特写（下周）

| # | 改动 | 说明 |
|---|------|------|
| P1-1 | Asset Reviewer Agent | 新增子 Agent，在创建后自动跑 C1-C8 检查清单 |
| P1-2 | 地标特写：scene detail 子资产或条件 prop + 强制参考 scene | 需改 asset-image-executor prompt |
| P1-3 | 存量治理：按项目跑冲突报告 + 可选合并工具 | 一次性脚本或管理端 API |
| P1-4 | 分镜复核加 C9-C12 绑定准确性检查 | `storyboard-content-reviewer.system.md` | Prompt |
| P1-5 | 分镜保存加剧本场次资产交叉校验（WARN 级） | `SaveStoryboardSceneShotsToolExecutor` | Java |

### P2：可选增强（后续）

| # | 改动 | 说明 |
|---|------|------|
| P2-1 | 前端候选确认页 | Jellyfish 模式，不阻塞自动化 |
| P2-2 | embedding 语义检索 | VideoMemory 模式，低置信只出候选 |
| P2-3 | Asset 表加 `importance` / `imagePolicy` / `aliases` 字段 | DDL + 实体 + 创建/查询适配 |

---

## 九、验收标准

| # | 标准 | 检查方式 |
|---|------|----------|
| V1 | 同项目不得同时存在 name 包含关系的 scene + prop | SQL: scene.name LIKE '%prop.name%' OR prop.name LIKE '%scene.name%' |
| V2 | lead/support 角色必须有 initial 图 | SQL: importance IN ('lead','support') AND initial.imageUrl IS NULL |
| V3 | extra 角色不应有 three_view | SQL: importance='extra' AND item_type='three_view' |
| V4 | background 角色不应有任何图 | SQL: importance='background' AND imageUrl IS NOT NULL |
| V5 | 资产生图任务数 ≤ 剧本场次引用实体数 × 1.5 | 对比 asset_image_gen 任务数与 scene_item 引用数 |
| V6 | 无设备黑名单关键词出现在 prop 名中 | SQL: name LIKE '%穿梭机%' OR ... |
| V7 | 无元场景名 | SQL: name LIKE '%各地%' OR name LIKE '%合集%' |
| V8 | scene↔prop 关联在剧本场次保存后即存在 | 验证 relatedAssetIds 在分镜前已写入 |
| V9 | 分镜镜头绑定的 assetItemId 必须属于当前项目 | SQL: storyboard_item.characterIds/propIds/sceneAssetItemId JOIN asset_item ON id, WHERE asset_item.asset.projectId = storyboard.projectId |
| V10 | 分镜镜头绑定的 assetItem 类型必须匹配字段 | characterIds 中 item 的主资产 type=character；sceneAssetItemId → type=scene；propIds → type=prop |
| V11 | 分镜镜头的 sceneAssetItemId 不得为 null（纯特写镜除外） | SQL: sceneAssetItemId IS NULL AND shotType NOT IN ('大特写','特写') |
| V12 | 分镜镜头绑定的资产必须属于剧本场次的资产集合 | 对比 storyboard_item 绑定 vs script_scene_item 的 characterAssetIds/sceneAssetId/propAssetIds |

---

## 十、风险与缓解

| 风险 | 缓解 |
|------|------|
| LLM 仍绕过 prompt 规则建冗余资产 | 服务端硬拦截（P0-2/P0-6）兜底 |
| extra 降级导致分镜角色匹配失败 | extra 仍建库（text-only），不建 three_view；name 匹配不受影响 |
| 关联前移改动 save_script_scene_items 逻辑 | 仅新增 writeBack 调用，不改现有字段结构 |
| 存量项目已有重复资产 | P1-3 存量治理脚本，不自动删，输出报告供人工确认 |
| 硬配额误伤长剧本 | 不设硬配额，用 Reviewer WARN + 软告警 |
| 语义自动 merge 误合并不同空间 | 禁止自动 merge，仅高置信规则拦截 + 低置信 WARN |
| 分镜绑定校验误报（预处理器新增变体不在剧本场次资产列表中） | WARN 级别不阻塞保存，仅记录日志；变体子资产的主资产 ID 仍在剧本场次列表中即可通过 |
| 纯特写镜无 sceneAssetItemId 被误拦 | sceneAssetItemId=null 仅 WARN 不阻塞；shotType 为特写/大特写时豁免 |

---

## 十一、与开源项目对标

| 项目 | 借鉴点 | 本方案对应 |
|------|--------|-----------|
| VideoMemory (ByteDance/HKUST) | Dynamic Memory Bank：char/prop/bg 三库；语义检索 retrieve-or-generate；镜头后 update | §5.4 地标特写同源；P2-2 embedding 检索（后续） |
| Jellyfish (短剧工作台) | 候选确认闸门；name existence 鼓励复用；costume 独立于 character | §5.3 Reviewer Agent；P2-1 前端确认（可选） |
| EM-Vid | 实体先定义再引用：CH_01 / OB_01 / SC_01；shot 只引用 ID | §5.2 分镜只引用 assetItemId，禁止临时造名 |
| MovieAgent (showlab) | 角色库前置，不全量自动抽一切 | §3.4 角色准入：lead/support/extra/background 分级 |
| visual-storytelling-skills | Continuity inventory 先于生图；BLOCK/WARN 必须处理完再 handoff | §5.3 Reviewer 检查清单 BLOCK/WARN 机制 |

---

## 十二、首帧承接上镜尾帧优化（镜头间动作连贯性）

### 12.1 问题诊断

**现象**：场次内非首镜头的首帧 prompt 经常跳到 content 中最后一个动作的完成状态，而非第一个动作的起始瞬间。

**实例**（项目 25，场次 1 镜头 2，item 867）：
- content：`苏念蹲在座位前，从抽屉里一摞摞往外掏卷子→自语→发现水果糖→端详`
- 首帧 prompt（错误）：`苏念手中拿着水果糖，举起在光下端详` ← 跳到最后一个动作
- 首帧 prompt（正确）：`苏念蹲在座位前，手刚伸向抽屉、正准备往外掏卷子的定格瞬间` ← 承接上镜尾帧，截断后续

**根因**：
1. **数据层**：`continuityContext` 缺少 `prevLastFramePrompt`，LLM 无法获取上镜结尾状态的精确描述
2. **Prompt 层**：首帧时序截断规则只看当前 content 孤立提取第一个动词，没有锚定到上镜结尾状态
3. **校正层**：`prompt-reviewer` 的 `interShotContinuity` 检查缺少首帧承接上镜尾帧的专项校验

### 12.2 核心原则（高度抽象）

```
对于场次内任意镜头 N (N > 1):
  首帧状态 = 上镜结尾状态 → 本镜第一个动作的起始瞬间

  其中:
  - 上镜结尾状态 ← prevLastFramePrompt (最优) / prevVideoPrompt (回退) / prevContent (最后回退)
  - 本镜第一个动作 ← content 时序截断（已有规则）
  - → 表示"自然延续"，角色位置/姿态/道具状态/光线方向保持一致
```

此原则等价于电影剪辑中的 **"动作衔接"（match on action）**：镜头 N 的开场 = 镜头 N-1 结尾的延续。适用于所有场景类型，不依赖具体内容。

### 12.3 三层改进

#### 第一层：数据层 — `GetStoryboardSceneItemsToolExecutor.java`

| 改动 | 说明 |
|------|------|
| 新增 `prevLastFramePrompt` 追踪变量 | 循环遍历时追踪前序镜头的尾帧 prompt |
| 注入 `continuityContext.prevLastFramePrompt` | 供 frame executor 和 reviewer 获取上镜结尾状态 |
| 追踪逻辑：`if (StrUtil.isNotBlank(item.getLastFramePrompt())) prevLastFramePrompt = item.getLastFramePrompt()` | 每个镜头处理完后更新 |

#### 第二层：Prompt 层 — `storyboard-frame-executor.system.md`

| 改动 | 说明 |
|------|------|
| `continuityContext` 字段列表新增 `prevLastFramePrompt` | 声明上镜尾帧 prompt 字段 |
| 新增"首帧承接上镜尾帧"规则（§3 连贯性规则内） | frameType=first 时最高优先级：锚定来源 prevLastFramePrompt → prevVideoPrompt → prevContent |
| 时序截断规则升级 | 不是孤立提取第一个动词，而是以上镜结尾状态为起点找第一个动作 |
| `review_frame_prompt` 调用 JSON 模板更新 | `continuityContext` 中加入 `prevLastFramePrompt` |
| 错误/正确示例 | 上镜尾帧="蹲在座位前掏卷子" → 本镜首帧应为"蹲着掏卷子的起始瞬间"而非"端详水果糖" |

#### 第三层：校正层 — `prompt-reviewer.system.md`

| 改动 | 说明 |
|------|------|
| 输入字段描述新增 `prevLastFramePrompt` | 声明 continuityContext 中包含该字段 |
| `interShotContinuity` 新增首帧承接检查 | 仅 `promptType=frame` 且为首帧时触发 |
| 动作跳跃检测 | 检查首帧 prompt 是否出现 content 中最后一个动作的关键词（如"端详""拿起"），发现则修正 |
| 锚定来源优先级 | prevLastFramePrompt → prevVideoPrompt → prevContent |
| 错误/正确示例与修复规则 | 同第二层，reviewer 侧执行修正 |

### 12.4 尾帧复用为首帧（像素级连贯）

**核心思路**：场次内非首镜头的首帧直接复用上镜尾帧图片 URL，跳过生图调用。

**原理**：上镜尾帧是上镜视频的结尾定格状态，本镜首帧从此状态开始延续。视频模型会按本镜自己的 video prompt 运镜和动作，首帧只是起始视觉锚点。像素级复用保证角色长相、光线、场景布局零漂移，且省一次生图调用。

**双层实现**：

**① 前端 API 层短路（优先，零 LLM 开销）**：前端点击"生成首帧"时，先检查同场次上镜是否有尾帧图。有则直接调 `POST /api/storyboard/item/{id}/reuse-prev-frame`，后端 `StoryboardService.reusePrevLastFrameAsFirstFrame` 查 DB 找到上镜尾帧 URL + prompt，写入当前镜头首帧字段。整个过程不触发 LLM Agent pipeline，零 token 消耗。失败时自动回退到正常 LLM 生成流程。

**② LLM Agent 层兜底**：frame executor 在 `frameType=first` 时优先检查 `continuityContext.prevLastFrameImageUrl`：
- **存在**：直接调用 `update_storyboard_item_frame` 写入上镜尾帧 URL 和 prompt，跳过生图流程
- **不存在**（首镜头或上镜无尾帧）：走正常生图流程

### 12.5 连贯性保障链路（更新后）

```
镜头1 生成首帧 → firstFramePrompt 保存
  → 生成尾帧 → lastFramePrompt + lastFrameImageUrl 保存
  ↓ 同场顺序调度（Semaphore 强制串行）
镜头2 调用 get_storyboard_scene_items
  → continuityContext（prevContent + prevFirstFramePrompt + prevLastFramePrompt + prevVideoPrompt + prevLastFrameImageUrl）
  ↓
frame executor 检查 prevLastFrameImageUrl
  → 存在：直接复用上镜尾帧 URL 作为本镜首帧（跳过生图）
  → 不存在：正常生图流程（首帧承接上镜尾帧状态 + review 校正）
  ↓
update_storyboard_item_frame / generate_image 落库 firstFrameImageUrl + firstFramePrompt
  ↓ 供镜头3的 continuityContext.prevLastFrameImageUrl + prevLastFramePrompt 使用...
```

### 12.6 涉及文件

| 文件 | 改动类型 |
|------|----------|
| `GetStoryboardSceneItemsToolExecutor.java` | Java — 新增 prevLastFramePrompt 追踪与注入 + 注入条件补全 |
| `StoryboardService.java` | Java — 新增 `reusePrevLastFrameAsFirstFrame` 方法（DB 层直接复用上镜尾帧） |
| `StoryboardController.java` | Java — 新增 `POST /item/{id}/reuse-prev-frame` 端点 |
| `storyboard.ts`（前端 API） | TS — 新增 `reusePrevFrame` 接口 |
| `storyboards/page.tsx`（前端页面） | TSX — `handleGenerateItemFrame` 新增尾帧复用短路逻辑（优先 API 复用，失败回退 LLM） |
| `storyboard-frame-executor.system.md` | Prompt — 新增尾帧复用短路流程 + 首帧承接规则 + 时序截断升级 + JSON 模板补全 promptType/frameType + 优先级标签修正 |
| `storyboard-frame-gen.system.md` | Prompt — 新增尾帧复用说明（区分生成/复用数量） |
| `prompt-reviewer.system.md` | Prompt — interShotContinuity 新增首帧承接检查 + 输入字段补全 frameType + 条件标签统一为 frameType=first/last + 第6项与第7项协同说明 |
| `storyboard-video-executor.system.md` | Prompt — continuityContext 补全 prevLastFramePrompt + JSON 模板补全 promptType=video |

### 12.7 验收标准

| # | 标准 | 检查方式 |
|---|------|----------|
| V13 | 场次内非首镜头的首帧 prompt 不得包含 content 中最后一个动作的关键词 | 人工抽查或 LLM 抽查 |
| V14 | continuityContext 中包含 prevLastFramePrompt（当上镜有尾帧 prompt 时） | 检查 get_storyboard_scene_items 返回 JSON |
| V15 | 首帧 prompt 的角色状态应与上镜尾帧 prompt 描述的结尾状态衔接 | 对比 prevLastFramePrompt 与本镜 firstFramePrompt |
| V16 | review_frame_prompt 调用 JSON 中包含 promptType 和 frameType 字段 | 检查 frame executor 调用日志 |
| V17 | reviewer 能根据 frameType 区分首帧/尾帧并应用对应规则 | 检查 reviewer 返回的 revisedPrompt |
| V18 | 当 continuityContext.prevLastFrameImageUrl 存在时，首帧直接复用上镜尾帧 URL，不调用 generate_image | 检查 frame executor 是否调用 update_storyboard_item_frame 而非 generate_image |
| V19 | 复用首帧的 firstFramePrompt 应等于上镜 lastFramePrompt（描述图片实际内容） | 对比 DB 中相邻镜头的 firstFramePrompt 与 lastFramePrompt |
| V20 | 前端点击"生成首帧"且上镜有尾帧时，直接调 reuse-prev-frame API，不触发 LLM pipeline | 检查网络请求中是否有 `/reuse-prev-frame` 且无 `/api/ai/pipeline/run` |
| V21 | reuse-prev-frame API 失败时自动回退到正常 LLM 生成流程 | 模拟 API 报错，检查是否触发 pipeline/run |

---

## 十三、结论

问题本质不是「生图模型不稳」，而是 **资产本体缺失 + 精确去重过弱 + 创建即膨胀 + 全量生图 + 关联时序滞后**。

P0 八项改动即可覆盖最高 ROI：规则文案 + 跨类型冲突 + three_view 门禁 + 生图门禁 + 关联前移 + 设备黑名单 + **分镜绑定 ID 校验 + 分镜绑定 prompt 强化**。

P1 的 Reviewer Agent、分镜复核绑定准确性检查、剧本场次交叉校验进一步自动化纠错。P2 的前端确认和 embedding 检索为可选增强，不阻塞主路径。

§十二 首帧承接上镜尾帧优化已实施（三层改动：数据层 + Prompt 层 + 校正层），解决镜头间动作连贯性问题。
