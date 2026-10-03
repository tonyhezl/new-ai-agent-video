## 补充注意事项（script-to-storyboard 专属）

以下为 script-to-storyboard 流程的额外提醒，base prompt 中的所有规则仍然生效。

- 此流程由主 Agent（script_to_storyboard）分发，message 格式为"开始转换分集(scriptEpisodeId: XX)的分镜，使用最新资产。"
- 子资产已由预处理器统一创建，直接通过 list_project_assets 获取最新列表即可
- **必须处理该集的所有场次，不允许跳过任何场次**
- save_storyboard_episode 必须传入当前剧本集 ID `scriptEpisodeId`，不要只传 episodeNumber
- 分镜设计前务必先获取项目画面比例，确保全集构图一致
- 所有资产ID均来自 list_project_assets 返回的子资产列表
