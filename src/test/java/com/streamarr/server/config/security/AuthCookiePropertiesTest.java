package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@Tag("UnitTest")
@DisplayName("Auth Cookie Properties Tests")
class AuthCookiePropertiesTest {

  @Test
  @DisplayName("Should opt in when auth.cookies.allow-insecure is set")
  void shouldOptInWhenAllowInsecureIsSet() {
    var properties = bind(Map.of("auth.cookies.allow-insecure", "true"));

    assertThat(properties.allowInsecure()).isTrue();
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
