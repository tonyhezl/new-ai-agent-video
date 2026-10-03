package com.tonyhe.fusion.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 权限变更审计实体
 * <p>
 * 对应数据库表：sys_permission_audit
 */
@TableName("sys_permission_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户ID */
    private Long operatorId;

    /** 被操作用户ID */
    private Long targetUserId;

    /** 操作类型：ASSIGN / REVOKE / DISABLE / ENABLE */
    private String action;

    /** 涉及角色ID */
    private Long roleId;

    /** 作用域类型 */
    private String scopeType;

    /** 作用域ID */
    private Long scopeId;

    /** 操作详情JSON */
    private String detail;

    /** 创建时间 */
    private LocalDateTime createTime;
}
