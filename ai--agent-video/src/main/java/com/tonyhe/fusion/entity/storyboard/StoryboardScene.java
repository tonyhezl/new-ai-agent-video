package com.tonyhe.fusion.entity.storyboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import com.tonyhe.fusion.common.handler.JsonbTypeHandler;
import lombok.*;

/**
 * 分镜场次实体
 * <p>
 * 对应数据库表：afv_storyboard_scene
 * 存储分镜集中的单个场次信息，每个场次下包含多个分镜条目（StoryboardItem）。
 */
@TableName(value = "afv_storyboard_scene", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryboardScene extends BaseEntity {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分镜集ID */
    private Long episodeId;

    /** 所属分镜ID（冗余字段，方便查询） */
    private Long storyboardId;

    /** 场次编号，如 "1-1" */
    private String sceneNumber;

    /** 场景标头，如 "内景 客厅 夜" */
    private String sceneHeading;

    /** 场景地点 */
    private String location;

    /** 时间段：日/夜/黄昏/清晨 等 */
    private String timeOfDay;

    /** 内外景标识：内景/外景/内外景 */
    private String intExt;

    /** 场次默认场景子资产ID；镜头级 sceneAssetItemId 可覆盖 */
    private Long baseSceneAssetItemId;

    /** 固定空间布局与锚点定义 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String layoutDefinition;

    /** 场次开始时的可移动对象状态 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String initialSceneState;

    /** 分镜拆分策略 JSON：primaryType + tags */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String shotStrategy;

    /** 本场次叙事节拍定义 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beats;

    /** 场景状态时间线版本 */
    @Builder.Default
    private Integer stateTimelineVersion = 0;

    /** 排列顺序 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 状态：0-草稿 1-正常 */
    @Builder.Default
    private Integer status = 0;
}
