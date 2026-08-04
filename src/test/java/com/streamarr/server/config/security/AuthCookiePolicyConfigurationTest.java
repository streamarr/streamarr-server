package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("UnitTest")
@DisplayName("Auth Cookie Security Configuration Tests")
class AuthCookiePolicyConfigurationTest {

  private final AuthCookiePolicyConfiguration configuration = new AuthCookiePolicyConfiguration();

  @Test
  @DisplayName("Should relax Secure when the flag and a development profile agree")
  void shouldRelaxSecureWhenFlagAndDevelopmentProfileAgree() {
    var security =
        configuration.authCookiePolicy(allowingInsecureCookies(), environmentWith("dev"));

    assertThat(security).isEqualTo(AuthCookiePolicy.INSECURE_DEVELOPMENT);
  }

  @Test
  @DisplayName("Should fail startup when the flag is set without a development profile")
  void shouldFailStartupWhenFlagIsSetWithoutDevelopmentProfile() {
    // Both gates or neither: silently ignoring the flag would leave an operator believing cookies
    // were relaxed, and would arm the relaxation the moment a profile changed.
    var production = environmentWith();
    var properties = allowingInsecureCookies();

    assertThatThrownBy(() -> configuration.authCookiePolicy(properties, production))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("development or test profile");
  }

  @Test
  @DisplayName("Should require Secure when a development profile is active without the flag")
  void shouldRequireSecureWhenDevelopmentProfileIsActiveWithoutFlag() {
    var security =
        configuration.authCookiePolicy(
            AuthCookieProperties.builder().build(), environmentWith("dev"));

    assertThat(security).isEqualTo(AuthCookiePolicy.SECURE);
  }

  @Test
  @DisplayName("Should require Secure when nothing is configured")
  void shouldRequireSecureWhenNothingIsConfigured() {
    var security =
        configuration.authCookiePolicy(AuthCookieProperties.builder().build(), environmentWith());

    assertThat(security).isEqualTo(AuthCookiePolicy.SECURE);
  }

  private static AuthCookieProperties allowingInsecureCookies() {
    return AuthCookieProperties.builder().allowInsecure(true).build();
  }

  private static MockEnvironment environmentWith(String... profiles) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }
}
