package com.tonyhe.fusion.controller.media;

import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.service.media.MediaLibraryService;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.security.AuthorizationService;
import com.tonyhe.fusion.security.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 媒体库 Controller
 * <p>
 * 提供全局媒体浏览与批量清除媒体 URL 的接口。
 */
@Tag(name = "媒体库")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaLibraryController {

    private final MediaLibraryService mediaLibraryService;
    private final AuthorizationService authorizationService;

    @Operation(summary = "查询媒体列表")
    @GetMapping("/list")
    public CommonResult<Map<String, Object>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String mediaType,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "60") int size) {
        Long userId = requireCurrentUserId();
        authorizationService.requirePermission(userId, PermissionCodes.MEDIA_READ);
        return CommonResult.success(mediaLibraryService.listMedia(projectId, mediaType, source, page, size, userId));
    }

    @Operation(summary = "批量清除媒体 URL")
    @PostMapping("/batch-clear")
    public CommonResult<Integer> batchClear(@RequestBody List<Map<String, Object>> entries) {
        Long userId = requireCurrentUserId();
        authorizationService.requirePermission(userId, PermissionCodes.MEDIA_DELETE);
        int cleared = mediaLibraryService.batchClearMedia(entries, userId);
        return CommonResult.success(cleared);
    }
}
