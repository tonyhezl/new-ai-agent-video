package com.tonyhe.fusion.service.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.generation.CapabilityRule;
import com.tonyhe.fusion.mapper.generation.CapabilityRuleMapper;
import com.tonyhe.fusion.service.ai.model.AiModelMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 能力规则服务。
 * <p>
 * 负责规则的 CRUD 以及根据模型元数据匹配规则、解析能力。
 * 规则按 sortOrder 升序排列，首个命中即返回。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapabilityRuleService {

    private final CapabilityRuleMapper capabilityRuleMapper;

    // ========================= CRUD =========================

    public List<CapabilityRule> listAll() {
        return capabilityRuleMapper.selectList(
                new LambdaQueryWrapper<CapabilityRule>()
                        .eq(CapabilityRule::getStatus, 1)
                        .orderByAsc(CapabilityRule::getModelType)
                        .orderByAsc(CapabilityRule::getSortOrder)
                        .orderByAsc(CapabilityRule::getId));
    }

    public List<CapabilityRule> listByType(Integer modelType) {
        return capabilityRuleMapper.selectList(
                new LambdaQueryWrapper<CapabilityRule>()
                        .eq(CapabilityRule::getModelType, modelType)
                        .orderByAsc(CapabilityRule::getSortOrder)
                        .orderByAsc(CapabilityRule::getId));
    }

    public CapabilityRule getById(Long id) {
        return capabilityRuleMapper.selectById(id);
    }

    @Transactional
    public Long create(CapabilityRule rule) {
        validate(rule);
        capabilityRuleMapper.insert(rule);
        return rule.getId();
    }

    @Transactional
    public void update(CapabilityRule rule) {
        if (rule.getId() == null) {
            throw new BusinessException(400, "规则 ID 不能为空");
        }
        CapabilityRule existing = capabilityRuleMapper.selectById(rule.getId());
        if (existing == null) {
            throw new BusinessException(404, "规则不存在");
        }
        validate(rule);
        capabilityRuleMapper.updateById(rule);
    }

    @Transactional
    public void delete(Long id) {
        capabilityRuleMapper.softDeleteById(id);
    }

    private void validate(CapabilityRule rule) {
        if (StrUtil.isBlank(rule.getName())) {
            throw new BusinessException(400, "规则名称不能为空");
        }
        if (rule.getModelType() == null || (rule.getModelType() != 2 && rule.getModelType() != 3)) {
            throw new BusinessException(400, "模型类型必须为 2(图片) 或 3(视频)");
        }
        if (StrUtil.isBlank(rule.getCapabilityConfig())) {
            throw new BusinessException(400, "能力配置不能为空");
        }
        try {
            JSONUtil.parseObj(rule.getCapabilityConfig());
        } catch (Exception e) {
            throw new BusinessException(400, "能力配置不是合法的 JSON");
        }
    }

    // ========================= 推断匹配 =========================

    /**
     * 根据模型元数据匹配视频能力规则。
     *
     * @param metadata 模型元数据
     * @param code     模型 code（小写）
     * @return 匹配到的能力 JSON，未匹配返回 null
     */
    public JSONObject matchVideoCapability(AiModelMetadata metadata, String code) {
        return matchRule(3, metadata, code);
    }

    /**
     * 根据模型元数据匹配图片能力规则。
     *
     * @param metadata 模型元数据
     * @param code     模型 code（小写）
     * @return 匹配到的能力 JSON，未匹配返回 null
     */
    public JSONObject matchImageCapability(AiModelMetadata metadata, String code) {
        return matchRule(2, metadata, code);
    }

    private JSONObject matchRule(int modelType, AiModelMetadata metadata, String code) {
        List<CapabilityRule> rules = listByType(modelType);
        String normalizedPlatform = metadata.normalizedPlatform();
        String family = StrUtil.blankToDefault(metadata.effectiveFamily(), "");
        String protocol = StrUtil.blankToDefault(metadata.effectiveProtocol(), "");
        String lowerCode = StrUtil.blankToDefault(code, "").toLowerCase(Locale.ROOT);

        for (CapabilityRule rule : rules) {
            if (rule.getStatus() != null && rule.getStatus() != 1) {
                continue;
            }
            if (matches(rule, normalizedPlatform, lowerCode, family, protocol)) {
                try {
                    return JSONUtil.parseObj(rule.getCapabilityConfig());
                } catch (Exception e) {
                    log.warn("能力规则 [{}] 的 capabilityConfig 解析失败: {}", rule.getId(), e.getMessage());
                }
            }
        }
        return null;
    }

    private boolean matches(CapabilityRule rule, String platform, String code, String family, String protocol) {
        if (StrUtil.isNotBlank(rule.getPlatform())
                && !rule.getPlatform().toLowerCase(Locale.ROOT).equals(platform)) {
            return false;
        }
        if (StrUtil.isNotBlank(rule.getCodeContains())
                && !code.contains(rule.getCodeContains().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (StrUtil.isNotBlank(rule.getCodeStartsWith())
                && !code.startsWith(rule.getCodeStartsWith().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (StrUtil.isNotBlank(rule.getFamily())
                && !rule.getFamily().toLowerCase(Locale.ROOT).equals(family)) {
            return false;
        }
        if (StrUtil.isNotBlank(rule.getProtocol())
                && !rule.getProtocol().toLowerCase(Locale.ROOT).equals(protocol)) {
            return false;
        }
        return true;
    }
}
