package com.tonyhe.fusion.service.generation.voiceover;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台词解析器（确定性，非 LLM）
 * <p>
 * 将分镜 StoryboardItem.dialogue 纯文本拆分为 AudioLine 列表。
 * 常见格式：
 * <pre>
 * 顾还生：急什么。收完地上的——下一个,轮到天。
 * 天音：找到了……天地的化身……
 * （旁白）远古传说……
 * </pre>
 */
@Component
@Slf4j
public class DialogueParser {

    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "^(?:[（(](旁白|画外音|叙述者|内心)[)）]\\s*[:：]|([^：:]+?)[:：])\\s*(.*)$");

    /**
     * 解析对白文本为 AudioLine 列表
     *
     * @param dialogue 原始对白文本（可多行）
     * @return AudioLine 列表，空文本返回空列表
     */
    public List<AudioLine> parse(String dialogue) {
        List<AudioLine> lines = new ArrayList<>();
        if (StrUtil.isBlank(dialogue)) {
            return lines;
        }

        String[] rawLines = dialogue.split("\n");
        String lastSpeaker = null;
        String lastDialogueType = "1"; // 默认对白
        int seq = 0;

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher matcher = SPEAKER_PATTERN.matcher(line);
            if (matcher.matches()) {
                String narratorKeyword = matcher.group(1);
                String speakerName = matcher.group(2);
                String text = matcher.group(3).trim();

                if (StrUtil.isNotBlank(narratorKeyword)) {
                    // 旁白/画外音
                    lastSpeaker = narratorKeyword;
                    lastDialogueType = "3";
                } else {
                    lastSpeaker = speakerName.trim();
                    lastDialogueType = "1";
                }

                if (StrUtil.isNotBlank(text)) {
                    lines.add(AudioLine.builder()
                            .seq(seq++)
                            .characterName(lastSpeaker)
                            .text(text)
                            .dialogueType(lastDialogueType)
                            .build());
                }
            } else {
                // 无前缀，继承上一句的角色
                if (StrUtil.isNotBlank(lastSpeaker) && StrUtil.isNotBlank(line)) {
                    lines.add(AudioLine.builder()
                            .seq(seq++)
                            .characterName(lastSpeaker)
                            .text(line)
                            .dialogueType(lastDialogueType)
                            .build());
                } else if (StrUtil.isNotBlank(line)) {
                    // 无前缀且无上一句角色，标记为旁白
                    lines.add(AudioLine.builder()
                            .seq(seq++)
                            .characterName("旁白")
                            .text(line)
                            .dialogueType("3")
                            .build());
                }
            }
        }

        //log.debug("[DialogueParser] 解析完成: {} 行 → {} 条 AudioLine", rawLines.length, lines.size());
        return lines;
    }

    /**
     * 解析后的单条对白
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class AudioLine {
        /** 该镜头内第几句（从0开始） */
        private int seq;
        /** 角色名（解析自对白前缀） */
        private String characterName;
        /** 台词文本 */
        private String text;
        /** 对白类型：1-对白 3-画外音 */
        private String dialogueType;
    }
}
