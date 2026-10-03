package com.tonyhe.fusion.service.system;

import com.tonyhe.fusion.entity.system.PermissionAudit;
import com.tonyhe.fusion.mapper.system.PermissionAuditMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 权限变更审计服务
 * <p>
 * 记录角色分配、撤销、禁用等权限变更操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditService {

    private final PermissionAuditMapper permissionAuditMapper;

    /**
     * 记录审计日志
     */
    public void record(Long operatorId, Long targetUserId, String action,
                       Long roleId, String scopeType, Long scopeId, String detail) {
        PermissionAudit audit = PermissionAudit.builder()
                .operatorId(operatorId)
                .targetUserId(targetUserId)
                .action(action)
                .roleId(roleId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .detail(detail)
                .build();
        permissionAuditMapper.insert(audit);
        log.info("[PermissionAudit] operator={}, target={}, action={}, role={}, scope={}:{}",
                operatorId, targetUserId, action, roleId, scopeType, scopeId);
    }

    /**
     * 分页查询审计日志
     */
    public Page<PermissionAudit> page(int pageNo, int pageSize) {
        return permissionAuditMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<PermissionAudit>()
                        .orderByDesc(PermissionAudit::getCreateTime)
                        .orderByDesc(PermissionAudit::getId));
    }
}
