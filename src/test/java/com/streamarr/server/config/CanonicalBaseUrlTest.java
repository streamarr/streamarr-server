package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Canonical Base URL Tests")
class CanonicalBaseUrlTest {

  @ParameterizedTest(name = "Should report absent for [{index}]")
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("Should treat the value as explicitly absent when the base URL is missing")
  void shouldTreatValueAsExplicitlyAbsentWhenBaseUrlMissing(String rawBaseUrl) {
    assertThat(CanonicalBaseUrl.of(rawBaseUrl, false).isConfigured()).isFalse();
  }

  @Test
  @DisplayName("Should normalize a single trailing slash when the base URL uses https")
  void shouldNormalizeSingleTrailingSlashWhenBaseUrlUsesHttps() {
    assertThat(CanonicalBaseUrl.of("https://home.example.com/", false).value())
        .isEqualTo("https://home.example.com");
  }

  @Test
  @DisplayName("Should join the verification path when the base URL contains a path")
  void shouldJoinVerificationPathWhenBaseUrlContainsPath() {
    assertThat(CanonicalBaseUrl.of("https://home.example.com/streamarr", false).resolve("/link"))
        .isEqualTo("https://home.example.com/streamarr/link");
  }

  @ParameterizedTest(name = "Should reject \"{0}\"")
  @ValueSource(
      strings = {
        // No scheme completion: assuming https would mask an operator typo.
        "home.example.com",
        "//home.example.com",
        "ftp://home.example.com",
        "https://user:pass@home.example.com",
        "https://home.example.com?token=abc",
        "https://home.example.com#fragment",
        "https://home.example.com/a%2Fb",
        "https:///nohost"
      })
  @DisplayName("Should fail startup when the base URL is not a bare endpoint")
  void shouldFailStartupWhenBaseUrlIsNotBareEndpoint(String rawBaseUrl) {
    assertThatThrownBy(() -> CanonicalBaseUrl.of(rawBaseUrl, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STREAMARR_BASE_URL");
  }

  @Test
  @DisplayName("Should refuse cleartext http when insecure transport is not unlocked")
  void shouldRefuseCleartextHttpWhenInsecureTransportNotUnlocked() {
    assertThatThrownBy(() -> CanonicalBaseUrl.of("http://home.example.com", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must use https");
  }

  @Test
  @DisplayName("Should accept cleartext http when insecure transport is unlocked")
  void shouldAcceptCleartextHttpWhenInsecureTransportUnlocked() {
    assertThat(CanonicalBaseUrl.of("http://home.example.com", true).value())
        .isEqualTo("http://home.example.com");
  }

  @Test
  @DisplayName("Should refuse to resolve a URL when no base is configured")
  void shouldRefuseToResolveUrlWhenNoBaseConfigured() {
    var absent = CanonicalBaseUrl.absent();

    assertThatThrownBy(() -> absent.resolve("/link")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Should refuse to resolve a path when it is not absolute")
  void shouldRefuseToResolvePathWhenPathIsNotAbsolute() {
    var baseUrl = CanonicalBaseUrl.of("https://home.example.com", false);

    assertThatThrownBy(() -> baseUrl.resolve("link"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
  }
}
