package com.tonyhe.fusion.service.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PromptNormalizer 归一化测试")
class PromptNormalizerTest {

    @Test
    @DisplayName("null → 空串")
    void normalize_null_returnsEmpty() {
        assertEquals("", PromptNormalizer.normalize(null));
    }

    @Test
    @DisplayName("纯空白 → 空串")
    void normalize_blank_returnsEmpty() {
        assertEquals("", PromptNormalizer.normalize("   "));
        assertEquals("", PromptNormalizer.normalize("\t\n\r"));
    }

    @Test
    @DisplayName("大小写统一：大写转小写")
    void normalize_toLowerCase() {
        assertEquals("helloworld", PromptNormalizer.normalize("HelloWorld"));
        assertEquals("helloworld", PromptNormalizer.normalize("HELLOWORLD"));
    }

    @Test
    @DisplayName("全角转半角")
    void normalize_fullwidth_to_halfwidth() {
        // 123 after homophone: 1→i, 2 stays, 3→e
        assertEquals("helloi2e", PromptNormalizer.normalize("ｈｅｌｌｏ１２３"));
        assertEquals("test!", PromptNormalizer.normalize("ｔｅｓｔ！"));
    }

    @Test
    @DisplayName("中间插空格/星号/下划线/连字符被去除")
    void normalize_stripSeparators() {
        assertEquals("helloworld", PromptNormalizer.normalize("hello world"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello*world"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello_world"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello-world"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello·world"));
    }

    @Test
    @DisplayName("零宽字符被去除")
    void normalize_stripZeroWidth() {
        assertEquals("helloworld", PromptNormalizer.normalize("hello\u200bworld"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello\u200cworld"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello\u200dworld"));
        assertEquals("helloworld", PromptNormalizer.normalize("hello\ufeffworld"));
    }

    @Test
    @DisplayName("谐音替换：数字→字母")
    void normalize_homophoneReplacement() {
        assertEquals("loot", PromptNormalizer.normalize("l00t"));
        assertEquals("iest", PromptNormalizer.normalize("1est"));
        assertEquals("eest", PromptNormalizer.normalize("3est"));
    }

    @Test
    @DisplayName("综合：全角+空格+大写+谐音")
    void normalize_combined() {
        String input = "Ｔｅｓｔ Ｂｌｏｃｋ Ｗｏｒｄ １２３";
        String result = PromptNormalizer.normalize(input);
        // 123 → i2e after homophone replacement
        assertEquals("testblockwordi2e", result);
    }

    @Test
    @DisplayName("SHA-256 哈希一致性")
    void sha256_consistent() {
        String normalized = "helloworld";
        String hash1 = PromptNormalizer.sha256(normalized);
        String hash2 = PromptNormalizer.sha256(normalized);
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("SHA-256 不同输入产生不同哈希")
    void sha256_different() {
        String hash1 = PromptNormalizer.sha256("hello");
        String hash2 = PromptNormalizer.sha256("world");
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("超长文本不被截断（归一化不负责长度校验）")
    void normalize_longText() {
        String longText = "a".repeat(3000);
        String result = PromptNormalizer.normalize(longText);
        assertEquals(3000, result.length());
    }
}
