package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("UnitTest")
@DisplayName("Canonical Base URL Configuration Tests")
class CanonicalBaseUrlConfigurationTest {

  private final CanonicalBaseUrlConfiguration configuration = new CanonicalBaseUrlConfiguration();

  @Test
  @DisplayName("Should fail startup when insecure http is allowed outside development")
  void shouldFailStartupWhenInsecureHttpAllowedOutsideDevelopment() {
    var properties =
        StreamarrServerProperties.builder()
            .baseUrl("http://home.example.com")
            .allowInsecureHttp(true)
            .build();

    // Both gates or neither: a single flag is one typo away from serving credentials in cleartext.
    assertThatThrownBy(() -> configuration.canonicalBaseUrl(properties, environmentWith()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("development or test profile");
  }

  @Test
  @DisplayName("Should allow insecure http when the flag and a development profile agree")
  void shouldAllowInsecureHttpWhenFlagAndDevelopmentProfileAgree() {
    var properties =
        StreamarrServerProperties.builder()
            .baseUrl("http://home.example.com")
            .allowInsecureHttp(true)
            .build();

    var baseUrl = configuration.canonicalBaseUrl(properties, environmentWith("dev"));

    assertThat(baseUrl.value()).isEqualTo("http://home.example.com");
  }

  @Test
  @DisplayName("Should refuse cleartext when a development profile is active without the flag")
  void shouldRefuseCleartextWhenDevelopmentProfileActiveWithoutFlag() {
    var properties = StreamarrServerProperties.builder().baseUrl("http://home.example.com").build();

    assertThatThrownBy(() -> configuration.canonicalBaseUrl(properties, environmentWith("dev")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must use https");
  }

  @Test
  @DisplayName("Should report an absent base URL when nothing is configured")
  void shouldReportAbsentBaseUrlWhenNothingConfigured() {
    var baseUrl =
        configuration.canonicalBaseUrl(
            StreamarrServerProperties.builder().build(), environmentWith());

    assertThat(baseUrl.isConfigured()).isFalse();
  }

  private static MockEnvironment environmentWith(String... profiles) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }
}
