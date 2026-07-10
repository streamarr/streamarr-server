package com.streamarr.server.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  /**
   * Transitional chain: permits everything while the token core lands. The enforcement flip
   * replaces this with the real permit matrix (GraphQL and images require SCOPE_ACCOUNT).
   *
   * <p>CSRF protects unsafe requests carrying the auth-cookie names reserved for the next PR while
   * leaving the current non-cookie API behavior unchanged.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    return http.csrf(
            csrf -> csrf.requireCsrfProtectionMatcher(new CookieAuthenticationCsrfMatcher()))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }
}
