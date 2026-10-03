package com.tonyhe.fusion.entity.generation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * 模型能力推断规则实体。
 * <p>
 * 对应数据库表 afv_capability_rule。
 * 按优先级排序匹配，首个命中的规则决定模型的推断能力。
 */
@TableName("afv_capability_rule")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称 */
    private String name;

    /** 模型类型：2-图片 3-视频 */
    private Integer modelType;

    /** 匹配平台（normalizedPlatform），null=不限 */
    private String platform;

    /** 模型 code 包含关键词，null=不限 */
    private String codeContains;

    /** 模型 code 前缀，null=不限 */
    private String codeStartsWith;

    /** 匹配 modelFamily，null=不限 */
    private String family;

    /** 匹配 modelProtocol，null=不限 */
    private String protocol;

    /** 排序（越小越优先） */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 状态：0-禁用 1-启用 */
    @Builder.Default
    private Integer status = 1;

    /** 能力配置 JSON */
    private String capabilityConfig;

    @Builder.Default
    private Long deletedId = 0L;
}
