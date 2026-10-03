package com.tonyhe.fusion.controller.generation;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageParam;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.controller.generation.vo.VideoTaskSubmitReqVO;
import com.tonyhe.fusion.convert.generation.GenerationConvert;
import com.tonyhe.fusion.entity.generation.VideoItem;
import com.tonyhe.fusion.entity.generation.VideoTask;
import com.tonyhe.fusion.service.generation.VideoGenerationService;
import com.tonyhe.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.security.AccessAction;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import com.tonyhe.fusion.service.team.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 生视频 Controller
 */
@Tag(name = "视频生成")
@RestController
@RequestMapping("/api/generation/video")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;
    private final TeamService teamService;

    @Operation(summary = "提交生视频任务")
    @PostMapping("/submit")
    public CommonResult<String> submit(@Valid @RequestBody VideoTaskSubmitReqVO reqVO) {
        VideoTask task = GenerationConvert.INSTANCE.convert(reqVO);
        Long userId = requireCurrentUserId();
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        if (task.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(task.getProjectId(), userId,
                    AccessAction.EDIT, PermissionCodes.GENERATION_CREATE);
        }
        task.setUserId(userId);
        task.setTeamId(teamService.getCurrentTeamIdByUser(userId));
        String taskId = videoGenerationConsumer.submitTask(task);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "查询生视频任务")
    @GetMapping("/{taskId}")
    public CommonResult<VideoTask> get(@PathVariable String taskId) {
        resourceAccessService.checkVideoTaskAccess(taskId, requireCurrentUserId());
        return CommonResult.success(videoGenerationService.getByTaskId(taskId));
    }

    @Operation(summary = "查询生视频任务的视频条目")
    @GetMapping("/{id}/items")
    public CommonResult<List<VideoItem>> listItems(@PathVariable Long id) {
        resourceAccessService.checkVideoTaskAccessById(id, requireCurrentUserId());
        return CommonResult.success(videoGenerationService.listItems(id));
    }

    @Operation(summary = "分页查询当前用户的生视频任务")
    @GetMapping("/page")
    public CommonResult<PageResult<VideoTask>> page(PageParam pageParam) {
        Long userId = requireCurrentUserId();
        return CommonResult.success(videoGenerationService.pageByUser(userId,
                pageParam.getPageNo(), pageParam.getPageSize()));
    }
}
