package com.tonyhe.fusion.service.ai.tool;

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

import java.util.Arrays;
import java.util.List;

/**
 * 创建单个资产工具（create_asset）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssetCreateToolExecutor implements ToolExecutor {

    private final AssetService assetService;
    private final ProjectService projectService;

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
        if ("prop".equals(type)) {
            List<Asset> scenes = assetService.listByProject(projectId, "scene", null);
            for (Asset scene : scenes) {
                if (scene.getName().contains(name) || name.contains(scene.getName())) {
                    return String.format("道具'%s'与已有场景'%s'名称存在包含关系，可能为场景地标，建议写入场景 description 而非单独建道具", name, scene.getName());
                }
            }
        }
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

    @Override
    public String getToolName() {
        return "create_asset";
    }

    @Override
    public String getDisplayName() {
        return "创建资产";
    }

    @Override
    public String getToolDescription() {
        return """
                创建新资产（角色/场景/道具），用于管理项目中的各种创作元素。
                创建时应尽可能补充完整的信息，特别是：
                - 角色（character）：外貌（appearance，基体素色内衣）、默认常服（clothing）、性格（personality）、年龄（age）等
                - 场景（scene）：地点描述、时间、氛围、空间布局等
                - 道具（prop）：用途、外观特征、尺寸材质等

                这些信息应填入 properties 字段（key-value 键值对），方便后续生图时使用。
                角色的 properties.clothing 填写默认日常着装；系统会写入自动创建的「XX 常服」costume 子资产。

                **重要**：
                1. name 不要重复，创建前建议先用 list_project_assets 查看已有资产
                2. 如需批量创建，请使用 batch_create_assets 工具
                3. 创建资产后会自动生成一个与资产同名的初始子资产（itemType=initial），角色类型还会自动生成三视图子资产（itemType=three_view）和默认常服子资产（itemType=costume，名称为"XX 常服"，并写入 clothing），无需手动创建
                4. appearance 只写素体；clothing 写默认常服，禁止把具体服装只写在 appearance 里

                **角色命名规范**：
                - 每个角色资产只使用一个名称，以该角色在剧情中最主要/最常出现的名字为准
                - 禁止用"/"或其他分隔符连接多个名称（如"王德发/花非烟"是错误的）
                - 穿越、重生、变身等导致角色名称变化的情况，只取在剧情中出场最多的那个名字
                - 如果某个角色前后各占一半，以剧本开篇使用的名字为准

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
                - 同一空间不同状态 → 同一 scene 的 variant，不新建主资产
                - 禁止元场景：`九州各地`、`各地`等合集类名称

                ### 角色重要性分级
                - lead/support：建库+生 initial+three_view+默认常服 costume
                - extra（龙套）：建库+生 initial+默认常服 costume，不生 three_view
                - background（群演）：仅建库，不生图
                - 群像合并：群演合并为一个 character
                - properties 须含 importance；有日常着装时须含 clothing

                ### 拍摄设备黑名单
                以下关键词禁止作为 prop 创建：穿梭机、无人机、飞行器、轨道车、摇臂、斯坦尼康、手持云台
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "projectId": {
                            "type": "number",
                            "description": "项目ID（必填）"
                        },
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
                            "description": "资产描述（可选），详细描述该资产的特征"
                        },
                        "properties": {
                            "type": "object",
                            "additionalProperties": { "type": "string" },
                            "description": "自定义属性键值对（可选），如 {\"appearance\": \"黑发高个\", \"age\": \"25\"}"
                        },
                        "initialItem": {
                            "type": "object",
                            "description": "初始子资产内容（可选），用于填充自动创建的初始子资产",
                            "properties": {
                                "name": { "type": "string", "description": "子资产名称，应使用具体有意义的名称（如角色名、场景名等）" },
                                "itemType": { "type": "string", "description": "子资产类型：initial(初始)/three_view(三视图)/variant(变体)/front(正面)/side(侧面)/back(背面)/detail(细节)/expression(表情)/pose(姿势)/original(原始)" },
                                "imageUrl": { "type": "string", "description": "子资产图片URL" },
                                "properties": { "type": "object", "additionalProperties": { "type": "string" }, "description": "子资产自定义属性" }
                            }
                        }
                    },
                    "required": ["type", "name", "projectId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            String type = params.getStr("type");
            String name = params.getStr("name");

            if (projectId == null || type == null || name == null) {
                return JSONUtil.createObj().set("status", "error")
                        .set("message", "缺少必要参数: projectId, type, name").toString();
            }

            // 设备黑名单 + 元场景黑名单校验
            String blacklistError = validateBlacklist(type, name);
            if (blacklistError != null) {
                log.warn("[create_asset] 资产创建被黑名单拦截: type={}, name={}, reason={}", type, name, blacklistError);
                return JSONUtil.createObj().set("status", "rejected")
                        .set("message", blacklistError).toString();
            }

            // 跨类型包含冲突检测
            String conflictWarning = checkCrossTypeConflict(projectId, type, name);
            if (conflictWarning != null) {
                log.warn("[create_asset] 跨类型冲突警告: type={}, name={}, warning={}", type, name, conflictWarning);
            }

            Long userId = context.getUserId();

            if (!projectService.canAccessProject(projectId, userId)) {
                return JSONUtil.createObj().set("status", "error")
                        .set("message", "无权访问该项目").toString();
            }

            Asset asset = Asset.builder()
                    .projectId(projectId)
                    .type(type)
                    .name(name)
                    .description(params.getStr("description"))
                    .properties(params.containsKey("properties") ? params.getJSONObject("properties").toString() : null)
                    .sourceType(2)
                    .userId(userId)
                    .build();

            Asset saved = assetService.create(asset);

            // 如果传入了 initialItem，用其数据更新自动创建的初始子资产
            JSONObject initialItemData = params.getJSONObject("initialItem");
            if (initialItemData != null) {
                List<AssetItem> items = assetService.listItems(saved.getId());
                if (!items.isEmpty()) {
                    AssetItem first = items.get(0);
                    if (initialItemData.getStr("name") != null) {
                        first.setName(initialItemData.getStr("name"));
                    }
                    if (initialItemData.getStr("itemType") != null) {
                        first.setItemType(initialItemData.getStr("itemType"));
                    }
                    if (initialItemData.getStr("imageUrl") != null) {
                        first.setImageUrl(initialItemData.getStr("imageUrl"));
                    }
                    if (initialItemData.containsKey("properties")) {
                        JSONObject initProps = initialItemData.getJSONObject("properties");
                        // initial 是素体基体，不应携带服装描述（clothing 由 costume 常服子资产承载）
                        initProps.remove("clothing");
                        first.setProperties(initProps.toString());
                    }
                    assetService.updateItem(first);
                }
            }

            return JSONUtil.createObj()
                    .set("assetId", saved.getId())
                    .set("type", type)
                    .set("name", name)
                    .set("message", String.format("资产 \"%s\" 创建成功", name)).toString();
        } catch (Exception e) {
            log.error("创建资产失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "创建失败: " + e.getMessage()).toString();
        }
    }
}
