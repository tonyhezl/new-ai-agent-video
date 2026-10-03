package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.entity.script.ScriptSceneItem;
import com.tonyhe.fusion.service.ai.ToolExecutionContext;
import com.tonyhe.fusion.service.ai.ToolExecutor;
import com.tonyhe.fusion.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 更新单个场次工具（update_script_scene）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateScriptSceneItemToolExecutor implements ToolExecutor {

    private final ScriptService scriptService;

    @Override
    public String getToolName() {
        return "update_script_scene";
    }

    @Override
    public String getDisplayName() {
        return "更新剧本场次";
    }

    @Override
    public String getToolDescription() {
        return """
                更新单个剧本场次的内容。可以修改场景标头、描述、对白等字段。
                仅传入需要修改的字段，未传入的字段保持不变。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptSceneItemId": { "type": "number", "description": "剧本场次ID" },
                        "sceneHeading": { "type": "string", "description": "场景标头" },
                        "location": { "type": "string", "description": "场景地点" },
                        "timeOfDay": { "type": "string", "description": "时间" },
                        "intExt": { "type": "string", "description": "内外景" },
                        "characters": { "type": "array", "items": { "type": "string" }, "description": "出场角色名列表" },
                        "sceneDescription": { "type": "string", "description": "场景描述" },
                        "dialogues": { "type": "array", "description": "对白数组" }
                    },
                    "required": ["scriptSceneItemId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long scriptSceneItemId = params.getLong("scriptSceneItemId");
            if (scriptSceneItemId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 scriptSceneItemId").toString();
            }

            ScriptSceneItem item = scriptService.getSceneById(scriptSceneItemId);
            if (params.containsKey("sceneHeading")) {
                item.setSceneHeading(params.getStr("sceneHeading"));
            }
            if (params.containsKey("location")) {
                item.setLocation(params.getStr("location"));
            }
            if (params.containsKey("timeOfDay")) {
                item.setTimeOfDay(params.getStr("timeOfDay"));
            }
            if (params.containsKey("intExt")) {
                item.setIntExt(params.getStr("intExt"));
            }
            if (params.containsKey("characters")) {
                item.setCharacters(params.getJSONArray("characters").toString());
            }
            if (params.containsKey("sceneDescription")) {
                item.setSceneDescription(params.getStr("sceneDescription"));
            }
            if (params.containsKey("dialogues")) {
                item.setDialogues(params.getJSONArray("dialogues").toString());
            }

            scriptService.updateScene(item);
            return JSONUtil.createObj()
                    .set("scriptSceneItemId", scriptSceneItemId)
                    .set("message", "场次更新成功").toString();
        } catch (Exception e) {
            log.error("更新场次失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "更新失败: " + e.getMessage()).toString();
        }
    }
}
