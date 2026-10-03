package com.tonyhe.fusion.config.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent 注册表
 * <p>
 * 在代码中静态定义所有 Agent 配置，提示词从 resources/prompts/agents/ 目录加载。
 * 新增 Agent 只需在 {@link #registerBuiltinAgents()} 中添加。
 */
@Component
public class AiAgentRegistry {

        private final Map<String, AiAgentDefinition> agentMap = new LinkedHashMap<>();

        public AiAgentRegistry() {
                registerBuiltinAgents();
        }

        /**
         * 从 classpath 加载提示词文件
         *
         * @param fileName 文件名（相对于 prompts/agents/ 目录）
         * @return 提示词内容
         */
        private static String loadPrompt(String fileName) {
                try {
                        ClassPathResource resource = new ClassPathResource("prompts/agents/" + fileName);
                        try (InputStream is = resource.getInputStream()) {
                                return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                        }
                } catch (IOException e) {
                        throw new RuntimeException("加载提示词文件失败: prompts/agents/" + fileName, e);
                }
        }

        /**
         * 注册内置 Agent 定义
         */
        private void registerBuiltinAgents() {
                registerAiMediaAgent();
                registerConceptVisualizerAgent();
                registerScriptWriterAgent();
                registerScriptFullParseAgent();
                registerScriptStoryToScriptAgent();
                registerScriptEpisodeUploadAgent();
                registerScriptAssistantAgent();
                registerScriptToStoryboardAgent();
                registerEpisodeSceneWriterAgent();
                registerEpisodeScriptCreatorAgent();
                registerEpisodeStoryboardWriterAgent();
                registerStoryboardAssetPreprocessorAgent();
                registerAssetImageGenerationAgent();
                registerAssetImageExecutorAgent();
                registerStoryboardFrameGenAgent();
                registerStoryboardFrameExecutorAgent();
                registerStoryboardVideoGenAgent();
                registerStoryboardVideoExecutorAgent();
                registerPromptReviewerAgent();
                registerStoryboardContentReviewerAgent();
                registerScriptDoctorAgent();
                registerPovStoryArchitectAgent();
                registerPovSuspenseReviewerAgent();
                registerPovVisualDirectorAgent();
        }

        // ========== 各 Agent 定义 ==========

        private void registerAiMediaAgent() {
                register(AiAgentDefinition.builder()
                                .type("ai_media")
                                .name("默认助手")
                                .toolNames(List.of(
                                                "list_my_projects", "get_project",
                                                "get_project_script", "list_project_assets", "list_project_storyboards",
                                                "get_script", "get_script_structure", "get_script_episode",
                                                "update_script", "update_script_info", "save_script_episode",
                                                "save_script_scene_items", "update_script_scene",
                                                "manage_script_scenes",
                                                "get_asset", "create_asset", "batch_create_assets", "update_asset",
                                                "add_asset_item",
                                                "get_storyboard", "insert_storyboard_item",
                                                "get_generation_model_capabilities",
                                                "generate_image",
                                                "query_asset_metadata"))
                                .systemPrompt(loadPrompt("ai-media.system.md"))
                                .instructionTemplate(loadPrompt("ai-media.instruction.md"))
                                .enableTools(1)
                                .build());
        }

        private void registerConceptVisualizerAgent() {
                register(AiAgentDefinition.builder()
                                .type("concept_visualizer")
                                .name("概念可视化")
                                .systemPrompt(loadPrompt("concept-visualizer.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        private void registerScriptWriterAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_writer")
                                .name("AI 剧本创作")
                                .toolNames(List.of("append_script_raw_content"))
                                .systemPrompt(loadPrompt("script-writer.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <script_id>{scriptId}</script_id>
                                                <genre>{genre}</genre>
                                                <style>{style}</style>
                                                <type>{type}</type>
                                                <visual_style>{visualStyle}</visual_style>
                                                <episode_count>{episodeCount}</episode_count>
                                                <description>{description}</description>
                                                </task_context>""")
                                .defaultUserMessage("请根据以上创意参数，为剧本（ID: {scriptId}）创作 {episodeCount} 集完整剧本文本。")
                                .enableTools(1)
                                .build());
        }

        private void registerScriptFullParseAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_full_parse")
                                .name("完整剧本解析")
                                .toolNames(List.of(
                                                "get_project_script", "list_project_assets", "batch_create_assets",
                                                "update_script_info", "save_script_episode",
                                                "get_script_structure", "query_asset_metadata"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("episode_scene_writer")
                                                                .displayName("分集场次编写器")
                                                                .description("对指定分集内容进行场次拆解和编写。输入分集编号和内容摘要，子Agent会自动解析并保存场次数据。可同时调用多个实例并行处理不同分集。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeId": {
                                                                                                      "type": "integer",
                                                                                                      "description": "剧本分集记录ID（从 save_script_episode 的返回值中获取）"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeId"]
                                                                                                }""")
                                                                .refAgentType("episode_scene_writer")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "子 Agent 的场次解析输出",
                                                                                  "properties": {
                                                                                    "scriptEpisodeId": { "type": "integer", "description": "处理的剧本分集ID" },
                                                                                    "sceneCount": { "type": "integer", "description": "解析出的场次数量" },
                                                                                    "status": { "type": "string", "enum": ["success", "partial", "failed"], "description": "处理状态" },
                                                                                    "message": { "type": "string", "description": "处理结果描述" }
                                                                                  },
                                                                                  "required": ["scriptEpisodeId", "sceneCount", "status"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-full-parse_episode-scene-writer.override.md"))
                                                                .build(),
                                                // 剧本医生子 Agent（场次解析完成后调用）
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_script")
                                                                .displayName("剧本诊断校正")
                                                                .description("在所有集的场次解析完成后调用此工具，对剧本进行诊断评估。子Agent会逐集读取场次和对白数据，输出诊断报告（钩子强度、节奏诊断、可拍性风险、对白可演性等），并使用 update_script_scene 修复P0级问题。必须在所有 episode_scene_writer 调用完成后再调用此工具，且只调用一次。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeIds": {
                                                                                                      "type": "string",
                                                                                                      "description": "所有已解析完成的剧本分集ID列表，逗号分隔，如 '1,2,3'"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeIds"]
                                                                                                }""")
                                                                .refAgentType("script_doctor")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "剧本诊断输出",
                                                                                  "properties": {
                                                                                    "status": { "type": "string", "enum": ["success", "partial", "failed"], "description": "处理状态" },
                                                                                    "verdict": { "type": "string", "description": "总判结论" },
                                                                                    "p0Count": { "type": "integer", "description": "P0致命伤数量" },
                                                                                    "p1Count": { "type": "integer", "description": "P1建议修改数量" },
                                                                                    "fixedCount": { "type": "integer", "description": "已自动修复的场次数量" },
                                                                                    "message": { "type": "string", "description": "诊断结果描述" }
                                                                                  },
                                                                                  "required": ["status", "verdict", "p0Count", "p1Count"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-full-parse_script-doctor.override.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("script-full-parse.system.md"))
                                .instructionTemplate("{scriptContent}")
                                .defaultUserMessage("请解析项目 {projectId} 的剧本（ID: {scriptId}），将原文内容解析为结构化的分集、场次和对白数据。")
                                .enableTools(1)
                                .build());
        }

        private void registerScriptStoryToScriptAgent() {
                register(AiAgentDefinition.builder()
                                .type("story_to_script")
                                .name("故事转剧本")
                                .toolNames(List.of(
                                                "list_project_assets", "batch_create_assets",
                                                "update_script_info", "save_script_episode",
                                                "get_project_script", "query_asset_metadata", "get_script_structure"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("episode_script_creator")
                                                                .displayName("分集剧本创作器")
                                                                .description("对指定分集进行对白和场次创作。输入分集编号，子Agent会自动查询大纲、设计场次、创作对白并保存。可同时调用多个实例并行处理不同分集。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeId": {
                                                                                                      "type": "integer",
                                                                                                      "description": "剧本分集记录ID（从 save_script_episode 的返回值中获取）"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeId"]
                                                                                                }""")
                                                                .refAgentType("episode_script_creator")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "子 Agent 的场次创作输出",
                                                                                  "properties": {
                                                                                    "scriptEpisodeId": { "type": "integer", "description": "处理的剧本分集ID" },
                                                                                    "sceneCount": { "type": "integer", "description": "创作的场次数量" },
                                                                                    "status": { "type": "string", "enum": ["success", "partial", "failed"], "description": "处理状态" },
                                                                                    "message": { "type": "string", "description": "处理结果描述" }
                                                                                  },
                                                                                  "required": ["scriptEpisodeId", "sceneCount", "status"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-story-to-script_episode-script-creator.override.md"))
                                                                .build(),
                                                // 剧本医生子 Agent（场次创作完成后调用）
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_script")
                                                                .displayName("剧本诊断校正")
                                                                .description("在所有集的场次创作完成后调用此工具，对剧本进行诊断评估。子Agent会逐集读取场次和对白数据，输出诊断报告（钩子强度、节奏诊断、可拍性风险、对白可演性等），并使用 update_script_scene 修复P0级问题。必须在所有 episode_script_creator 调用完成后再调用此工具，且只调用一次。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeIds": {
                                                                                                      "type": "string",
                                                                                                      "description": "所有已创作完成的剧本分集ID列表，逗号分隔，如 '1,2,3'"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeIds"]
                                                                                                }""")
                                                                .refAgentType("script_doctor")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "剧本诊断输出",
                                                                                  "properties": {
                                                                                    "status": { "type": "string", "enum": ["success", "partial", "failed"], "description": "处理状态" },
                                                                                    "verdict": { "type": "string", "description": "总判结论" },
                                                                                    "p0Count": { "type": "integer", "description": "P0致命伤数量" },
                                                                                    "p1Count": { "type": "integer", "description": "P1建议修改数量" },
                                                                                    "fixedCount": { "type": "integer", "description": "已自动修复的场次数量" },
                                                                                    "message": { "type": "string", "description": "诊断结果描述" }
                                                                                  },
                                                                                  "required": ["status", "verdict", "p0Count", "p1Count"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-story-to-script_script-doctor.override.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("script-story-to-script.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <script_id>{scriptId}</script_id>
                                                </task_context>""")
                                .defaultUserMessage("请根据项目 {projectId} 的剧本大纲（ID: {scriptId}），创作完整的分集场次和对白内容。")
                                .enableTools(1)
                                .build());
        }

        private void registerScriptEpisodeUploadAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_episode_parse")
                                .name("分集上传解析")
                                .toolNames(List.of(
                                                "list_project_assets", "batch_create_assets",
                                                "update_script_info", "save_script_scene_items",
                                                "get_script_episode", "get_script_structure",
                                                "manage_script_scenes", "query_asset_metadata", "get_project"))
                                .systemPrompt(loadPrompt("script-episode-upload.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <script_id>{scriptId}</script_id>
                                                <episode_id>{episodeId}</episode_id>
                                                </task_context>""")
                                .defaultUserMessage("请解析分集（episodeId: {episodeId}）的剧本原文，将内容解析为结构化的场次和对白数据。")
                                .enableTools(1)
                                .build());
        }

        private void registerScriptAssistantAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_assistant")
                                .name("剧本对话助手")
                                .toolNames(List.of(
                                                "get_script_structure", "get_script_episode",
                                                "update_script_scene", "manage_script_scenes",
                                                "list_project_assets", "create_asset"))
                                .systemPrompt(loadPrompt("script-assistant.system.md"))
                                .instructionTemplate("")
                                .enableTools(1)
                                .build());
        }

        private void registerScriptToStoryboardAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_to_storyboard")
                                .name("剧本转分镜")
                                .toolNames(List.of(
                                                "get_project", "get_script_structure",
                                                "list_project_assets", "get_storyboard",
                                                "list_project_storyboards"))
                                .subAgentTools(List.of(
                                                // 子资产预处理子 Agent（串行，先执行）
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("storyboard_asset_preprocessor")
                                                                .displayName("子资产预处理器")
                                                                .description("分析所有分集剧本内容，识别角色/场景/道具的外观变化，统一创建所需的子资产变体并保存到数据库。此工具必须在 episode_storyboard_writer 之前调用，且只调用一次。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeIds": {
                                                                                                      "type": "string",
                                                                                                      "description": "所有需要处理的剧本分集ID列表，逗号分隔，如 '1,2,3'"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeIds"]
                                                                                                }""")
                                                                .refAgentType("storyboard_asset_preprocessor")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "子资产预处理输出",
                                                                                  "properties": {
                                                                                    "createdCount": { "type": "integer", "description": "新创建的子资产数量" },
                                                                                    "status": { "type": "string", "enum": ["success", "failed"], "description": "处理状态" },
                                                                                    "message": { "type": "string", "description": "处理结果描述" }
                                                                                  },
                                                                                  "required": ["status"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-to-storyboard_asset-preprocessor.override.md"))
                                                                .build(),
                                                // 分集分镜编写子 Agent（并行，后执行）
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("episode_storyboard_writer")
                                                                .displayName("分集分镜编写器")
                                                                .description("对指定分集进行分镜转换。输入分集编号，子Agent会自动查询场次内容、获取最新资产列表（含预处理器已创建的子资产）、设计镜头并保存分镜数据。可同时调用多个实例并行处理不同分集。调用时只传声明中要求的业务参数，不要传 session_id，框架会自动维护会话。")
                                                                .parametersSchema(
                                                                                """
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "scriptEpisodeId": {
                                                                                                      "type": "integer",
                                                                                                      "description": "剧本分集记录ID（从 get_script_structure 获取）"
                                                                                                    }
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["scriptEpisodeId"]
                                                                                                }""")
                                                                .refAgentType("episode_storyboard_writer")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "子 Agent 的分镜转换输出",
                                                                                  "properties": {
                                                                                    "scriptEpisodeId": { "type": "integer", "description": "处理的剧本分集ID" },
                                                                                    "shotCount": { "type": "integer", "description": "生成的镜头总数" },
                                                                                    "sceneCount": { "type": "integer", "description": "处理的场次数量" },
                                                                                    "status": { "type": "string", "enum": ["success", "partial", "failed"], "description": "处理状态" },
                                                                                    "message": { "type": "string", "description": "处理结果描述" }
                                                                                  },
                                                                                  "required": ["scriptEpisodeId", "shotCount", "sceneCount", "status"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt(
                                                                                "script-to-storyboard_episode-storyboard-writer.override.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("script-to-storyboard.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <storyboard_id>{storyboardId}</storyboard_id>
                                                <script_id>{scriptId}</script_id>
                                                </task_context>""")
                                .defaultUserMessage("请根据项目 {projectId} 的剧本（ID: {scriptId}），将结构化剧本数据转换为分镜表。")
                                .enableTools(1)
                                .build());
        }

        private void registerEpisodeSceneWriterAgent() {
                register(AiAgentDefinition.builder()
                                .type("episode_scene_writer")
                                .name("分集场次编写器")
                                .toolNames(List.of(
                                                "get_script_episode", "list_project_assets",
                                                "save_script_scene_items", "get_project_script",
                                                "get_script_scene"))
                                .systemPrompt(loadPrompt("episode-scene-writer.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <script_id>{scriptId}</script_id>
                                                </task_context>

                                                请根据主 Agent 提供的 episodeId，查询该集原文并解析场次数据。""")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分集剧本创作子 Agent
         * <p>
         * 每个实例只处理一集，自主完成查询大纲→获取资产→创作场次对白→保存全流程。
         */
        private void registerEpisodeScriptCreatorAgent() {
                register(AiAgentDefinition.builder()
                                .type("episode_script_creator")
                                .name("分集剧本创作器")
                                .toolNames(List.of(
                                                "get_script_episode", "list_project_assets", "save_script_scene_items"))
                                .systemPrompt(loadPrompt("episode-script-creator.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <script_id>{scriptId}</script_id>
                                                </task_context>

                                                请根据主 Agent 提供的 episodeId，查询该集大纲并创作完整的场次和对白。""")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分集分镜编写子 Agent
         * <p>
         * 每个实例只处理一集，自主完成查场次→设计镜头→匹配子资产→保存分镜全流程。
         */
        private void registerEpisodeStoryboardWriterAgent() {
                register(AiAgentDefinition.builder()
                                .type("episode_storyboard_writer")
                                .name("分集分镜编写器")
                                .toolNames(List.of(
                                                "get_script_episode", "get_script_scene", "list_project_assets",
                                                "save_storyboard_episode", "save_storyboard_scene_shots"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_storyboard_content")
                                                                .displayName("分镜内容复核")
                                                                .description("在保存分镜前复核整场镜头计划。调用时传入 shotStrategy、beats、完整剧本场次、场景状态和完整镜头字段；返回已完成拆合修正的 finalShots、approved、unresolvedErrors 与 reviewedShotCount。只有 approved=true 且无未解决错误时才能保存。")
                                                                .refAgentType("storyboard_content_reviewer")
                                                                .build()))
                                .systemPrompt(loadPrompt("episode-storyboard-writer.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <storyboard_id>{storyboardId}</storyboard_id>
                                                <script_id>{scriptId}</script_id>
                                                <script_episode_id>{scriptEpisodeId}</script_episode_id>
                                                </task_context>

                                                请根据任务上下文中的 scriptEpisodeId，查询该集剧本内容并设计分镜。""")
                                .defaultUserMessage("请为剧本分集（scriptEpisodeId: {scriptEpisodeId}）生成分镜，并保存到分镜脚本 {storyboardId}。")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分镜子资产预处理子 Agent
         * <p>
         * 串行执行（只调用一次），负责分析所有分集剧本内容，
         * 识别角色/场景/道具的外观变化，统一创建子资产变体，
         * 返回完整的 assetItemMapping 供分镜编写子 Agent 使用。
         */
        private void registerStoryboardAssetPreprocessorAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_asset_preprocessor")
                                .name("子资产预处理器")
                                .toolNames(List.of(
                                                "get_script_episode", "list_project_assets",
                                                "query_asset_items", "batch_create_asset_items",
                                                "query_asset_metadata"))
                                .systemPrompt(loadPrompt("storyboard-asset-preprocessor.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <script_id>{scriptId}</script_id>
                                                </task_context>

                                                请根据主 Agent 提供的 episodeIds，逐集分析剧本内容并创建所需的子资产变体。""")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册资产图片自动生成主 Agent
         * <p>
         * 负责获取项目画风、收集无图子资产、按子资产维度并行分发子 Agent。
         */
        private void registerAssetImageGenerationAgent() {
                register(AiAgentDefinition.builder()
                                .type("asset_image_gen")
                                .name("资产图片自动生成")
                                .toolNames(List.of(
                                                "get_project", "query_asset_items", "list_project_assets"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("generate_asset_image")
                                                                .displayName("为子资产生成图片")
                                                                .description("""
                                                                                为单个子资产生成AI图片并自动保存。每次调用只处理一个子资产，可在同一轮同时调用多个实例并行执行。

                                                                                调用时 message 必须包含以下信息（每行一个键值对）：
                                                                                - assetId: 主资产ID（数字，必传）
                                                                                - itemId: 子资产ID（数字，必传）
                                                                                - projectId: 项目ID（数字，必传）
                                                                                - 不要额外传 session_id，框架会自动维护会话

                                                                                message 格式示例：
                                                                                请为子资产生成图片。
                                                                                assetId: 1
                                                                                itemId: 3
                                                                                projectId: 5""")
                                                                .refAgentType("asset_image_executor")
                                                                .build()))
                                .systemPrompt(loadPrompt("asset-image-generation.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                </task_context>""")
                                .defaultUserMessage("请为项目 {projectId} 的资产自动生成图片。")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册资产图片生成执行子 Agent
         * <p>
         * 每个实例只处理一个子资产，自主完成查资产→编排prompt→生图→回填全流程。
         */
        private void registerAssetImageExecutorAgent() {
                register(AiAgentDefinition.builder()
                                .type("asset_image_executor")
                                .name("资产图片生成执行器")
                                .toolNames(List.of(
                                                "get_project", "query_asset_items", "get_generation_model_capabilities", "generate_image",
                                                "update_asset_image"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_frame_prompt")
                                                                .displayName("资产图提示词校正")
                                                                .description("校正资产图片提示词，检查空间一致性、景别运镜、参考图编号、能力合规和题材画风一致性。在编写完 draftPrompt 后、调用 generate_image 之前必须调用此工具。调用时传入 draftPrompt、资产上下文、参考图列表、模型能力摘要和画风描述。")
                                                                .refAgentType("prompt_reviewer")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "校正结果",
                                                                                  "properties": {
                                                                                    "revisedPrompt": {"type": "string", "description": "校正后的完整提示词"},
                                                                                    "changed": {"type": "boolean", "description": "是否有过修改"},
                                                                                    "issues": {"type": "array", "items": {"type": "object"}},
                                                                                    "checks": {"type": "object"}
                                                                                  },
                                                                                  "required": ["revisedPrompt", "changed", "checks"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt("prompt-reviewer.system.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("asset-image-executor.system.md"))
                                .instructionTemplate("")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分镜首尾帧生成主 Agent
         * <p>
         * 负责读取前端指定的镜头、帧类型和提示词，并按镜头维度分发子 Agent。
         */
        private void registerStoryboardFrameGenAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_frame_gen")
                                .name("分镜首尾帧生成")
                                .toolNames(List.of(
                                                "get_project", "get_storyboard", "get_storyboard_scene_items"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("generate_storyboard_frame")
                                                                .displayName("为镜头生成首尾帧")
                                                                .description("""
                                                                                为单个分镜镜头生成首帧或尾帧图片并自动保存。每次调用只处理一个镜头的一个帧。

	                                                                                调度约束：同一 storyboardSceneId 的镜头按 sortOrder 串行，不同场次可并行。框架会按 storyboardSceneId 使用非阻塞队列。

                                                                                调用时 message 必须包含以下信息（每行一个键值对）：
	                                                                                - storyboardItemId: 分镜条目ID（数字，必传）
	                                                                                - storyboardSceneId: 分镜场次ID（数字，必传，用于按场次调度）
	                                                                                - projectId: 项目ID（数字，必传）
                                                                                - frameType: 帧类型，first 或 last（必传）
                                                                                - framePrompt: 用户确认后的首尾帧提示词（必传）
                                                                                - 不要额外传 session_id，框架会自动维护会话

                                                                                message 格式示例：
	                                                                                请为分镜镜头生成首尾帧。
	                                                                                storyboardItemId: 42
	                                                                                storyboardSceneId: 7
	                                                                                projectId: 5
                                                                                frameType: first
                                                                                framePrompt: 生成该镜头视频的首帧定格图……""")
                                                                .refAgentType("storyboard_frame_executor")
                                                                .sequential(true)
                                                                .build()))
                                .systemPrompt(loadPrompt("storyboard-frame-gen.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <storyboard_id>{storyboardId}</storyboard_id>
                                                <frame_type>{frameType}</frame_type>
                                                <frame_prompt>{framePrompt}</frame_prompt>
                                                </task_context>""")
                                .defaultUserMessage("请为项目 {projectId} 的分镜镜头生成首尾帧。")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分镜首尾帧生成执行子 Agent
         * <p>
         * 每个实例只处理一个镜头的一个帧，自主完成查上下文、生图、回填全流程。
         */
        private void registerStoryboardFrameExecutorAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_frame_executor")
                                .name("分镜首尾帧生成执行器")
                                .toolNames(List.of(
                                                "get_project", "get_storyboard_scene_items",
                                                "get_generation_model_capabilities", "generate_image",
                                                "update_storyboard_item_frame"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_frame_prompt")
                                                                .displayName("首尾帧提示词校正")
                                                                .description("校正首尾帧图片提示词，检查空间一致性、景别运镜、参考图编号、能力合规和题材画风一致性。在编写完 draftPrompt 后、调用 generate_image 之前必须调用此工具。调用时传入 draftPrompt、分镜上下文（包含 frameType 和 firstFramePrompt）、参考图列表、模型能力摘要和画风描述。")
                                                                .parametersSchema("""
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "draftPrompt": {"type": "string", "description": "待校正的提示词"},
                                                                                                    "shotContext": {"type": "string", "description": "分镜上下文 JSON，包含 content、sceneExpectation、dialogue、shotType、cameraMovement、cameraAngle、frameType（first/last）、firstFramePrompt（当 frameType=last 时必须传入已生成的首帧提示词，用于位置和服装继承检查）"},
                                                                                                    "referenceImages": {"type": "string", "description": "参考图列表 JSON，每项含 index、role、name"},
                                                                                                    "modelCapabilities": {"type": "string", "description": "模型能力摘要 JSON"},
                                                                                                    "artStyle": {"type": "string", "description": "项目画风描述"}
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["draftPrompt", "shotContext", "referenceImages", "modelCapabilities", "artStyle"]
                                                                                                }""")
                                                                .refAgentType("prompt_reviewer")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "校正结果",
                                                                                  "properties": {
                                                                                    "revisedPrompt": {"type": "string", "description": "校正后的完整提示词"},
                                                                                    "changed": {"type": "boolean", "description": "是否有过修改"},
                                                                                    "issues": {"type": "array", "items": {"type": "object"}},
                                                                                    "checks": {"type": "object"}
                                                                                  },
                                                                                  "required": ["revisedPrompt", "changed", "checks"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt("prompt-reviewer.system.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("storyboard-frame-executor.system.md"))
                                .instructionTemplate("")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分镜视频生成主 Agent
         * <p>
         * 负责获取项目信息、分析选中的分镜镜头、按镜头维度并行分发子 Agent。
         */
        private void registerStoryboardVideoGenAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_video_gen")
                                .name("分镜视频生成")
                                .toolNames(List.of(
                                                "get_project", "get_storyboard", "get_storyboard_scene_items"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("generate_storyboard_video")
                                                                .displayName("为镜头生成视频")
                                                                .description("""
                                                                                为单个分镜镜头生成AI视频并自动保存。每次调用只处理一个镜头。

	                                                                                调度约束：同一 storyboardSceneId 的镜头按 sortOrder 串行，不同场次可并行。框架会按 storyboardSceneId 使用非阻塞队列。

                                                                                调用时 message 必须包含以下信息（每行一个键值对）：
	                                                                                - storyboardItemId: 分镜条目ID（数字，必传）
	                                                                                - storyboardSceneId: 分镜场次ID（数字，必传，用于按场次调度）
	                                                                                - projectId: 项目ID（数字，必传）
                                                                                - 不要额外传 session_id，框架会自动维护会话

                                                                                message 格式示例：
	                                                                                请为分镜镜头生成视频。
	                                                                                storyboardItemId: 42
	                                                                                storyboardSceneId: 7
	                                                                                projectId: 5""")
                                                                .refAgentType("storyboard_video_executor")
                                                                .sequential(true)
                                                                .build()))
                                .systemPrompt(loadPrompt("storyboard-video-gen.system.md"))
                                .instructionTemplate("""
                                                <task_context>
                                                <project_id>{projectId}</project_id>
                                                <storyboard_id>{storyboardId}</storyboard_id>
                                                </task_context>""")
                                .defaultUserMessage("请为项目 {projectId} 的分镜镜头生成视频。")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册分镜视频生成执行子 Agent
         * <p>
         * 每个实例只处理一个镜头，自主完成查上下文→编排prompt→生视频→回填全流程。
         */
        private void registerStoryboardVideoExecutorAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_video_executor")
                                .name("分镜视频生成执行器")
                                .toolNames(List.of(
                                                "get_project", "get_storyboard_scene_items",
                                                "get_generation_model_capabilities", "generate_video", "update_storyboard_item_video"))
                                .subAgentTools(List.of(
                                                AiAgentDefinition.SubAgentToolDef.builder()
                                                                .toolName("review_video_prompt")
                                                                .displayName("视频提示词校正")
                                                                .description("校正视频提示词，检查空间一致性、景别运镜、参考图编号、能力合规和题材画风一致性。在编写完 draftPrompt 后、调用 generate_video 之前必须调用此工具。调用时传入 draftPrompt、分镜上下文、参考图列表、模型能力摘要和画风描述。")
                                                                .parametersSchema("""
                                                                                                {
                                                                                                  "type": "object",
                                                                                                  "properties": {
                                                                                                    "draftPrompt": {"type": "string", "description": "待校正的提示词"},
                                                                                                    "shotContext": {"type": "string", "description": "分镜上下文 JSON，包含 content、sceneExpectation、dialogue、shotType、cameraMovement、cameraAngle"},
                                                                                                    "referenceImages": {"type": "string", "description": "参考图列表 JSON，每项含 index、role、name"},
                                                                                                    "modelCapabilities": {"type": "string", "description": "模型能力摘要 JSON"},
                                                                                                    "artStyle": {"type": "string", "description": "项目画风描述"}
                                                                                                  },
                                                                                                  "additionalProperties": false,
                                                                                                  "required": ["draftPrompt", "shotContext", "referenceImages", "modelCapabilities", "artStyle"]
                                                                                                }""")
                                                                .refAgentType("prompt_reviewer")
                                                                .outputSchema("""
                                                                                {
                                                                                  "type": "object",
                                                                                  "description": "校正结果",
                                                                                  "properties": {
                                                                                    "revisedPrompt": {"type": "string", "description": "校正后的完整提示词"},
                                                                                    "changed": {"type": "boolean", "description": "是否有过修改"},
                                                                                    "issues": {"type": "array", "items": {"type": "object"}},
                                                                                    "checks": {"type": "object"}
                                                                                  },
                                                                                  "required": ["revisedPrompt", "changed", "checks"]
                                                                                }""")
                                                                .systemPromptOverride(loadPrompt("prompt-reviewer.system.md"))
                                                                .build()))
                                .systemPrompt(loadPrompt("storyboard-video-executor.system.md"))
                                .instructionTemplate("")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册剧本医生 Agent
         * <p>
         * 诊断剧本病灶并开处方，不负责分镜、不负责备料，也不擅自改写成全文。
         * 在剧本解析完成后、分镜生成前执行，输出诊断报告供下游参考。
         * 被 script_full_parse 和 story_to_script 的子 Agent 工具引用。
         */
        private void registerScriptDoctorAgent() {
                register(AiAgentDefinition.builder()
                                .type("script_doctor")
                                .name("剧本医生")
                                .toolNames(List.of(
                                                "get_project", "get_script_structure", "get_script_episode",
                                                "list_project_assets", "update_script_scene", "get_script_scene"))
                                .systemPrompt(loadPrompt("script-doctor.system.md"))
                                .instructionTemplate("")
                                .enableTools(1)
                                .build());
        }

        /**
         * 注册 Prompt 校正 Agent
         * <p>
         * 纯推理 Agent（不调工具），接收 draftPrompt 和上下文，
         * 输出 revisedPrompt + issues[] + checks{}。
         * 被 frame/video executor 的子 Agent 工具引用。
         */
        private void registerPromptReviewerAgent() {
                register(AiAgentDefinition.builder()
                                .type("prompt_reviewer")
                                .name("Prompt 校正")
                                .systemPrompt(loadPrompt("prompt-reviewer.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        /**
         * 注册分镜内容复核 Agent
         * <p>
         * 纯推理 Agent（不调工具），接收分镜镜头列表和剧本上下文，
         * 输出修正后的 content/sceneExpectation + issues[]。
         * 被 episode-storyboard-writer 的子 Agent 工具引用。
         */
        private void registerStoryboardContentReviewerAgent() {
                register(AiAgentDefinition.builder()
                                .type("storyboard_content_reviewer")
                                .name("分镜内容复核")
                                .systemPrompt(loadPrompt("storyboard-content-reviewer.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        private void registerPovStoryArchitectAgent() {
                register(AiAgentDefinition.builder()
                                .type("pov_story_architect")
                                .name("第一人称猎奇故事编剧")
                                .systemPrompt(loadPrompt("pov-story-architect.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        private void registerPovSuspenseReviewerAgent() {
                register(AiAgentDefinition.builder()
                                .type("pov_suspense_reviewer")
                                .name("第一人称悬疑质检门禁")
                                .systemPrompt(loadPrompt("pov-suspense-reviewer.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        private void registerPovVisualDirectorAgent() {
                register(AiAgentDefinition.builder()
                                .type("pov_visual_director")
                                .name("第一人称视觉提示词导演")
                                .systemPrompt(loadPrompt("pov-visual-director.system.md"))
                                .instructionTemplate("")
                                .enableTools(0)
                                .build());
        }

        /**
         * 注册 Agent 定义
         */
        public void register(AiAgentDefinition definition) {
                agentMap.put(definition.getType(), definition);
        }

        /**
         * 根据类型获取 Agent 定义
         *
         * @param type Agent 类型
         * @return Agent 定义，不存在返回 null
         */
        public AiAgentDefinition getByType(String type) {
                return agentMap.get(type);
        }

        /**
         * 获取所有 Agent 定义
         */
        public List<AiAgentDefinition> getAll() {
                return new ArrayList<>(agentMap.values());
        }
}
