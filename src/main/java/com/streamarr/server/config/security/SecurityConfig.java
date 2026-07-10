package com.streamarr.server.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

@Configuration
public class SecurityConfig {

  /**
   * Transitional chain: permits everything while the token core lands. The enforcement flip
   * replaces this with the real permit matrix (GraphQL and images require SCOPE_ACCOUNT).
   *
   * <p>A cookie-scoped filter protects unsafe requests carrying the auth-cookie names reserved for
   * the next PR while leaving the current non-cookie API behavior unchanged.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.removeConfigurer(CsrfConfigurer.class);
    return http.addFilterAfter(cookieScopedCsrfFilter(), HeaderWriterFilter.class)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }

  @SuppressWarnings("java:S3330")
  private CsrfFilter cookieScopedCsrfFilter() {
    var filter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
    filter.setRequireCsrfProtectionMatcher(new CookieAuthenticationCsrfMatcher());
    filter.setRequestHandler(new SpaCookieCsrfTokenRequestHandler());
    return filter;
  }
}
