package com.tonyhe.fusion.controller.storage;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.service.storage.MediaStorageService;
import com.tonyhe.fusion.security.MediaAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

import static com.tonyhe.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 通用文件上传 Controller
 */
@Tag(name = "文件上传")
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Slf4j
public class FileUploadController {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );

    private final MediaStorageService mediaStorageService;
    private final MediaAccessService mediaAccessService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件")
    public CommonResult<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subDir", defaultValue = "uploads") String subDir) {

        if (file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 100MB");
        }
        if (subDir == null || !subDir.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9_-]{0,63})*")) {
            throw new BusinessException("上传目录格式不合法");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException("仅支持图片格式：PNG, JPEG, WebP, GIF");
        }

        try {
            String ext = getExtension(file.getOriginalFilename());
            String url = mediaStorageService.storeBytes(file.getBytes(), subDir, ext);
            mediaAccessService.registerTemporaryUpload(url, requireCurrentUserId());
            log.info("[FileUpload] 上传成功: size={}KB, url={}", file.getSize() / 1024, url);
            return CommonResult.success(url);
        } catch (IOException e) {
            log.error("[FileUpload] 上传失败", e);
            throw new BusinessException("上传失败: " + e.getMessage());
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "png";
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(dotIndex + 1) : "png";
    }
}
