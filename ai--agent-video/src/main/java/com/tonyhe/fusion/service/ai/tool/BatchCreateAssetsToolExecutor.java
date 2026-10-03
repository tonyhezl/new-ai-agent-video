package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.asset.AssetService;
import com.tonyhe.fusion.service.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量创建资产工具（batch_create_assets）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchCreateAssetsToolExecutor implements ToolExecutor {

    /**
     * 单次批量创建资产的最大数量
     */
    private static final int MAX_BATCH_SIZE = 10;

    /**
     * 拍摄设备黑名单关键词，禁止作为 prop 创建
     */
    private static final List<String> DEVICE_BLACKLIST = Arrays.asList(
            "穿梭机", "无人机", "飞行器", "轨道车", "摇臂", "斯坦尼康", "手持云台");

    /**
     * 元场景黑名单关键词
     */
    private static final List<String> META_SCENE_BLACKLIST = Arrays.asList(
            "各地", "合集", "若干场景");

    private final AssetService assetService;
    private final ProjectService projectService;

    /**
     * 校验资产是否违反设备黑名单或元场景黑名单
     */
    private String validateBlacklist(String type, String name) {
        if ("prop".equals(type)) {
            for (String keyword : DEVICE_BLACKLIST) {
                if (name.contains(keyword)) {
                    return String.format("道具名称包含拍摄设备关键词'%s'，禁止创建。拍摄设备不是画面道具", keyword);
                }
            }
        }
        if ("scene".equals(type)) {
            for (String keyword : META_SCENE_BLACKLIST) {
                if (name.contains(keyword)) {
                    return String.format("场景名称包含元场景关键词'%s'，禁止创建。请拆分为具体场景", keyword);
                }
            }
        }
        return null;
    }

    /**
     * 跨类型包含冲突检测：检查同项目下是否存在 name 包含关系的 scene/prop 对
     */
    private String checkCrossTypeConflict(Long projectId, String type, String name) {
        if (!"prop".equals(type) && !"scene".equals(type)) {
            return null;
        }
        // 检查 prop 是否与已有 scene 存在 name 包含关系
        if ("prop".equals(type)) {
            List<Asset> scenes = assetService.listByProject(projectId, "scene", null);
            for (Asset scene : scenes) {
                if (scene.getName().contains(name) || name.contains(scene.getName())) {
                    return String.format("道具'%s'与已有场景'%s'名称存在包含关系，可能为场景地标，建议写入场景 description 而非单独建道具", name, scene.getName());
                }
            }
        }
        // 检查 scene 是否与已有 prop 存在 name 包含关系
        if ("scene".equals(type)) {
            List<Asset> props = assetService.listByProject(projectId, "prop", null);
            for (Asset prop : props) {
                if (prop.getName().contains(name) || name.contains(prop.getName())) {
                    return String.format("场景'%s'与已有道具'%s'名称存在包含关系，请确认是否需要合并", name, prop.getName());
                }
            }
        }
        return null;
    }

    /**
     * 常见场景状态修饰词（用于识别同一空间的不同物理/时间/环境状态）
     */
    private static final Set<String> SCENE_STATE_KEYWORDS = Set.of(
            "废墟", "遗址", "残垣", "残壁", "毁坏", "毁灭", "破损", "破碎", "残破", "坍塌",
            "坍陷", "火海", "焦土", "战后", "战火", "灾后", "重建", "重构", "修复后", "搬空",
            "搬家中", "封印后", "夜景", "雪景", "雨景", "黄昏", "清晨", "白昼", "深夜"
    );

    /**
     * 层级分隔符列表
     */
    private static final List<String> HIERARCHY_DELIMITERS = List.of("·", "・", "/", "-", "_");

    /**
     * 同类型场景变体检测：检查新 scene 是否与已有 scene 构成同一空间的不同状态
     * <p>
     * 如「飞升台」vs「飞升台废墟」、「大殿」vs「大殿·废墟」→ 应使用 variant 而非新建主资产；
     * 但如「天玄城」vs「天玄城·天机铺」（层级子场景）→ 属于独立子空间，允许创建独立主资产。
     *
     * @return 拦截原因，null 表示通过
     */
    private String checkSceneVariantConflict(Long projectId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<Asset> existingScenes = assetService.listByProject(projectId, "scene", null);
        for (Asset scene : existingScenes) {
            String existingName = scene.getName();
            if (existingName == null || existingName.equals(name)) {
                continue;
            }

            // 检查两者是否构成同一空间的状态变体关系
            if (isSceneStateVariantConflict(name, existingName)) {
                return String.format(
                        "场景'%s'与已有场景'%s'疑似同一空间的不同状态（如破坏前后/昼夜环境）。"
                                + "请勿新建主资产，应使用 add_asset_item 为已有场景添加 variant 类型子资产，"
                                + "并在子资产 description 中描述该状态差异。生图时可用原场景 initial 图作为参考。",
                        name, existingName);
            }
        }
        return null;
    }

    /**
     * 判断两个场景名称是否为同一空间的状态变体冲突
     */
    private boolean isSceneStateVariantConflict(String name1, String name2) {
        String longer = name1.length() >= name2.length() ? name1 : name2;
        String shorter = name1.length() >= name2.length() ? name2 : name1;

        // 如果较长名称并不包含较短名称，则不可能是变体
        if (!longer.contains(shorter)) {
            return false;
        }

        // 检查是否为层级子场景：例如 "A·B" 和 "A"
        // 如果 longer 是由 shorter 加上层级分隔符和具体子区域名构成的，需判断子区域名是否为纯状态词
        for (String delim : HIERARCHY_DELIMITERS) {
            if (longer.startsWith(shorter + delim)) {
                String subPart = longer.substring((shorter + delim).length()).trim();
                // 如果子部分属于状态修饰词（如 "大殿·废墟"），则是变体冲突；如果是具体子区域（如 "宗门·藏经阁"），则放行
                return isPureStateModifier(subPart);
            }
            if (longer.endsWith(delim + shorter)) {
                String prefixPart = longer.substring(0, longer.length() - (delim + shorter).length()).trim();
                return isPureStateModifier(prefixPart);
            }
        }

        // 检查无分隔符或括号包裹的直接后缀修饰，例如 "主峰废墟"、"大殿(战后)"
        String diff = longer.replace(shorter, "").replaceAll("[()（）\\[\\]{}\\-_·・/ ]", "").trim();
        if (!diff.isEmpty() && isPureStateModifier(diff)) {
            return true;
        }

        return false;
    }

    /**
     * 判断字符串是否为纯状态修饰词
     */
    private boolean isPureStateModifier(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String clean = text.replaceAll("[()（）\\[\\]{}\\-_·・/ ]", "").trim();
        if (SCENE_STATE_KEYWORDS.contains(clean)) {
            return true;
        }
        for (String kw : SCENE_STATE_KEYWORDS) {
            if (clean.equals(kw) || clean.equals(kw + "版") || clean.equals("状态_" + kw)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToolName() {
        return "batch_create_assets";
    }

    @Override
    public String getDisplayName() {
        return "批量创建资产";
    }

    @Override
    public String getToolDescription() {
        return String.format(
                """
                        从剧本中提取角色、场景、道具等信息并批量创建资产。

                        **使用流程**：
                        1. 先使用 list_project_assets 查看已有资产
                        2. 调用 query_asset_metadata 获取各类型资产的属性字段定义及填写指南（fieldDescription）
                        3. 调用此工具创建缺失的资产（工具自动按 project_id + type + name 去重，已有资产会被复用）
                        4. 创建完成后，可使用 add_asset_item 为资产添加参考图

                        **注意事项**：
                        - 单次最多创建 %d 个资产，超出请分次调用
                        - assets 数组中每项必须包含 type 和 name
                        - 支持的资产类型：character（角色）、scene（场景）、prop（道具）
                        - properties 必须包含 query_asset_metadata 返回的**所有** fieldKey，每个 key 都必须填写，确实无法确定的填'无'。填写内容严格遵循对应 fieldDescription 中的要求
                        - 每个资产必须提供 initialItem 填充初始子资产的 name 和 properties（properties 同样须包含所有 fieldKey，无值填'无'）

                        ## 角色资产命名规范（最高优先级）

                        - 每个角色资产只使用一个名称，以该角色在剧情中最主要/最常出现的名字为准
                        - 禁止用"/"或其他分隔符连接多个名称（如"王德发/花非烟"是错误的，应只写"王德发"或只写"花非烟"）
                        - 穿越、重生、变身、性别转换等情节导致角色名称变化时，只用剧中出场最多的那个名字
                        - 如果前后名字出场各占一半，以剧本开篇使用的名字为准
                        - 在 description 中可以补充说明角色的其他名字/身份（如"王德发，穿越后名花非烟"）

                        ## 角色类资产 description 描述规范

                        描述要求突出角色特色，有细节质感，使用中文 80-150 字：
                        - 面部：脸型、五官特征（如高挺鼻梁、深邃眼窝、薄唇等）
                        - 眼睛：形状、大小（禁止描写眼睛颜色）
                        - 头发：颜色、长度、发型、发质（如蓬松卷发、挑染银灰等）
                        - 体型：身高感、体态、肩宽、腰线
                        - 皮肤：只写质感和独特标记（光滑/粗糙、雀斑/疤痕/纹身），禁止描述肤色
                        - 服装：⚠️ description/appearance 只写"素色紧身衣/中性内衣"（initial 基体）
                        - 鞋子：写"赤足"或"素色软底鞋"
                        - 配饰：initial 不写配饰

                        **properties.clothing（默认常服，必填有内容时）**：
                        - 填写角色全片默认日常着装的款式、材质、配色、鞋子、配饰（中文）
                        - 系统会自动创建「XX 常服」costume 子资产，并把 clothing 写入该子资产 properties
                        - 禁止填"素色紧身衣/中性内衣"或"无"
                        - 换装/特殊场合服装不要写在 clothing，后续用 batch_create_asset_items 建额外 costume

                        非人类角色（动物、神话生物等）不受上述模板限制，以名称/物种名开头自由描述核心特征。

                        **描述禁忌**：
                        - 禁写表情、姿态、动作、情绪
                        - 禁写背景、环境、道具、光影、画风
                        - 禁写身体部位颜色（肤色/唇色/眼色/脸色），头发和服装颜色可写
                        - 禁写抽象气质（如"气场强大"、"成熟稳重的气息"）
                        - 禁用不确定词（或/可能/也许/大概）
                        - 服装发型须符合故事年代设定
                        - 原文有描述以原文优先（颜色禁忌仍需遵守）

                        场景/道具等其他类型按实际特征描述即可。

                        ## 资产边界与准入规则（创建时必须遵守）

                        ### scene vs prop 边界
                        - scene（场景）：可进入的空间容器，角色可在其中活动。固定地标、建筑构件、陈设写入 scene 的 description，不单独建 prop
                        - prop（道具）：可携带、跨≥2场复现、需特写一致性的物品。满足以下≥2条才建 prop：1.推动剧情 2.跨≥2场出现 3.需特写/手持一致性 4.可被角色携带或独立运动
                        - 反例：广场玉碑→写入 scene.description；始终戴在头上的头盔→写入角色 description
                        - 正例：被甩飞的黄色头盔；传讯玉简

                        ### 穿戴物三态决策
                        - 始终穿戴、无离体戏 → 写入角色 description，不建 prop
                        - 会离体、被抛、单独特写 → 建 prop
                        - 场景固定地标 → 只进 scene.description

                        ### 场景粒度
                        - 一个可拍地点一个 scene，子区域用命名层级（如`青云宗·祭天台`）
                        - **同一空间不同状态（破坏/昼夜/废墟）→ 同一 scene 的 variant 子资产，不新建主资产**。例如「飞升台」和「飞升台废墟」不应建两个 scene，应只建「飞升台」一个主资产，再用 add_asset_item 添加 variant 子资产描述废墟状态
                        - 禁止元场景：`九州各地`、`各地`等合集类名称

                        ### 角色重要性分级
                        - lead/support：建库+生 initial+three_view+默认常服 costume
                        - extra（龙套）：建库+生 initial+默认常服 costume，不生 three_view
                        - background（群演）：仅建库，不生图
                        - 群像合并：群演合并为一个 character
                        - **importance 必须写入主资产 properties**：创建角色资产时，properties 中必须包含 importance 字段（值为 lead/support/extra/background），否则系统无法识别龙套/群演，会错误生成三视图
                        - **clothing 必须写入主资产 properties**：有可识别日常着装时必须填写，供默认「XX 常服」生图使用

                        ### 拍摄设备黑名单
                        以下关键词禁止作为 prop 创建：穿梭机、无人机、飞行器、轨道车、摇臂、斯坦尼康、手持云台

                        返回值：created（新建）、existing（复用）、allAssets（全部）
                        """,
                MAX_BATCH_SIZE);
    }

    @Override
    public String getParametersSchema() {
        return String.format(
                """
                        {
                            "type": "object",
                            "properties": {
                                "projectId": {
                                    "type": "number",
                                    "description": "项目ID（必填）"
                                },
                                "assets": {
                                    "type": "array",
                                    "maxItems": %d,
                                    "description": "资产列表（最多%d个，超出请分次调用）",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "type": {
                                                "type": "string",
                                                "enum": ["character", "scene", "prop"],
                                                "description": "资产类型（必填）"
                                            },
                                            "name": {
                                                "type": "string",
                                                "description": "资产名称（必填），不要与已有资产重复"
                                            },
                                            "description": {
                                                "type": "string",
                                                "description": "资产描述（必填）。角色类资产须撰写可视化静态外貌描述（80-150字中文），禁止描述表情、姿态、动作、情绪（如微笑、困惑、奔跑等都不允许）。场景/道具等按实际特征描述"
                                            },
                                            "properties": {
                                                "type": "object",
                                                "additionalProperties": { "type": "string" },
                                                "description": "属性键值对（必填），必须包含 query_asset_metadata 返回的所有 fieldKey，每个都要填写，无值填'无'。填写内容遵循对应的 fieldDescription"
                                            },
                                            "initialItem": {
                                                "type": "object",
                                                "description": "初始子资产内容（必填），用于填充自动创建的初始子资产。name 填写具体有意义的名称（如角色名、场景名等）",
                                                "properties": {
                                                    "name": { "type": "string", "description": "子资产名称，应使用具体有意义的名称（如'张三'、'桌子'等）" },
                                                    "itemType": { "type": "string" },
                                                    "imageUrl": { "type": "string" },
                                                    "properties": {
                                                        "type": "object",
                                                        "additionalProperties": { "type": "string" },
                                                        "description": "子资产属性（必填），必须包含 query_asset_metadata 返回的所有 fieldKey，每个都要填写，无值填'无'。填写规则同主资产，遵循对应的 fieldDescription"
                                                    }
                                                },
                                                "required": ["name", "properties"]
                                            }
                                        },
                                        "required": ["type", "name", "initialItem"]
                                    }
                                }
                            },
                            "required": ["projectId", "assets"]
                        }
                        """,
                MAX_BATCH_SIZE, MAX_BATCH_SIZE);
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            JSONArray assetsArray = params.getJSONArray("assets");

            if (projectId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 projectId").toString();
            }
            if (assetsArray == null || assetsArray.isEmpty()) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 assets 数组").toString();
            }
            if (assetsArray.size() > MAX_BATCH_SIZE) {
                return JSONUtil.createObj().set("status", "error")
                        .set("message", String.format("单次最多创建 %d 个资产，当前传入 %d 个。请分多次调用。",
                                MAX_BATCH_SIZE, assetsArray.size()))
                        .toString();
            }

            Long userId = context.getUserId();

            if (!projectService.canAccessProject(projectId, userId)) {
                return JSONUtil.createObj().set("status", "error")
                        .set("message", "无权访问该项目").toString();
            }

            List<JSONObject> created = new ArrayList<>();
            List<JSONObject> existing = new ArrayList<>();

            for (int i = 0; i < assetsArray.size(); i++) {
                JSONObject data = assetsArray.getJSONObject(i);
                String type = data.getStr("type");
                String name = data.getStr("name");

                if (type == null || name == null || name.isBlank()) {
                    continue;
                }

                // 设备黑名单 + 元场景黑名单校验
                String blacklistError = validateBlacklist(type, name);
                if (blacklistError != null) {
                    log.warn("[batch_create_assets] 资产创建被黑名单拦截: type={}, name={}, reason={}", type, name, blacklistError);
                    created.add(JSONUtil.createObj()
                            .set("type", type).set("name", name)
                            .set("status", "rejected")
                            .set("reason", blacklistError));
                    continue;
                }

                // 跨类型包含冲突检测
                String conflictWarning = checkCrossTypeConflict(projectId, type, name);
                if (conflictWarning != null) {
                    log.warn("[batch_create_assets] 跨类型冲突警告: type={}, name={}, warning={}", type, name, conflictWarning);
                    // 记录警告但不阻塞创建，让 LLM 自行决定
                }

                // 同类型场景变体检测：拦截同一空间不同状态的重复 scene
                if ("scene".equals(type)) {
                    String variantError = checkSceneVariantConflict(projectId, name);
                    if (variantError != null) {
                        log.warn("[batch_create_assets] 场景变体冲突拦截: name={}, reason={}", name, variantError);
                        created.add(JSONUtil.createObj()
                                .set("type", type).set("name", name)
                                .set("status", "rejected")
                                .set("reason", variantError));
                        continue;
                    }
                }

                // 去重检查
                Asset found = assetService.findByProjectTypeAndName(projectId, type, name);
                if (found != null) {
                    existing.add(JSONUtil.createObj()
                            .set("type", type).set("name", name).set("assetId", found.getId()));
                } else {
                    // 对于 character 类型，确保 importance 字段存在于主资产 properties 中
                    // AI 可能只在 initialItem.properties 中传了 importance，需合并到主资产
                    String propertiesJson = null;
                    if (data.containsKey("properties")) {
                        propertiesJson = data.getJSONObject("properties").toString();
                    }
                    if ("character".equals(type)) {
                        boolean hasImportance = false;
                        if (propertiesJson != null) {
                            try {
                                JSONObject propsObj = JSONUtil.parseObj(propertiesJson);
                                hasImportance = propsObj.getStr("importance") != null;
                            } catch (Exception ignored) {}
                        }
                        if (!hasImportance) {
                            // 从 initialItem.properties 中提取 importance 合并到主资产
                            JSONObject initialItemData = data.getJSONObject("initialItem");
                            if (initialItemData != null && initialItemData.containsKey("properties")) {
                                try {
                                    JSONObject initProps = initialItemData.getJSONObject("properties");
                                    String importance = initProps.getStr("importance");
                                    if (importance != null) {
                                        JSONObject mergedProps = propertiesJson != null
                                                ? JSONUtil.parseObj(propertiesJson) : new JSONObject();
                                        mergedProps.set("importance", importance);
                                        propertiesJson = mergedProps.toString();
                                        log.info("[batch_create_assets] 从 initialItem.properties 提取 importance={} 合并到角色 '{}' 主资产", importance, name);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    Asset asset = Asset.builder()
                            .projectId(projectId)
                            .type(type)
                            .name(name)
                            .description(data.getStr("description"))
                            .properties(propertiesJson)
                            .sourceType(2)
                            .userId(userId)
                            .build();
                    Asset saved = assetService.create(asset);

                    // 处理 initialItem
                    JSONObject initialItemData = data.getJSONObject("initialItem");
                    if (initialItemData != null) {
                        List<AssetItem> items = assetService.listItems(saved.getId());
                        if (!items.isEmpty()) {
                            AssetItem first = items.get(0);
                            if (initialItemData.getStr("name") != null)
                                first.setName(initialItemData.getStr("name"));
                            if (initialItemData.getStr("itemType") != null)
                                first.setItemType(initialItemData.getStr("itemType"));
                            if (initialItemData.getStr("imageUrl") != null)
                                first.setImageUrl(initialItemData.getStr("imageUrl"));
                            if (initialItemData.containsKey("properties")) {
                                JSONObject initProps = initialItemData.getJSONObject("properties");
                                // initial 是素体基体，不应携带服装描述（clothing 由 costume 常服子资产承载）
                                initProps.remove("clothing");
                                first.setProperties(initProps.toString());
                            }
                            assetService.updateItem(first);
                        }
                    }

                    created.add(JSONUtil.createObj()
                            .set("type", type).set("name", name).set("assetId", saved.getId()));
                }
            }

            List<JSONObject> allAssets = new ArrayList<>(created);
            allAssets.addAll(existing);

            return JSONUtil.createObj()
                    .set("created", created)
                    .set("existing", existing)
                    .set("allAssets", allAssets)
                    .set("createdCount", created.size())
                    .set("existingCount", existing.size())
                    .set("message", String.format("新建 %d 个资产，复用 %d 个已有资产", created.size(), existing.size()))
                    .toString();
        } catch (Exception e) {
            log.error("批量创建资产失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "创建失败: " + e.getMessage()).toString();
        }
    }
}
