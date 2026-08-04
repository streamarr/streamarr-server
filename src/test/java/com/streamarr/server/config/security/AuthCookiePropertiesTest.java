package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Auth Cookie Properties Tests")
class AuthCookiePropertiesTest {

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(AuthCookiePropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthCookieProperties.class)
  static class AuthCookiePropertiesConfiguration {}

  @Test
  @DisplayName("Should opt in when auth.cookies.allow-insecure is set")
  void shouldOptInWhenAllowInsecureIsSet() {
    var properties = bind(Map.of("auth.cookies.allow-insecure", "true"));

    assertThat(properties.allowInsecure()).isTrue();
  }

  @Test
  @DisplayName("Should enable insecure cookies when the documented environment variable is true")
  void shouldEnableInsecureCookiesWhenDocumentedEnvironmentVariableIsTrue() {
    CONTEXT_RUNNER
        .withSystemProperties("AUTH_COOKIES_ALLOW_INSECURE=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AuthCookieProperties.class).allowInsecure()).isTrue();
            });
  }

  @Test
  @DisplayName("Should default allow-insecure to false when nothing is configured")
  void shouldDefaultAllowInsecureToFalseWhenNothingIsConfigured() {
    var properties = bind(Map.of());

    assertThat(properties.allowInsecure()).isFalse();
  }

  private static AuthCookieProperties bind(Map<String, Object> configuration) {
    return new Binder(new MapConfigurationPropertySource(configuration))
        .bindOrCreate("auth.cookies", AuthCookieProperties.class);
  }
}
