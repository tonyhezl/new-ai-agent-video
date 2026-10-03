package com.tonyhe.fusion.service.moderation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.workbench.ModerationHitLog;
import com.tonyhe.fusion.entity.workbench.ModerationWord;
import com.tonyhe.fusion.mapper.workbench.ModerationHitLogMapper;
import com.tonyhe.fusion.mapper.workbench.ModerationWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词审核服务，对外唯一入口。
 * <p>
 * 流程：
 * 1. 原文 length > 2000 → 400 PROMPT_TOO_LONG
 * 2. normalized = PromptNormalizer.normalize(prompt)
 * 3. 空串 → 400 PROMPT_EMPTY
 * 4. 本地词表匹配（P0）
 * 5. 命中 severity>=2 → 异步写 hit_log，返回 blocked
 * 6. 本地通过 → 云审核（P1，可选）
 * 7. 最终 pass / blocked
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptModerationService {

    private static final int MAX_PROMPT_LENGTH = 2000;

    private final ModerationWordMapper moderationWordMapper;
    private final ModerationHitLogMapper moderationHitLogMapper;
    private final PromptModerationMatcher matcher;
    private final CloudModerationService cloudModerationService;

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载词表。
     */
    public void reload() {
        List<ModerationWord> words = moderationWordMapper.selectList(
                new LambdaQueryWrapper<ModerationWord>()
                        .eq(ModerationWord::getEnabled, 1));
        matcher.load(words);
        log.info("PromptModerationService reloaded {} enabled words", words.size());
    }

    /**
     * 检查提示词是否合规。
     *
     * @param prompt  原始提示词
     * @param userId  登录用户 ID（可为 null）
     * @param guestId 游客 ID（可为 null）
     * @param ip      客户端 IP（可为 null）
     * @return 审核结果
     */
    public ModerationResult check(String prompt, Long userId, String guestId, String ip) {
        // Step 1: 长度校验
        if (prompt != null && prompt.length() > MAX_PROMPT_LENGTH) {
            return ModerationResult.error("PROMPT_TOO_LONG", "提示词过长，请精简后重试");
        }

        // Step 2: 归一化
        String normalized = PromptNormalizer.normalize(prompt);

        // Step 3: 空串校验
        if (normalized.isEmpty()) {
            return ModerationResult.error("PROMPT_EMPTY", "提示词不能为空");
        }

        // Step 4-5: 匹配
        PromptModerationMatcher.MatchResult matchResult = matcher.match(normalized);
        if (matchResult != null) {
            ModerationWord word = matchResult.getWord();
            int severity = word.getSeverity() != null ? word.getSeverity() : 2;

            if (severity >= 2) {
                // 异步写 hit_log
                writeHitLog(userId, guestId, normalized, prompt != null ? prompt.length() : 0,
                        word, matchResult.getHitNormalized(), ip);

                log.debug("Prompt blocked: category={}, severity={}, hitWord={}",
                        word.getCategory(), severity, word.getNormalized());

                return ModerationResult.blocked(word.getCategory());
            } else {
                // severity=1 仅打日志，不拦截
                log.info("Prompt warning (severity=1): category={}, hitWord={}",
                        word.getCategory(), word.getNormalized());
            }
        }

        // Step 6: 通过 → 云审核（P1）
        CloudModerationService.CloudModerationResult cloudResult = cloudModerationService.checkText(prompt);
        if (!cloudResult.isPassed()) {
            // 云审核拦截也写 hit_log
            writeHitLog(userId, guestId, normalized, prompt != null ? prompt.length() : 0,
                    null, cloudResult.getCategory() + ": " + cloudResult.getDetail(), ip);
            return ModerationResult.blocked(cloudResult.getCategory());
        }

        // Step 7: 最终通过
        return ModerationResult.pass();
    }

    @Async
    public void writeHitLog(Long userId, String guestId, String normalized, int promptLength,
                            ModerationWord word, String hitNormalized, String ip) {
        try {
            String promptHash = PromptNormalizer.sha256(normalized);
            ModerationHitLog hitLog = ModerationHitLog.builder()
                    .userId(userId)
                    .guestId(guestId)
                    .promptHash(promptHash)
                    .promptLength(promptLength)
                    .hitWordId(word != null ? word.getId() : null)
                    .hitCategory(word != null ? word.getCategory() : "cloud")
                    .hitNormalized(hitNormalized != null ? hitNormalized : "cloud_block")
                    .ip(ip)
                    .build();
            moderationHitLogMapper.insert(hitLog);
        } catch (Exception e) {
            log.error("Failed to write moderation hit log", e);
        }
    }

    /**
     * 审核结果。
     */
    @Data
    @AllArgsConstructor
    public static class ModerationResult {
        private boolean passed;
        private boolean blocked;
        private String errorCode;
        private String errorMessage;
        private String category;

        public static ModerationResult pass() {
            return new ModerationResult(true, false, null, null, null);
        }

        public static ModerationResult blocked(String category) {
            return new ModerationResult(false, true, "PROMPT_BLOCKED",
                    "提示词包含违规内容，请修改后再生成", category);
        }

        public static ModerationResult error(String code, String message) {
            return new ModerationResult(false, false, code, message, null);
        }
    }
}
