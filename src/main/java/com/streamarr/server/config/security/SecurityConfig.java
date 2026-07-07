package com.streamarr.server.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  /**
   * Transitional chain: permits everything while the token core lands. The enforcement flip
   * replaces this with the real permit matrix (GraphQL and images require SCOPE_ACCOUNT).
   *
   * <p>CSRF stays disabled only while nothing authenticates by cookie; the auth surface PR replaces
   * this with csrf.spa() and a cookie-scoped protection matcher.
   */
  @SuppressWarnings("java:S4502")
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }
}
