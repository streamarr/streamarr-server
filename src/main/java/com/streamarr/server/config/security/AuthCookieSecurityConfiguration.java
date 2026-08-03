package com.streamarr.server.config.security;

import java.util.Arrays;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
public class AuthCookieSecurityConfiguration {

  private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "development", "test");

  /**
   * Resolves the {@code Secure} attribute at startup from two independent gates: the opt-in flag
   * and a development or test profile. Either alone leaves cookies secure, and the flag without a
   * profile fails the application rather than being quietly ignored — an operator who set it would
   * otherwise believe the relaxation was active, and a later profile change would arm it without
   * anyone deciding so.
   */
  @Bean
  AuthCookieSecurity authCookieSecurity(AuthCookieProperties properties, Environment environment) {
    if (!properties.allowInsecure()) {
      return AuthCookieSecurity.SECURE;
    }

    if (!isNonProductionProfileActive(environment)) {
      throw new IllegalStateException(
          "AUTH_COOKIES_ALLOW_INSECURE requires a development or test profile; a single flag is one"
              + " typo away from sending session cookies in cleartext.");
    }

    log.warn(
        "Auth cookies are being issued WITHOUT the Secure attribute so http://localhost works in"
            + " Safari — development only. Session cookies will travel in cleartext and any host on"
            + " the path can replay them.");

    return AuthCookieSecurity.INSECURE_DEVELOPMENT;
  }

  private static boolean isNonProductionProfileActive(Environment environment) {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(NON_PRODUCTION_PROFILES::contains);
  }
}
