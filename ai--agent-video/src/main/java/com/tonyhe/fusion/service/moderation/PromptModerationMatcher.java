package com.tonyhe.fusion.service.moderation;

import com.tonyhe.fusion.entity.workbench.ModerationWord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存词表匹配器。
 * <p>
 * 使用 CopyOnWrite 快照策略：检查线程只读快照，无锁；
 * 词表变更时整体替换快照。
 * <ul>
 *   <li>exact 词：HashSet 精确匹配（按空白分词后 contains）</li>
 *   <li>contains 词：逐词子串扫描（P0 用朴素匹配，后续可换 Aho-Corasick）</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptModerationMatcher {

    private final AtomicReference<MatcherSnapshot> snapshotRef = new AtomicReference<>();

    /**
     * 从数据库加载启用的词表并构建快照。
     *
     * @param words 启用的 ModerationWord 列表
     */
    public void load(List<ModerationWord> words) {
        MatcherSnapshot snapshot = buildSnapshot(words);
        snapshotRef.set(snapshot);
        log.info("PromptModerationMatcher loaded: {} exact words, {} contains words",
                snapshot.exactSet.size(), snapshot.containsList.size());
    }

    /**
     * 重新加载词表（后台修改后调用）。
     */
    public void reload(List<ModerationWord> words) {
        load(words);
    }

    /**
     * 匹配归一化后的文本。
     *
     * @param normalizedText 归一化后的提示词
     * @return 命中结果，未命中返回 null
     */
    public MatchResult match(String normalizedText) {
        MatcherSnapshot snapshot = snapshotRef.get();
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }

        // exact 匹配：按空白分词后逐词检查
        String[] tokens = normalizedText.split("\\s+");
        for (String token : tokens) {
            ModerationWord hit = snapshot.exactSet.get(token);
            if (hit != null) {
                return new MatchResult(hit, hit.getNormalized());
            }
        }
        // 整体 exact 匹配
        ModerationWord wholeHit = snapshot.exactSet.get(normalizedText);
        if (wholeHit != null) {
            return new MatchResult(wholeHit, wholeHit.getNormalized());
        }

        // contains 匹配：子串扫描
        for (ModerationWord word : snapshot.containsList) {
            String needle = word.getNormalized();
            if (normalizedText.contains(needle)) {
                return new MatchResult(word, needle);
            }
        }

        return null;
    }

    private MatcherSnapshot buildSnapshot(List<ModerationWord> words) {
        Map<String, ModerationWord> exactSet = new HashMap<>();
        List<ModerationWord> containsList = new ArrayList<>();

        if (words != null) {
            for (ModerationWord word : words) {
                if (word.getEnabled() == null || word.getEnabled() != 1) {
                    continue;
                }
                if ("exact".equalsIgnoreCase(word.getMatchMode())) {
                    exactSet.put(word.getNormalized(), word);
                } else {
                    containsList.add(word);
                }
            }
        }

        // contains 按词长度降序排列，优先匹配更长的词
        containsList.sort((a, b) -> {
            int lenA = a.getNormalized() != null ? a.getNormalized().length() : 0;
            int lenB = b.getNormalized() != null ? b.getNormalized().length() : 0;
            return Integer.compare(lenB, lenA);
        });

        return new MatcherSnapshot(exactSet, containsList);
    }

    @Data
    @AllArgsConstructor
    public static class MatcherSnapshot {
        private final Map<String, ModerationWord> exactSet;
        private final List<ModerationWord> containsList;

        public boolean isEmpty() {
            return exactSet.isEmpty() && containsList.isEmpty();
        }
    }

    @Data
    @AllArgsConstructor
    public static class MatchResult {
        private final ModerationWord word;
        private final String hitNormalized;
    }
}
