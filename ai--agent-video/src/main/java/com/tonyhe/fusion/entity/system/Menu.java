package com.tonyhe.fusion.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

/**
 * 系统菜单实体
 * <p>
 * 对应数据库表：sys_menu
 * 统一存目录、页面、按钮和 API 权限声明。
 */
@TableName("sys_menu")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Menu extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父级目录ID，0为根节点 */
    @Builder.Default
    private Long parentId = 0L;

    /** 类型：DIRECTORY / PAGE / BUTTON / API */
    private String type;

    /** 稳定标识，不随显示文案变化 */
    private String name;

    /** 前端显示名称 */
    private String title;

    /** 前端路由或 API 路径 */
    private String path;

    /** 页面组件标识，可为空 */
    private String component;

    /** 权限码，如 project:update */
    private String permissionCode;

    /** 图标名称 */
    private String icon;

    /** 排序 */
    @Builder.Default
    private Integer sort = 0;

    /** 是否显示 */
    @Builder.Default
    private Boolean visible = true;

    /** 启用/禁用 */
    @Builder.Default
    private Integer status = 1;
}
