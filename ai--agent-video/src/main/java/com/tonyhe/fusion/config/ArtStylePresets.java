package com.tonyhe.fusion.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 预设画风注册表
 * <p>
 * 静态定义所有预设画风配置，包括名称、描述、英文提示词和参考图路径。
 * 预设参考图存放在 resources/static/art-styles/ 目录下，并统一通过 /api/art-styles/** 对外暴露。
 */
public class ArtStylePresets {

    private static final Map<String, ArtStylePreset> PRESETS = new LinkedHashMap<>();

    static {
        register(ArtStylePreset.builder()
                .key("cartoon_3d")
                .name("卡通3D")
                .description("3D卡通渲染风格，圆润简洁的造型比例，柔和均匀的光照，细腻材质纹理，明快干净的色彩，画面整洁通透")
                .imagePrompt("3D卡通渲染风格（3D cartoon render），圆润简洁的造型比例，柔和均匀的漫射光照，细腻的材质与毛发纹理（subsurface scattering），明快干净的色彩搭配，高精度渲染（octane render quality），画面整洁通透，边缘光滑流畅，无粗糙噪点，高质量精细画面")
                .referencePrompt("仅参考图片{index}的3D卡通造型比例、柔和漫射光照和明快干净的色彩搭配，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/cartoon_3d.jpg"))
                .build());

        register(ArtStylePreset.builder()
                .key("cg")
                .name("CG动画")
                .description("写实向3D CG渲染风格，高精度模型，真实光影与材质质感，细腻表面细节，清晰锐利画面")
                .imagePrompt("写实向3D CG渲染风格（high quality CG render），高精度模型，基于物理的材质质感（PBR materials），真实自然的光影追踪（ray tracing lighting），细腻的皮肤与表面细节（fine skin texture），清晰锐利的画面（sharp focus），电影级渲染质量（cinematic render quality），体积光与全局光照，体积云渲染（volumetric clouds with realistic density, depth and soft light scattering），高质量精细画面画面调整平滑，全域降噪，画质干净通透，无细碎杂点，弱化颗粒感，整体质感统一强调大色块叙事与整体轮廓，不要高频纹理，不要脏污颗粒，不要密集小装饰，边缘清晰利落，表面干净，画面呼吸感强，一目了然。")
                .referencePrompt("仅参考图片{index}的CG渲染精度、光影追踪质感、PBR材质细节和轮廓光表现力，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/cg1.png", "/api/art-styles/cg2.png"))
                .build());

        register(ArtStylePreset.builder()
                .key("realistic")
                .name("写实")
                .description("真人写实摄影风格，自然光影，真实材质与肤色细节，浅景深，电影镜头质感")
                .imagePrompt("真人写实摄影风格（photorealistic），自然真实的光影（natural lighting），细腻自然的肤色与材质纹理（detailed skin texture），大光圈浅景深（shallow depth of field, bokeh），电影镜头质感（cinematic lens, anamorphic lens flare），真实世界细节还原，8K级画质，画面干净通透，全域降噪，无细碎杂点，弱化颗粒感，皮肤表面平滑自然，不要脏污颗粒，不要高频噪点，不要过度锐化，不要毛孔级细节，不要过度皮肤纹理，整体质感统一，高质量精细画面")
                .referencePrompt("仅参考图片{index}的写实摄影光影质感、肤色质感、浅景深虚化和胶片色调，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/realistic_1.jpg", "/api/art-styles/realistic_2.jpg"))
                .build());

        register(ArtStylePreset.builder()
                .key("anime_jp")
                .name("日漫")
                .description("2D日式动画风格，赛璐璐上色，清晰干净线稿，平涂色块与硬边缘阴影，高饱和纯净色彩")
                .imagePrompt("2D日式动画风格（2D anime style），赛璐璐上色（cel shading），清晰干净的线稿（clean crisp lineart），平涂色块与硬边缘阴影（hard-edged shadows），高饱和纯净色彩（vibrant pure colors），细腻的面部表情刻画，柔和的渐变天空，动画截图质感（anime screenshot quality），高质量精细画面，无粗糙线条")
                .referencePrompt("仅参考图片{index}的赛璐璐上色方式、干净线稿风格、硬边缘阴影画法和高饱和纯净色彩搭配，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/anime_jp.jpg"))
                .build());

        register(ArtStylePreset.builder()
                .key("anime_cn")
                .name("国漫")
                .description("现代国风3D动画风格，3D渲染+国风美术，细腻材质与光影，飘逸衣袂动态，清雅配色")
                .imagePrompt("现代国风3D动画风格（modern Chinese 3D animation style），3D渲染（3D render），基于物理的材质质感（PBR materials），细腻的皮肤与织物纹理，柔和自然的光影过渡（soft natural lighting），水彩晕染质感的背景（watercolor wash background），清雅配色（elegant muted palette），精致的面部五官刻画，衣袂飘飘的动态质感，类似《天官赐福》《魔道祖师》的3D画风质感，高质量精细画面，无粗糙噪点")
                .referencePrompt("仅参考图片{index}的国风3D渲染质感、细腻材质纹理、柔和光影过渡、水彩晕染背景画法和清雅配色方案，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/anime_cn_1.png", "/api/art-styles/anime_cn_2.png"))
                .build());

        register(ArtStylePreset.builder()
                .key("comic_us")
                .name("美漫")
                .description("美式漫画风格，粗黑有力轮廓线，高对比度色彩，半调网点阴影，硬朗色块分割，平面印刷质感")
                .imagePrompt("美式漫画风格（American comic book style），粗黑有力的轮廓线（bold black outlines），高对比度色彩（high contrast colors），半调网点阴影（halftone dots shading），硬朗的色块分割（hard color blocks），平面印刷质感（flat print quality），动态夸张的构图，鲜明的光影对比，高质量精细画面")
                .referencePrompt("仅参考图片{index}的粗黑轮廓线风格、高对比度色彩搭配、半调网点阴影画法和硬朗色块分割方式，绝不参考其中的任何具体角色、物品和构图，")
                .referenceImagePaths(Arrays.asList("/api/art-styles/comic_us.jpg"))
                .build());
    }

    private static void register(ArtStylePreset preset) {
        PRESETS.put(preset.getKey(), preset);
    }

    /**
     * 根据 key 获取预设画风
     */
    public static ArtStylePreset getByKey(String key) {
        return PRESETS.get(key);
    }

    /**
     * 获取所有预设画风列表
     */
    public static List<ArtStylePreset> getAll() {
        return new ArrayList<>(PRESETS.values());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtStylePreset {
        /** 画风唯一标识 */
        private String key;
        /** 画风显示名称 */
        private String name;
        /** 画风描述（中文，用于视频生成 prompt 前缀） */
        private String description;
        /** 画风英文提示词（用于图片生成） */
        private String imagePrompt;
        /** 参考图引用提示词（告诉图片模型从参考图中参考什么，{index} 会被替换为实际图片编号） */
        private String referencePrompt;
        /** 参考图相对路径列表（如 /api/art-styles/cg.jpg），支持多张 */
        private List<String> referenceImagePaths;
        /** 参考图公网 URL 列表（上传到 OSS 后由系统配置填充），与 referenceImagePaths 一一对应 */
        private List<String> referenceImagePublicUrls;
    }
}
