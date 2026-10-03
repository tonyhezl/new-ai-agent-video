package com.tonyhe.fusion.controller.ai;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageParam;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.entity.ai.AgentConversation;
import com.tonyhe.fusion.entity.ai.AgentMessage;
import com.tonyhe.fusion.mapper.ai.AgentMessageMapper;
import com.tonyhe.fusion.service.ai.AgentConversationService;
import com.tonyhe.fusion.service.ai.AgentMessageService;
import com.tonyhe.fusion.security.ResourceAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * AI 助手 Controller（对话管理 + 后续多轮 Chat）
 * <p>
 * Pipeline 相关接口已迁移至 {@link AiPipelineController}
 */
@Tag(name = "AI 助手")
@RestController
@RequestMapping("/api/ai/assistant")
@RequiredArgsConstructor
public class AiAssistantController {

    private final AgentConversationService conversationService;
    private final AgentMessageService messageService;
    private final AgentMessageMapper messageMapper;
    private final ResourceAccessService resourceAccessService;

    // ========== 对话管理 ==========

    @Operation(summary = "获取对话列表（当前用户）")
    @GetMapping("/conversations")
    public CommonResult<PageResult<AgentConversation>> listConversations(
            PageParam pageParam,
            @RequestParam(required = false) String category) {
        Long userId = requireCurrentUserId();
        PageResult<AgentConversation> result;
        if (category != null) {
            result = conversationService.listByUserAndCategory(userId, category,
                    pageParam.getPageNo(), pageParam.getPageSize());
        } else {
            result = conversationService.listByUser(userId,
                    pageParam.getPageNo(), pageParam.getPageSize());
        }
        return CommonResult.success(result);
    }

    @Operation(summary = "获取对话消息列表")
    @GetMapping("/conversations/{conversationId}/messages")
    public CommonResult<List<AgentMessage>> listMessages(@PathVariable String conversationId) {
        resourceAccessService.checkConversationAccess(conversationId, requireCurrentUserId());
        return CommonResult.success(messageService.listByConversation(conversationId));
    }

    @Operation(summary = "删除对话")
    @DeleteMapping("/conversations/{id}")
    public CommonResult<Boolean> deleteConversation(@PathVariable Long id) {
        resourceAccessService.checkConversationAccessById(id, requireCurrentUserId());
        conversationService.delete(id);
        return CommonResult.success(true);
    }

    // ========== Token 用量查询 ==========

    @Operation(summary = "获取对话 Token 用量（按 Agent 分组）")
    @GetMapping("/conversations/{conversationId}/token-usage")
    public CommonResult<List<Map<String, Object>>> getTokenUsage(@PathVariable String conversationId) {
        resourceAccessService.checkConversationAccess(conversationId, requireCurrentUserId());
        return CommonResult.success(messageMapper.selectTokenUsageByConversation(conversationId));
    }

    // ========== 全局 Token 用量统计 ==========

    @Operation(summary = "获取当前用户 Token 用量总览")
    @GetMapping("/token-usage/summary")
    public CommonResult<Map<String, Object>> getTokenUsageSummary() {
        Long userId = requireCurrentUserId();
        return CommonResult.success(messageMapper.selectTokenUsageSummary(userId));
    }

    @Operation(summary = "获取当前用户按模型分组的 Token 用量")
    @GetMapping("/token-usage/by-model")
    public CommonResult<List<Map<String, Object>>> getTokenUsageByModel() {
        Long userId = requireCurrentUserId();
        return CommonResult.success(messageMapper.selectTokenUsageByModel(userId));
    }

    @Operation(summary = "获取当前用户 Token 用量 Top 对话")
    @GetMapping("/token-usage/top-conversations")
    public CommonResult<List<Map<String, Object>>> getTokenUsageTopConversations(
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = requireCurrentUserId();
        return CommonResult.success(messageMapper.selectTokenUsageByConversationTop(userId, Math.min(limit, 100)));
    }

    @Operation(summary = "获取当前用户每日 Token 用量趋势")
    @GetMapping("/token-usage/daily-trend")
    public CommonResult<List<Map<String, Object>>> getTokenUsageDailyTrend(
            @RequestParam(defaultValue = "30") int days) {
        Long userId = requireCurrentUserId();
        LocalDateTime startDate = LocalDateTime.now().minusDays(Math.min(days, 365));
        return CommonResult.success(messageMapper.selectTokenUsageDailyTrend(userId, startDate));
    }
}

