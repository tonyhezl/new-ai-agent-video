package com.tonyhe.fusion.service.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GenerateImageToolExecutor 的单元测试。
 * 重点验证画风 imagePrompt 的服务端注入检测逻辑。
 */
class GenerateImageToolExecutorTest {

    @Test
    void isImagePromptAlreadyIncluded_blankImagePrompt_returnsTrue() {
        assertTrue(GenerateImageToolExecutor.isImagePromptAlreadyIncluded("some prompt", ""));
        assertTrue(GenerateImageToolExecutor.isImagePromptAlreadyIncluded("some prompt", null));
    }

    @Test
    void isImagePromptAlreadyIncluded_promptContainsFullImagePrompt_returnsTrue() {
        String imagePrompt = "写实向3D CG渲染风格（high quality CG render），高精度模型，基于物理的材质质感";
        String prompt = "写实向3D CG渲染风格（high quality CG render），高精度模型，基于物理的材质质感，仅参考图片1的CG渲染精度...";
        assertTrue(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(prompt, imagePrompt));
    }

    @Test
    void isImagePromptAlreadyIncluded_promptHasOnlyOldVersion_returnsFalse() {
        // LLM 包含了旧版 imagePrompt（含 hyperrealistic），新版本已移除该关键词
        String oldImagePrompt = "真人写实摄影风格（photorealistic, hyperrealistic），自然真实的光影（natural lighting），8K级画质，高质量精细画面";
        String newImagePrompt = "真人写实摄影风格（photorealistic），自然真实的光影（natural lighting），8K级画质，画面干净通透，全域降噪，无细碎杂点，高质量精细画面";
        // 新版前30%指纹为"真人写实摄影风格（photorealistic），自然真实"，旧版不含此片段
        assertFalse(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(oldImagePrompt, newImagePrompt));
    }

    @Test
    void isImagePromptAlreadyIncluded_promptHasParaphrasedVersion_returnsFalse() {
        // LLM 用 description 摘要替代完整 imagePrompt 的情况
        String imagePrompt = "写实向3D CG渲染风格（high quality CG render），高精度模型，基于物理的材质质感";
        String prompt = "仅参考图片1的CG渲染精度、光影追踪质感、PBR材质细节和轮廓光表现力，绝不参考其中的任何具体角色、物品和构图，写实向3D CG渲染风格，高精度模型，真实光影与材质质感，体积云渲染，高质量精细画面。";
        assertFalse(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(prompt, imagePrompt));
    }

    @Test
    void isImagePromptAlreadyIncluded_promptMissingImagePrompt_returnsFalse() {
        String imagePrompt = "真人写实摄影风格（photorealistic），自然真实的光影";
        String prompt = "仅参考图片1的写实摄影光影质感，一个全身正面角色设定图，标准站姿正面";
        assertFalse(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(prompt, imagePrompt));
    }

    @Test
    void isImagePromptAlreadyIncluded_promptContainsHeadThird_returnsTrue() {
        // 前 30% 从 "真人写实摄影风格（photorealistic），自然真实" 开始
        String imagePrompt = "真人写实摄影风格（photorealistic），自然真实的光影（natural lighting），细腻自然的肤色与材质纹理";
        String prompt = "真人写实摄影风格（photorealistic），自然真实的光影（natural lighting），后续内容";
        assertTrue(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(prompt, imagePrompt));
    }

    @Test
    void isImagePromptAlreadyIncluded_shortImagePrompt_headThirdAsFingerprint() {
        // imagePrompt "高质量画面"，前 30% = "高" (length=5, 5/3=1, Math.max(1,20)=20, substring(0,5)="高质量画面")
        String imagePrompt = "高质量画面";
        String prompt = "高质量画面，一个角色设定图";
        assertTrue(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(prompt, imagePrompt));

        String promptWithout = "一个角色设定图";
        assertFalse(GenerateImageToolExecutor.isImagePromptAlreadyIncluded(promptWithout, imagePrompt));
    }

    @Test
    void promptReferenceIndexBeyondFinalReferencesIsRejected() {
        assertThrows(RuntimeException.class, () ->
                GenerateImageToolExecutor.validatePromptReferenceIndexes(
                        "参考图片1的场景布局，参考图片3的服装", List.of("scene", "character")));
    }

    @Test
    void promptReferenceIndexesWithinFinalReferencesAreAllowed() {
        assertDoesNotThrow(() ->
                GenerateImageToolExecutor.validatePromptReferenceIndexes(
                        "参考图片 1 的场景布局，参考图片2的角色", List.of("scene", "character")));
    }
}
