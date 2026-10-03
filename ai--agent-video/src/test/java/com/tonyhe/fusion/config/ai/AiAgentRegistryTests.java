package com.tonyhe.fusion.config.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentRegistryTests {

    private final AiAgentRegistry registry = new AiAgentRegistry();

    @Test
    void shouldRegisterScriptDoctorAgent() {
        AiAgentDefinition doctor = registry.getByType("script_doctor");
        assertNotNull(doctor, "script_doctor agent must be registered");
        assertEquals("剧本医生", doctor.getName());
        assertNotNull(doctor.getSystemPrompt());
        assertFalse(doctor.getSystemPrompt().isBlank());
        assertEquals(1, doctor.getEnableTools());
    }

    @Test
    void scriptDoctorShouldHaveRequiredTools() {
        AiAgentDefinition doctor = registry.getByType("script_doctor");
        assertNotNull(doctor.getToolNames());
        List<String> tools = doctor.getToolNames();
        assertTrue(tools.contains("get_project"), "must have get_project");
        assertTrue(tools.contains("get_script_structure"), "must have get_script_structure");
        assertTrue(tools.contains("get_script_episode"), "must have get_script_episode");
        assertTrue(tools.contains("update_script_scene"), "must have update_script_scene for P0 fixes");
        assertTrue(tools.contains("get_script_scene"), "must have get_script_scene");
    }

    @Test
    void scriptFullParseShouldHaveReviewScriptSubAgent() {
        AiAgentDefinition fullParse = registry.getByType("script_full_parse");
        assertNotNull(fullParse.getSubAgentTools());
        AiAgentDefinition.SubAgentToolDef reviewTool = fullParse.getSubAgentTools().stream()
                .filter(t -> "review_script".equals(t.getToolName()))
                .findFirst()
                .orElse(null);
        assertNotNull(reviewTool, "script_full_parse must have review_script sub-agent tool");
        assertEquals("script_doctor", reviewTool.getRefAgentType());
        assertNotNull(reviewTool.getParametersSchema());
        assertNotNull(reviewTool.getOutputSchema());
        assertNotNull(reviewTool.getSystemPromptOverride());
        assertFalse(reviewTool.getSystemPromptOverride().isBlank());
    }

    @Test
    void storyToScriptShouldHaveReviewScriptSubAgent() {
        AiAgentDefinition storyToScript = registry.getByType("story_to_script");
        assertNotNull(storyToScript.getSubAgentTools());
        AiAgentDefinition.SubAgentToolDef reviewTool = storyToScript.getSubAgentTools().stream()
                .filter(t -> "review_script".equals(t.getToolName()))
                .findFirst()
                .orElse(null);
        assertNotNull(reviewTool, "story_to_script must have review_script sub-agent tool");
        assertEquals("script_doctor", reviewTool.getRefAgentType());
        assertNotNull(reviewTool.getParametersSchema());
        assertNotNull(reviewTool.getOutputSchema());
        assertNotNull(reviewTool.getSystemPromptOverride());
        assertFalse(reviewTool.getSystemPromptOverride().isBlank());
    }

    @Test
    void scriptFullParseShouldStillHaveEpisodeSceneWriter() {
        AiAgentDefinition fullParse = registry.getByType("script_full_parse");
        assertNotNull(fullParse.getSubAgentTools());
        boolean hasSceneWriter = fullParse.getSubAgentTools().stream()
                .anyMatch(t -> "episode_scene_writer".equals(t.getToolName()));
        assertTrue(hasSceneWriter, "script_full_parse must still have episode_scene_writer sub-agent");
    }

    @Test
    void storyToScriptShouldStillHaveEpisodeScriptCreator() {
        AiAgentDefinition storyToScript = registry.getByType("story_to_script");
        assertNotNull(storyToScript.getSubAgentTools());
        boolean hasCreator = storyToScript.getSubAgentTools().stream()
                .anyMatch(t -> "episode_script_creator".equals(t.getToolName()));
        assertTrue(hasCreator, "story_to_script must still have episode_script_creator sub-agent");
    }

    @Test
    void allAgentTypesShouldBeUnique() {
        long count = registry.getAll().size();
        long uniqueCount = registry.getAll().stream()
                .map(AiAgentDefinition::getType)
                .distinct()
                .count();
        assertEquals(count, uniqueCount, "All agent types must be unique");
    }
}
