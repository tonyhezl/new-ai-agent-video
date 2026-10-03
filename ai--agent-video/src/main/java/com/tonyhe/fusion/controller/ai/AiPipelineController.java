package com.tonyhe.fusion.controller.ai;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.ai.vo.AiChatReqVO;
import com.tonyhe.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.tonyhe.fusion.entity.ai.AgentConversation;
import com.tonyhe.fusion.service.ai.AgentConversationService;
import com.tonyhe.fusion.service.ai.agentscope.AgentScopeAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * AI Pipeline Controller（单次自动执行的工作流）
 * <p>
 * 与 AiAssistantController（多轮对话）分离，
 * 便于后续独立调整 pipeline 的参数、限流、鉴权等逻辑。
 */
@Tag(name = "AI Pipeline")
@RestController
@RequestMapping("/api/ai/pipeline")
@RequiredArgsConstructor
public class AiPipelineController {

    private final AgentScopeAssistantService aiAssistantService;
    private final AgentConversationService conversationService;

    @Operation(summary = "启动 Pipeline（SSE 流式）")
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> run(@RequestBody AiChatReqVO reqVO) {
        Long userId = requireCurrentUserId();
        return aiAssistantService.stream(reqVO, userId);
    }

    @Operation(summary = "取消 Pipeline")
    @PostMapping("/cancel")
    public CommonResult<Boolean> cancel(@RequestParam String conversationId) {
        aiAssistantService.cancelStream(conversationId);
        return CommonResult.success(true);
    }

    @Operation(summary = "重连 Pipeline（页面刷新后恢复 SSE）")
    @GetMapping(value = "/reconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> reconnect(@RequestParam String conversationId) {
        return aiAssistantService.reconnectStream(conversationId);
    }

    @Operation(summary = "查询 Pipeline 流状态")
    @GetMapping("/status")
    public CommonResult<String> getStatus(@RequestParam String conversationId) {
        return CommonResult.success(aiAssistantService.getStreamStatus(conversationId));
    }

    @Operation(summary = "查询运行中的 Pipeline 列表")
    @GetMapping("/running")
    public CommonResult<List<AgentConversation>> listRunning() {
        Long userId = requireCurrentUserId();
        return CommonResult.success(conversationService.listRunning(userId));
    }
}
