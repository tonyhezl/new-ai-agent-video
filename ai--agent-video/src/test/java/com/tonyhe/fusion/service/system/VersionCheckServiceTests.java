package com.tonyhe.fusion.service.system;

import com.tonyhe.fusion.controller.system.vo.VersionInfoRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCheckServiceTests {

    @Test
    void sourceDeliveryHasNoExternalReleaseLinksOrUpgradeReminder() {
        Properties properties = new Properties();
        properties.setProperty("version", "0.6.3");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");
        VersionCheckService service = new VersionCheckService(new BuildProperties(properties), environment);

        VersionInfoRespVO result = service.getVersionInfo(true);

        assertThat(result.getCurrentVersion()).isEqualTo("0.6.3");
        assertThat(result.getSource()).isEqualTo("local");
        assertThat(result.getUpdateAvailable()).isFalse();
        assertThat(result.getComparisonEnabled()).isFalse();
        assertThat(result.getLatestReleaseUrl()).isNull();
        assertThat(result.getReleaseUrl()).isNull();
        assertThat(result.getTagUrl()).isNull();
        assertThat(result.getMessage()).contains("独立源码部署");
    }

    @Test
    void enabledCheckWithMissingChannelReturnsConfigurationMessage() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.version-check.enabled", "true");
        VersionCheckService service = new VersionCheckService(null, environment);

        VersionInfoRespVO result = service.getVersionInfo(true);

        assertThat(result.getCheckSucceeded()).isFalse();
        assertThat(result.getUpdateAvailable()).isFalse();
        assertThat(result.getMessage()).contains("配置不完整");
    }

    @Test
    void malformedConfiguredApiUrlReturnsFailureWithoutThrowing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.version-check.enabled", "true")
                .withProperty("app.version-check.releases-api-url", "invalid-url")
                .withProperty("app.version-check.releases-page-url", "https://example.com/releases")
                .withProperty("app.version-check.backend-image-repository", "buyer/backend")
                .withProperty("app.version-check.frontend-image-repository", "buyer/frontend");
        VersionCheckService service = new VersionCheckService(null, environment);

        VersionInfoRespVO result = service.getVersionInfo(true);

        assertThat(result.getCheckSucceeded()).isFalse();
        assertThat(result.getMessage()).contains("地址格式不正确");
        assertThat(result.getReleaseUrl()).isEqualTo("https://example.com/releases");
    }
}
