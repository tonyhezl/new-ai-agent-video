package com.tonyhe.fusion.controller.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.entity.workbench.ImageModelPrice;
import com.tonyhe.fusion.mapper.workbench.ImageModelPriceMapper;
import com.tonyhe.fusion.security.SecurityUtils;
import com.tonyhe.fusion.service.system.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "模型价格管理")
@RestController
@RequestMapping("/api/admin/image-model-prices")
@RequiredArgsConstructor
@Slf4j
public class ImageModelPriceAdminController {

    private final ImageModelPriceMapper imageModelPriceMapper;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "模型价格列表")
    public CommonResult<List<ImageModelPrice>> list(
            @RequestParam(required = false) Long modelId) {
        requireAdmin();
        LambdaQueryWrapper<ImageModelPrice> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(modelId != null, ImageModelPrice::getModelId, modelId)
                .orderByAsc(ImageModelPrice::getModelId)
                .orderByAsc(ImageModelPrice::getQualityLevel);
        return success(imageModelPriceMapper.selectList(wrapper));
    }

    @PostMapping
    @Operation(summary = "新增模型价格")
    public CommonResult<ImageModelPrice> create(@Valid @RequestBody PriceReqVO reqVO) {
        requireAdmin();
        ImageModelPrice price = ImageModelPrice.builder()
                .modelId(reqVO.getModelId())
                .qualityLevel(reqVO.getQualityLevel() != null ? reqVO.getQualityLevel() : "standard")
                .aspectRatio(reqVO.getAspectRatio() != null ? reqVO.getAspectRatio() : "1:1")
                .creditCost(reqVO.getCreditCost())
                .upstreamCostCny(reqVO.getUpstreamCostCny())
                .isDefault(reqVO.getIsDefault() != null && reqVO.getIsDefault() ? 1 : 0)
                .status(1)
                .deletedId(0L)
                .build();
        imageModelPriceMapper.insert(price);
        return success(price);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改模型价格")
    public CommonResult<ImageModelPrice> update(@PathVariable Long id, @Valid @RequestBody PriceReqVO reqVO) {
        requireAdmin();
        ImageModelPrice price = imageModelPriceMapper.selectById(id);
        if (price == null) {
            throw new BusinessException(404, "价格配置不存在");
        }
        if (reqVO.getModelId() != null) price.setModelId(reqVO.getModelId());
        if (reqVO.getQualityLevel() != null) price.setQualityLevel(reqVO.getQualityLevel());
        if (reqVO.getAspectRatio() != null) price.setAspectRatio(reqVO.getAspectRatio());
        if (reqVO.getCreditCost() != null) price.setCreditCost(reqVO.getCreditCost());
        if (reqVO.getUpstreamCostCny() != null) price.setUpstreamCostCny(reqVO.getUpstreamCostCny());
        if (reqVO.getIsDefault() != null) price.setIsDefault(reqVO.getIsDefault() ? 1 : 0);
        imageModelPriceMapper.updateById(price);
        return success(price);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型价格")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        requireAdmin();
        imageModelPriceMapper.deleteById(id);
        return success(true);
    }

    private void requireAdmin() {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userService.isAdmin(userId)) {
            throw new BusinessException(403, "仅管理员可操作");
        }
    }

    @Data
    public static class PriceReqVO {
        @NotNull(message = "模型 ID 不能为空")
        private Long modelId;
        private String qualityLevel;
        private String aspectRatio;
        @NotNull(message = "创作点价格不能为空")
        private Integer creditCost;
        private BigDecimal upstreamCostCny;
        private Boolean isDefault;
    }
}
