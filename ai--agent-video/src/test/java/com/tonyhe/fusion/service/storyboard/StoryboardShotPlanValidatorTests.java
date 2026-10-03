package com.tonyhe.fusion.service.storyboard;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardShotPlanValidatorTests {

    private final StoryboardShotPlanValidator validator = new StoryboardShotPlanValidator();

    @Test
    void oneShotCanCoverMultipleConsecutiveBeats() {
        JSONObject params = approvedParams(beats("B1", "B2", "B3"), 1);
        JSONObject shot = validShot("B1", "B2", "B3");

        assertThatCode(() -> validator.validate(params, List.of(shot)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonConsecutiveBeatsInOneShotAreRejected() {
        JSONObject params = approvedParams(beats("B1", "B2", "B3"), 2);

        assertThatThrownBy(() -> validator.validate(params, List.of(
                validShot("B1", "B3"), validShot("B2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("连续节拍");
    }

    @Test
    void shotBeatRangesCannotMoveBackwardInNarrativeOrder() {
        JSONObject params = approvedParams(beats("B1", "B2", "B3"), 3);

        assertThatThrownBy(() -> validator.validate(params, List.of(
                validShot("B1", "B2"), validShot("B2", "B3"), validShot("B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("顺序倒置");
    }

    @Test
    void adjacentShotsMayShareTheSameBeat() {
        JSONObject params = approvedParams(beats("B1", "B2"), 3);

        assertThatCode(() -> validator.validate(params, List.of(
                validShot("B1"), validShot("B1", "B2"), validShot("B2"))))
                .doesNotThrowAnyException();
    }

    @Test
    void missingBeatCoverageIsRejected() {
        JSONObject params = approvedParams(beats("B1", "B2"), 1);

        assertThatThrownBy(() -> validator.validate(params, List.of(validShot("B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未被镜头覆盖")
                .hasMessageContaining("B2");
    }

    @Test
    void unapprovedReviewIsRejected() {
        JSONObject params = approvedParams(beats("B1"), 1)
                .set("reviewApproved", false);

        assertThatThrownBy(() -> validator.validate(params, List.of(validShot("B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("复核尚未通过");
    }

    @Test
    void unresolvedReviewErrorsAreRejected() {
        JSONObject params = approvedParams(beats("B1"), 1)
                .set("unresolvedReviewErrors", new JSONArray().put("MISSING_BEAT_COVERAGE"));

        assertThatThrownBy(() -> validator.validate(params, List.of(validShot("B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未解决的复核错误");
    }

    @Test
    void legacyMergeSentinelIsRejectedInsteadOfSilentlyDroppingShotData() {
        JSONObject params = approvedParams(beats("B1"), 1);
        JSONObject shot = validShot("B1").set("content", "__MERGE_INTO_PREV__");

        assertThatThrownBy(() -> validator.validate(params, List.of(shot)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("旧合并标记");
    }

    @Test
    void reviewedShotCountMustMatchFinalShots() {
        JSONObject params = approvedParams(beats("B1"), 2);

        assertThatThrownBy(() -> validator.validate(params, List.of(validShot("B1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("复核镜头数");
    }

    @Test
    void sceneStateDeltaMustAlwaysExposeChangesArray() {
        JSONObject params = approvedParams(beats("B1"), 1);
        JSONObject shot = validShot("B1").set("sceneStateDelta", JSONUtil.createObj());

        assertThatThrownBy(() -> validator.validate(params, List.of(shot)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sceneStateDelta.changes");
    }

    private JSONObject approvedParams(JSONArray beats, int reviewedShotCount) {
        return JSONUtil.createObj()
                .set("reviewed", true)
                .set("reviewApproved", true)
                .set("reviewedShotCount", reviewedShotCount)
                .set("unresolvedReviewErrors", new JSONArray())
                .set("shotStrategy", JSONUtil.createObj()
                        .set("primaryType", "DIALOGUE")
                        .set("tags", new JSONArray().put("ACTION")))
                .set("beats", beats);
    }

    private JSONArray beats(String... ids) {
        JSONArray result = new JSONArray();
        for (String id : ids) {
            result.put(JSONUtil.createObj()
                    .set("id", id)
                    .set("description", id + " 的叙事变化"));
        }
        return result;
    }

    private JSONObject validShot(String... beatIds) {
        JSONArray shotBeatIds = new JSONArray();
        for (String beatId : beatIds) {
            shotBeatIds.put(beatId);
        }
        return JSONUtil.createObj()
                .set("beatIds", shotBeatIds)
                .set("content", "角色进入房间并完成信息交换")
                .set("sceneExpectation", "中景，室内暖光，角色关系清楚")
                .set("shotType", "中景")
                .set("duration", 8)
                .set("cameraMovement", "缓推")
                .set("cameraAngle", "平视")
                .set("cameraEquipment", "轨道")
                .set("sound", "室内环境底噪")
                .set("soundEffect", "无")
                .set("continuityMode", "CUT")
                .set("sceneStateDelta", JSONUtil.createObj().set("changes", new JSONArray()));
    }
}
