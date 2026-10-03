package com.tonyhe.fusion.service.system;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.entity.system.SystemConfig;
import com.tonyhe.fusion.mapper.system.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统配置服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;

    /**
     * 获取配置值
     */
    @Cacheable(value = "systemConfig", key = "#key", unless = "#result == null")
    public String getValue(String key) {
        SystemConfig config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key)
                        .last("LIMIT 1"));
        return config != null ? config.getConfigValue() : null;
    }

    /**
     * 设置配置值（不存在则创建）
     */
    @CacheEvict(value = "systemConfig", key = "#key")
    public void setValue(String key, String value) {
        SystemConfig existing = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setConfigValue(value);
            systemConfigMapper.updateById(existing);
        } else {
            SystemConfig config = SystemConfig.builder()
                    .configKey(key)
                    .configValue(value)
                    .build();
            systemConfigMapper.insert(config);
        }
    }

    /**
     * 获取所有配置
     */
    public List<SystemConfig> getAll() {
        return systemConfigMapper.selectList(
                new LambdaQueryWrapper<SystemConfig>()
                        .orderByAsc(SystemConfig::getConfigKey));
    }

    /**
     * 获取站点访问域名
     */
    public String getSiteBaseUrl() {
        String url = getValue("site_base_url");
        // 去掉末尾斜杠
        if (StrUtil.isNotBlank(url) && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 获取布尔配置值。
     */
    public boolean getBooleanValue(String key, boolean defaultValue) {
        String value = getValue(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 是否允许公开注册。
     */
    public boolean isRegistrationEnabled() {
        return getBooleanValue("allow_register", false);
    }

    /**
     * 设置是否允许公开注册。
     */
    public void setRegistrationEnabled(boolean enabled) {
        setValue("allow_register", Boolean.toString(enabled));
    }

    /**
     * 将相对路径解析为完整的公网可访问 URL
     * <p>
     * 1. 已是完整 URL (http/https) → 直接返回
    * 2. 预设画风图 (/art-styles/** 或 /api/art-styles/**) 统一映射到后端静态资源端点 /api/art-styles/**
    * 3. 其他相对路径在有 site_base_url 时直接拼接
    * 4. 没有 site_base_url 时，预设画风图返回相对 API 路径，兼容本地直连后端
     */
    public String resolvePublicUrl(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return null;
        }
        // 已经是完整 URL（如 OSS 直链）
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath;
        }
        String normalizedPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        if (presetArtStyleResourceResolver.isPresetArtStylePath(normalizedPath)) {
            String apiPath = presetArtStyleResourceResolver.toApiPath(normalizedPath);
            if (StrUtil.isBlank(apiPath)) {
                return normalizedPath;
            }
            String siteBaseUrl = getSiteBaseUrl();
            if (StrUtil.isBlank(siteBaseUrl)) {
                return apiPath;
            }
            return buildApiUrl(siteBaseUrl, apiPath);
        }
        // 拼接站点域名
        String siteBaseUrl = getSiteBaseUrl();
        if (StrUtil.isNotBlank(siteBaseUrl)) {
            return siteBaseUrl + normalizedPath;
        }
        return null;
    }

    private String buildApiUrl(String siteBaseUrl, String apiPath) {
        if (StrUtil.isBlank(siteBaseUrl)) {
            return apiPath;
        }
        if (siteBaseUrl.endsWith("/api") && apiPath.startsWith("/api/")) {
            return siteBaseUrl + apiPath.substring("/api".length());
        }
        return siteBaseUrl + apiPath;
    }
}
