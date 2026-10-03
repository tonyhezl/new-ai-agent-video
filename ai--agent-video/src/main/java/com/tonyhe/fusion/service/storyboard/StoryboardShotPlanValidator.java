package com.tonyhe.fusion.service.storyboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates the final, reviewed storyboard shot plan before any database write.
 */
@Component
public class StoryboardShotPlanValidator {

    private static final Set<String> PRIMARY_TYPES = Set.of(
            "GENERAL_NARRATIVE", "DIALOGUE", "ACTION", "CONTINUOUS_VISUAL", "MONTAGE");
    private static final Set<String> CONTINUITY_MODES = Set.of("CUT", "SEAMLESS");
    private static final List<String> REQUIRED_SHOT_TEXT_FIELDS = List.of(
            "content", "sceneExpectation", "shotType", "cameraMovement", "cameraAngle",
            "cameraEquipment", "sound", "soundEffect");

    public void validate(JSONObject params, List<JSONObject> shots) {
        if (!Boolean.TRUE.equals(params.getBool("reviewed"))) {
            throw new IllegalArgumentException("必须先调用 review_storyboard_content 完成复核");
        }
        if (!Boolean.TRUE.equals(params.getBool("reviewApproved"))) {
            throw new IllegalArgumentException("分镜复核尚未通过，禁止保存");
        }

        JSONArray unresolvedErrors = params.getJSONArray("unresolvedReviewErrors");
        if (unresolvedErrors == null) {
            throw new IllegalArgumentException("缺少 unresolvedReviewErrors 复核结果");
        }
        if (!unresolvedErrors.isEmpty()) {
            throw new IllegalArgumentException("分镜仍有未解决的复核错误: " + unresolvedErrors);
        }

        Integer reviewedShotCount = params.getInt("reviewedShotCount");
        if (reviewedShotCount == null || reviewedShotCount != shots.size()) {
            throw new IllegalArgumentException("复核镜头数与最终保存镜头数不一致");
        }

        validateShotStrategy(params.getJSONObject("shotStrategy"));
        Map<String, Integer> beatIndexes = validateBeats(params.getJSONArray("beats"));
        Set<String> coveredBeatIds = new HashSet<>();
        BeatSpan previousSpan = null;

        for (int i = 0; i < shots.size(); i++) {
            BeatSpan currentSpan = validateShot(shots.get(i), i, beatIndexes, coveredBeatIds);
            if (previousSpan != null
                    && (currentSpan.firstIndex() < previousSpan.firstIndex()
                    || currentSpan.lastIndex() < previousSpan.lastIndex())) {
                throw new IllegalArgumentException("shots[" + i + "].beatIds 与剧情节拍顺序倒置");
            }
            previousSpan = currentSpan;
        }

        Set<String> missingBeatIds = new HashSet<>(beatIndexes.keySet());
        missingBeatIds.removeAll(coveredBeatIds);
        if (!missingBeatIds.isEmpty()) {
            throw new IllegalArgumentException("存在未被镜头覆盖的剧本节拍: " + missingBeatIds);
        }
    }

    private void validateShotStrategy(JSONObject strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("缺少 shotStrategy");
        }
        String primaryType = strategy.getStr("primaryType");
        String normalized = StrUtil.blankToDefault(primaryType, "").trim().toUpperCase(Locale.ROOT);
        if (!PRIMARY_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("shotStrategy.primaryType 非法: " + primaryType);
        }
        if (strategy.getJSONArray("tags") == null) {
            throw new IllegalArgumentException("shotStrategy.tags 必须提供数组，可为空数组");
        }
    }

    private Map<String, Integer> validateBeats(JSONArray beats) {
        if (beats == null || beats.isEmpty()) {
            throw new IllegalArgumentException("beats 不能为空");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < beats.size(); i++) {
            JSONObject beat = beats.getJSONObject(i);
            String id = normalizeBeatId(beat.getStr("id"));
            if (StrUtil.isBlank(id) || StrUtil.isBlank(beat.getStr("description"))) {
                throw new IllegalArgumentException("beats[" + i + "] 缺少 id 或 description");
            }
            if (result.putIfAbsent(id, i) != null) {
                throw new IllegalArgumentException("beats 存在重复 ID: " + id);
            }
        }
        return result;
    }

    private BeatSpan validateShot(
            JSONObject shot,
            int index,
            Map<String, Integer> beatIndexes,
            Set<String> coveredBeatIds) {
        String fieldPrefix = "shots[" + index + "]";
        for (String field : REQUIRED_SHOT_TEXT_FIELDS) {
            String value = shot.getStr(field);
            if (StrUtil.isBlank(value)) {
                throw new IllegalArgumentException(fieldPrefix + "." + field + " 不能为空");
            }
            if (value.contains("__MERGE_INTO_PREV__")) {
                throw new IllegalArgumentException("最终镜头列表禁止包含旧合并标记: " + fieldPrefix);
            }
        }

        BigDecimal duration;
        try {
            duration = new BigDecimal(shot.getStr("duration"));
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldPrefix + ".duration 必须是正数", e);
        }
        if (duration.signum() <= 0) {
            throw new IllegalArgumentException(fieldPrefix + ".duration 必须大于 0");
        }

        JSONArray shotBeatIds = shot.getJSONArray("beatIds");
        if (shotBeatIds == null || shotBeatIds.isEmpty()) {
            throw new IllegalArgumentException(fieldPrefix + ".beatIds 不能为空");
        }
        Set<String> shotBeatIdSet = new HashSet<>();
        int firstBeatIndex = -1;
        int previousBeatIndex = -1;
        for (int i = 0; i < shotBeatIds.size(); i++) {
            String beatId = normalizeBeatId(shotBeatIds.getStr(i));
            Integer beatIndex = beatIndexes.get(beatId);
            if (beatIndex == null) {
                throw new IllegalArgumentException(fieldPrefix + " 引用了不存在的节拍: " + beatId);
            }
            if (!shotBeatIdSet.add(beatId)) {
                throw new IllegalArgumentException(fieldPrefix + ".beatIds 包含重复节拍: " + beatId);
            }
            if (previousBeatIndex >= 0 && beatIndex != previousBeatIndex + 1) {
                throw new IllegalArgumentException(fieldPrefix + ".beatIds 必须按剧情顺序覆盖连续节拍");
            }
            if (firstBeatIndex < 0) {
                firstBeatIndex = beatIndex;
            }
            previousBeatIndex = beatIndex;
            coveredBeatIds.add(beatId);
        }

        JSONObject delta = shot.getJSONObject("sceneStateDelta");
        if (delta == null || delta.getJSONArray("changes") == null) {
            throw new IllegalArgumentException(fieldPrefix
                    + ".sceneStateDelta.changes 必须提供数组，可为空数组");
        }

        String continuityMode = StrUtil.blankToDefault(shot.getStr("continuityMode"), "")
                .trim().toUpperCase(Locale.ROOT);
        if (!CONTINUITY_MODES.contains(continuityMode)) {
            throw new IllegalArgumentException(fieldPrefix + ".continuityMode 仅支持 CUT 或 SEAMLESS");
        }
        return new BeatSpan(firstBeatIndex, previousBeatIndex);
    }

    private String normalizeBeatId(String value) {
        return StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
    }

    private record BeatSpan(int firstIndex, int lastIndex) {
    }
}
