package com.tonyhe.fusion.controller.generation;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageParam;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.controller.generation.vo.ImageTaskSubmitReqVO;
import com.tonyhe.fusion.convert.generation.GenerationConvert;
import com.tonyhe.fusion.entity.generation.ImageItem;
import com.tonyhe.fusion.entity.generation.ImageTask;
import com.tonyhe.fusion.service.generation.ImageGenerationService;
import com.tonyhe.fusion.service.generation.consumer.ImageGenerationConsumer;
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
 * 生图 Controller
 */
@Tag(name = "图片生成")
@RestController
@RequestMapping("/api/generation/image")
@RequiredArgsConstructor
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final ResourceAccessService resourceAccessService;
    private final AuthorizationService authorizationService;
    private final TeamService teamService;

    @Operation(summary = "提交生图任务")
    @PostMapping("/submit")
    public CommonResult<String> submit(@Valid @RequestBody ImageTaskSubmitReqVO reqVO) {
        ImageTask task = GenerationConvert.INSTANCE.convert(reqVO);
        Long userId = requireCurrentUserId();
        authorizationService.requirePermission(userId, PermissionCodes.GENERATION_CREATE);
        if (task.getProjectId() != null) {
            resourceAccessService.checkProjectContentAccess(task.getProjectId(), userId,
                    AccessAction.EDIT, PermissionCodes.GENERATION_CREATE);
        }
        task.setUserId(userId);
        task.setTeamId(teamService.getCurrentTeamIdByUser(userId));
        String taskId = imageGenerationConsumer.submitTask(task);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "查询生图任务")
    @GetMapping("/{taskId}")
    public CommonResult<ImageTask> get(@PathVariable String taskId) {
        resourceAccessService.checkImageTaskAccess(taskId, requireCurrentUserId());
        return CommonResult.success(imageGenerationService.getByTaskId(taskId));
    }

    @Operation(summary = "查询生图任务的图片条目")
    @GetMapping("/{id}/items")
    public CommonResult<List<ImageItem>> listItems(@PathVariable Long id) {
        resourceAccessService.checkImageTaskAccessById(id, requireCurrentUserId());
        return CommonResult.success(imageGenerationService.listItems(id));
    }

    @Operation(summary = "分页查询当前用户的生图任务")
    @GetMapping("/page")
    public CommonResult<PageResult<ImageTask>> page(PageParam pageParam) {
        Long userId = requireCurrentUserId();
        return CommonResult.success(imageGenerationService.pageByUser(userId,
                pageParam.getPageNo(), pageParam.getPageSize()));
    }
}
