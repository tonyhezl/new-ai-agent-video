package com.tonyhe.fusion.controller.moderation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.entity.workbench.ModerationHitLog;
import com.tonyhe.fusion.entity.workbench.ModerationWord;
import com.tonyhe.fusion.mapper.workbench.ModerationHitLogMapper;
import com.tonyhe.fusion.mapper.workbench.ModerationWordMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.moderation.PromptModerationService;
import com.tonyhe.fusion.service.moderation.PromptNormalizer;
import com.tonyhe.fusion.service.system.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "审核词库管理")
@RestController
@RequestMapping("/api/admin/moderation-words")
@RequiredArgsConstructor
@Slf4j
public class ModerationWordAdminController {

    private final ModerationWordMapper moderationWordMapper;
    private final ModerationHitLogMapper moderationHitLogMapper;
    private final PromptModerationService promptModerationService;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "词库列表（分页+筛选）")
    public CommonResult<PageResult<ModerationWord>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireAdmin();
        LambdaQueryWrapper<ModerationWord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(category != null, ModerationWord::getCategory, category)
                .eq(enabled != null, ModerationWord::getEnabled, enabled)
                .like(keyword != null, ModerationWord::getWord, keyword)
                .orderByDesc(ModerationWord::getId);
        return success(PageResult.of(moderationWordMapper.selectPage(new Page<>(pageNo, pageSize), wrapper)));
    }

    @PostMapping
    @Operation(summary = "新增审核词")
    public CommonResult<ModerationWord> create(@Valid @RequestBody ModerationWordReqVO reqVO) {
        requireAdmin();
        String normalized = PromptNormalizer.normalize(reqVO.getWord());
        ModerationWord word = ModerationWord.builder()
                .word(reqVO.getWord().trim().toLowerCase())
                .normalized(normalized)
                .category(reqVO.getCategory())
                .matchMode(reqVO.getMatchMode() != null ? reqVO.getMatchMode() : "contains")
                .severity(reqVO.getSeverity() != null ? reqVO.getSeverity() : 2)
                .enabled(1)
                .remark(reqVO.getRemark())
                .deletedId(0L)
                .build();
        moderationWordMapper.insert(word);
        promptModerationService.reload();
        return success(word);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改审核词")
    public CommonResult<ModerationWord> update(@PathVariable Long id, @Valid @RequestBody ModerationWordReqVO reqVO) {
        requireAdmin();
        ModerationWord word = moderationWordMapper.selectById(id);
        if (word == null) {
            return CommonResult.error(404, "审核词不存在");
        }
        word.setWord(reqVO.getWord().trim().toLowerCase());
        word.setNormalized(PromptNormalizer.normalize(reqVO.getWord()));
        if (reqVO.getCategory() != null) word.setCategory(reqVO.getCategory());
        if (reqVO.getMatchMode() != null) word.setMatchMode(reqVO.getMatchMode());
        if (reqVO.getSeverity() != null) word.setSeverity(reqVO.getSeverity());
        if (reqVO.getRemark() != null) word.setRemark(reqVO.getRemark());
        moderationWordMapper.updateById(word);
        promptModerationService.reload();
        return success(word);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用审核词")
    public CommonResult<Boolean> enable(@PathVariable Long id) {
        requireAdmin();
        ModerationWord word = moderationWordMapper.selectById(id);
        if (word == null) return CommonResult.error(404, "审核词不存在");
        word.setEnabled(1);
        moderationWordMapper.updateById(word);
        promptModerationService.reload();
        return success(true);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用审核词")
    public CommonResult<Boolean> disable(@PathVariable Long id) {
        requireAdmin();
        ModerationWord word = moderationWordMapper.selectById(id);
        if (word == null) return CommonResult.error(404, "审核词不存在");
        word.setEnabled(0);
        moderationWordMapper.updateById(word);
        promptModerationService.reload();
        return success(true);
    }

    @PostMapping("/reload")
    @Operation(summary = "手动刷新词表缓存")
    public CommonResult<Boolean> reload() {
        requireAdmin();
        promptModerationService.reload();
        return success(true);
    }

    @GetMapping("/hits")
    @Operation(summary = "命中日志查询")
    public CommonResult<PageResult<ModerationHitLog>> hits(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireAdmin();
        LambdaQueryWrapper<ModerationHitLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(category != null, ModerationHitLog::getHitCategory, category)
                .orderByDesc(ModerationHitLog::getId);
        return success(PageResult.of(moderationHitLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper)));
    }

    private void requireAdmin() {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userService.isAdmin(userId)) {
            throw new com.tonyhe.fusion.common.BusinessException(403, "仅管理员可操作");
        }
    }

    @Data
    public static class ModerationWordReqVO {
        @NotBlank(message = "审核词不能为空")
        private String word;
        private String category;
        private String matchMode;
        private Integer severity;
        private String remark;
    }
}
