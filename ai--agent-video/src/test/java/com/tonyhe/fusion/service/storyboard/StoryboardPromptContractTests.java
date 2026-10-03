package com.tonyhe.fusion.service.storyboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardPromptContractTests {

    @Test
    void writerUsesMultiBeatHybridStrategyAndHardReviewContract() throws IOException {
        String prompt = readPrompt("episode-storyboard-writer.system.md");

        assertThat(prompt)
                .contains("beatIds")
                .contains("GENERAL_NARRATIVE")
                .contains("reviewApproved=true")
                .contains("finalShots")
                .contains("assets[].items[].assetItemId")
                .contains("禁止把主资产 ID 拼进 `objectId`")
                .doesNotContain("每个镜头必须绑定 sceneAssetItemId")
                .doesNotContain("固定\"镜头占比不得超过 60%")
                .doesNotContain("反应镜头是独立节拍")
                .doesNotContain("至少有一长一短")
                .doesNotContain("#### 类型 A：");
    }

    @Test
    void reviewerReturnsCompleteFinalShotsInsteadOfLossyMergeInstructions() throws IOException {
        String prompt = readPrompt("storyboard-content-reviewer.system.md");

        assertThat(prompt)
                .contains("shotStrategy / beats / finalShots")
                .contains("sceneStateDelta.changes")
                .contains("与 `propIds` 不一致")
                .contains("不能使用主资产 `Asset.id`")
                .contains("不得在推进后回到更早节拍")
                .contains("unresolvedErrors")
                .contains("reviewedShotCount")
                .contains("禁止输出旧协议标记")
                .doesNotContain("将后一镜的 content 和 sceneExpectation 合并到前一镜");
    }

    private String readPrompt(String fileName) throws IOException {
        String resource = "prompts/agents/" + fileName;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Prompt resource not found: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
