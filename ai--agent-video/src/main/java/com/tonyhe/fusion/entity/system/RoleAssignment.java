package com.tonyhe.fusion.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * 作用域角色分配实体
 * <p>
 * 对应数据库表：sys_role_assignment
 * 支持在 SYSTEM / TEAM / PROJECT 三种作用域下为用户分配角色，
 * 实现细粒度 ABAC 授权。
 */
@TableName("sys_role_assignment")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignment extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 角色ID */
    private Long roleId;

    /** 作用域类型：SYSTEM / TEAM / PROJECT */
    private String scopeType;

    /** 作用域ID：SYSTEM 为 null，TEAM/PROJECT 为对应 ID */
    private Long scopeId;

    /** 状态：ACTIVE / DISABLED */
    @Builder.Default
    private String status = "ACTIVE";

    /** 过期时间，可选，用于临时授权 */
    private java.time.LocalDateTime expiresAt;

    /** 创建者用户ID */
    private Long createdBy;
}
