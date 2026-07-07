package com.streamarr.server.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtDecoder jwtDecoder;
  private final JwtIdentityConverter identityConverter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  /**
   * Transitional authorization: permits everything while the auth surface lands; the enforcement
   * flip replaces the permit matrix (GraphQL and images require SCOPE_ACCOUNT). Authentication is
   * already real — presented tokens are decoded, version-checked, and rejected with the machine
   * codes clients key on.
   *
   * <p>CSRF stays disabled only while nothing in the suite authenticates by cookie; the CSRF slice
   * of this PR replaces it with csrf.spa() and a cookie-scoped protection matcher.
   */
  @SuppressWarnings("java:S4502")
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenResolver(new StreamarrBearerTokenResolver())
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder).jwtAuthenticationConverter(identityConverter)))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .build();
  }
}
