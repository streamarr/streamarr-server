package com.streamarr.server.config;

import java.util.Arrays;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
public class CanonicalBaseUrlConfiguration {

  private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "development", "test");

  /**
   * Validates the configured base URL at startup: a malformed value fails the application rather
   * than surfacing later as an unreachable pairing link.
   */
  @Bean
  CanonicalBaseUrl canonicalBaseUrl(StreamarrServerProperties properties, Environment environment) {
    var insecureAllowed =
        properties.allowInsecureHttp() && isNonProductionProfileActive(environment);

    if (properties.allowInsecureHttp() && !insecureAllowed) {
      throw new IllegalStateException(
          "STREAMARR_ALLOW_INSECURE_HTTP requires a development or test profile; a single flag is"
              + " one typo away from serving credentials in cleartext.");
    }

    var baseUrl = CanonicalBaseUrl.of(properties.baseUrl(), insecureAllowed);
    warnAboutInsecureTransport(baseUrl, insecureAllowed);

    return baseUrl;
  }

  private static void warnAboutInsecureTransport(
      CanonicalBaseUrl baseUrl, boolean insecureAllowed) {
    if (insecureAllowed && baseUrl.isConfigured() && baseUrl.value().startsWith("http://")) {
      log.warn(
          "Serving credentials over cleartext HTTP at {} — development only; release clients"
              + " reject HTTP endpoints.",
          baseUrl.value());
    }
  }

  private static boolean isNonProductionProfileActive(Environment environment) {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(NON_PRODUCTION_PROFILES::contains);
  }
}
