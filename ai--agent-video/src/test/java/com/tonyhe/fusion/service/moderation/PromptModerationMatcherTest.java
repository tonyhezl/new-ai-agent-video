package com.tonyhe.fusion.service.moderation;

import com.tonyhe.fusion.entity.workbench.ModerationWord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PromptModerationMatcher 匹配器测试")
class PromptModerationMatcherTest {

    private PromptModerationMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new PromptModerationMatcher();
        List<ModerationWord> words = List.of(
                ModerationWord.builder()
                        .id(1L).word("testblockword").normalized("testblockword")
                        .category("other").matchMode("exact").severity(2).enabled(1)
                        .build(),
                ModerationWord.builder()
                        .id(2L).word("testcontains").normalized("testcontains")
                        .category("other").matchMode("contains").severity(2).enabled(1)
                        .build(),
                ModerationWord.builder()
                        .id(3L).word("warningword").normalized("warningword")
                        .category("other").matchMode("contains").severity(1).enabled(1)
                        .build(),
                ModerationWord.builder()
                        .id(4L).word("disabledword").normalized("disabledword")
                        .category("other").matchMode("contains").severity(2).enabled(0)
                        .build()
        );
        matcher.load(words);
    }

    @Test
    @DisplayName("exact 匹配：整词命中")
    void match_exactHit() {
        PromptModerationMatcher.MatchResult result = matcher.match("testblockword");
        assertNotNull(result);
        assertEquals("testblockword", result.getHitNormalized());
        assertEquals(1L, result.getWord().getId());
    }

    @Test
    @DisplayName("exact 不匹配子串")
    void match_exactNoSubstring() {
        PromptModerationMatcher.MatchResult result = matcher.match("xxtestblockwordxx");
        // exact 不应该命中子串，但 contains "testcontains" 也不在 "xxtestblockwordxx" 中
        // 所以这里应该是 null
        assertNull(result);
    }

    @Test
    @DisplayName("contains 匹配：子串命中")
    void match_containsHit() {
        PromptModerationMatcher.MatchResult result = matcher.match("xxtestcontainsxx");
        assertNotNull(result);
        assertEquals("testcontains", result.getHitNormalized());
        assertEquals(2L, result.getWord().getId());
    }

    @Test
    @DisplayName("未命中返回 null")
    void match_noHit() {
        assertNull(matcher.match("helloworld"));
    }

    @Test
    @DisplayName("停用词不参与匹配")
    void match_disabledWordNotMatched() {
        assertNull(matcher.match("disabledword"));
    }

    @Test
    @DisplayName("空快照不匹配")
    void match_emptySnapshot() {
        PromptModerationMatcher emptyMatcher = new PromptModerationMatcher();
        emptyMatcher.load(List.of());
        assertNull(emptyMatcher.match("anything"));
    }

    @Test
    @DisplayName("reload 后新词生效")
    void match_reloadNewWord() {
        assertNull(matcher.match("newword"));
        ModerationWord newWord = ModerationWord.builder()
                .id(5L).word("newword").normalized("newword")
                .category("other").matchMode("contains").severity(2).enabled(1)
                .build();
        matcher.reload(List.of(newWord));
        PromptModerationMatcher.MatchResult result = matcher.match("xxnewwordxx");
        assertNotNull(result);
        assertEquals("newword", result.getHitNormalized());
    }

    @Test
    @DisplayName("contains 优先匹配更长的词")
    void match_containsLongerFirst() {
        List<ModerationWord> words = List.of(
                ModerationWord.builder()
                        .id(10L).word("ab").normalized("ab")
                        .category("other").matchMode("contains").severity(2).enabled(1)
                        .build(),
                ModerationWord.builder()
                        .id(11L).word("abc").normalized("abc")
                        .category("other").matchMode("contains").severity(2).enabled(1)
                        .build()
        );
        matcher.load(words);
        PromptModerationMatcher.MatchResult result = matcher.match("xxabcxx");
        assertNotNull(result);
        assertEquals("abc", result.getHitNormalized());
    }
}
