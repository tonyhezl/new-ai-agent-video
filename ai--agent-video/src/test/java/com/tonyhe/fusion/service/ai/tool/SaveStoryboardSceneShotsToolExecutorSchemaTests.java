package com.tonyhe.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tonyhe.fusion.service.storyboard.StoryboardShotPlanValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SaveStoryboardSceneShotsToolExecutorSchemaTests {

    @Test
    void schemaRequiresReviewedBeatAwareFinalShots() {
        SaveStoryboardSceneShotsToolExecutor executor = new SaveStoryboardSceneShotsToolExecutor(
                null, null, new StoryboardShotPlanValidator());

        JSONObject schema = JSONUtil.parseObj(executor.getParametersSchema());
        JSONArray rootRequired = schema.getJSONArray("required");
        JSONObject shotSchema = schema.getJSONObject("properties")
                .getJSONObject("shots")
                .getJSONObject("items");
        JSONArray shotRequired = shotSchema.getJSONArray("required");

        assertThat(rootRequired.toList(String.class)).contains(
                "shotStrategy", "beats", "baseSceneAssetItemId", "reviewed", "reviewApproved",
                "reviewedShotCount", "unresolvedReviewErrors", "shots");
        assertThat(shotRequired.toList(String.class)).contains(
                "beatIds", "content", "duration", "sceneStateDelta", "continuityMode");
        assertThat((Object) shotSchema.getJSONObject("properties").getJSONObject("beatIds")).isNotNull();
    }
}
