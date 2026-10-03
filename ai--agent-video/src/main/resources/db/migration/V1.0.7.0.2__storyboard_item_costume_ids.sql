-- 为分镜条目表添加服装子资产ID列表字段
-- costume_ids 存储 JSON 数组，包含角色在该镜头中穿着的 costume 类型 AssetItem.id
-- 用于支持角色服装动态切换：角色基体(initial)定义长相，costume 子资产定义具体服装

ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `costume_ids` json NULL DEFAULT NULL COMMENT '服装子资产ID列表 JSON (List<Long> of AssetItem.id, itemType=costume)' AFTER `character_ids`;
