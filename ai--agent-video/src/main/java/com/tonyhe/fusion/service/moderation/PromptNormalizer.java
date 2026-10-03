package com.tonyhe.fusion.service.moderation;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词归一化纯函数。
 * <p>
 * 按方案 5.1.1 定义的步骤顺序处理：
 * 1. null → 空串；trim
 * 2. 全角转半角
 * 3. Unicode NFKC
 * 4. 小写
 * 5. 去掉空白、零宽字符、下划线、连字符、星号、中点
 * 6. 简繁转换（常见繁体映射表）
 * 7. 谐音/形近替换
 */
public final class PromptNormalizer {

    private PromptNormalizer() {
    }

    private static final char[] ZERO_WIDTH_CHARS = {
            '\u200b', '\u200c', '\u200d', '\ufeff'
    };

    private static final char[] STRIP_CHARS = {
            ' ', '\t', '\n', '\r', '_', '-', '*', '·', '\u200b', '\u200c', '\u200d', '\ufeff'
    };

    private static final Map<Character, Character> FULLWIDTH_MAP = new HashMap<>();
    private static final Map<Character, Character> TRADITIONAL_MAP = new HashMap<>();
    private static final Map<Character, Character> HOMOPHONE_MAP = new HashMap<>();

    static {
        // 全角→半角：！-～ → !-~
        for (char c = '\uFF01'; c <= '\uFF5E'; c++) {
            FULLWIDTH_MAP.put(c, (char) (c - 0xFEE0));
        }
        FULLWIDTH_MAP.put('\u3000', ' '); // 全角空格

        // 常见繁体→简体映射（只覆盖高频字，不追求全覆盖）
        String[] tsPairs = {
                "東东", "西西", "南南", "北北", "門门", "開开", "關关", "時时", "國国",
                "學学", "經经", "動动", "電电", "話话", "說说", "請请", "謝谢",
                "對对", "應应", "體体", "會会", "發发", "產产", "從从", "們们",
                "這这", "個个", "來来", "現现", "裡里", "為為", "為为", "雲云",
                "飛飞", "風风", "龍龙", "鳳凤", "鳥鸟", "馬马", "魚鱼", "車车",
                "書书", "畫画", "長长", "門门", "見见", "貝贝", "頁页", "詞词",
                "語语", "論论", "講讲", "識识", "藝艺", "術术", "華华", "聯联",
                "網网", "線线", "點点", "號号", "電电", "視视", "節节", "目目",
                "區区", "員员", "類类", "級级", "紀纪", "約约", "統统", "計计",
                "設设", "製制", "備备", "複复", "係系", "關关", "調调", "查查",
                "證证", "據据", "結结", "果果", "構构", "機机", "械械", "檢检",
                "驗验", "營营", "運运", "維维", "護护", "修修", "建建", "設设",
                "備备", "創创", "建建", "製制", "作作", "生生", "產产", "品品",
                "務务", "服服", "務务", "業业", "務务", "項项", "目目", "錄录",
                "編编", "號号", "碼码", "類类", "型型", "規规", "格格", "式式",
                "範范", "圍围", "內内", "容容", "功功", "能能", "特特", "性性",
                "質质", "量量", "標标", "準准", "確确", "定定", "義义", "務务"
        };
        for (String pair : tsPairs) {
            if (pair.length() == 2) {
                TRADITIONAL_MAP.put(pair.charAt(0), pair.charAt(1));
            }
        }

        // 谐音/形近替换
        HOMOPHONE_MAP.put('0', 'o');
        HOMOPHONE_MAP.put('1', 'i');
        HOMOPHONE_MAP.put('3', 'e');
        HOMOPHONE_MAP.put('4', 'a');
        HOMOPHONE_MAP.put('5', 's');
        HOMOPHONE_MAP.put('7', 't');
    }

    /**
     * 归一化提示词文本。
     *
     * @param input 原始提示词，可为 null
     * @return 归一化后的字符串（不含空白、全半角统一、简体、谐音替换）
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }

        // Step 1: trim
        String s = input.trim();

        // Step 2: 全角转半角
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character half = FULLWIDTH_MAP.get(c);
            sb.append(half != null ? half : c);
        }
        s = sb.toString();

        // Step 3: Unicode NFKC
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);

        // Step 4: 小写
        s = s.toLowerCase();

        // Step 5: 去掉空白、零宽字符、下划线、连字符、星号、中点
        sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isStripChar(c)) {
                sb.append(c);
            }
        }
        s = sb.toString();

        // Step 6: 简繁转换
        sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character simplified = TRADITIONAL_MAP.get(c);
            sb.append(simplified != null ? simplified : c);
        }
        s = sb.toString();

        // Step 7: 谐音/形近替换
        sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character replaced = HOMOPHONE_MAP.get(c);
            sb.append(replaced != null ? replaced : c);
        }

        return sb.toString();
    }

    private static boolean isStripChar(char c) {
        for (char sc : STRIP_CHARS) {
            if (c == sc) return true;
        }
        return false;
    }

    /**
     * 计算归一化后文本的 SHA-256 哈希（用于 hit_log，不存原文）。
     */
    public static String sha256(String normalized) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
