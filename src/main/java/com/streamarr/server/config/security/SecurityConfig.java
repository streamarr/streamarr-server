package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtDecoder jwtDecoder;
  private final JwtIdentityConverter identityConverter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  /**
   * The permit matrix: pre-auth endpoints and health stay open; non-health actuator endpoints are
   * refused for everyone; streams demand SCOPE_PLAYBACK carried in the playback-URL token (outside
   * the hierarchy); images demand SCOPE_PROFILE; everything else — GraphQL including introspection
   * and future surfaces — demands SCOPE_ACCOUNT, which household and profile tokens satisfy through
   * the scope hierarchy.
   *
   * <p>CSRF (SPA shape: readable XSRF-TOKEN cookie, Xor handler) protects exactly the
   * cookie-authenticated requests. The filter is wired manually because the resource-server DSL
   * exempts any request its bearer resolver finds a token on — and our resolver reads the access
   * cookie, which is precisely the ambient credential CSRF must cover.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    return http.csrf(AbstractHttpConfigurer::disable)
        .addFilterAfter(cookieScopedCsrfFilter(), HeaderWriterFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/api/auth/status",
                        "/api/auth/setup",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/.well-known/jwks.json")
                    .permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .requestMatchers(SecurityRequestMatchers.STREAM_PATHS)
                    .hasAuthority(TokenScope.PLAYBACK.authority())
                    .requestMatchers("/api/images/**")
                    .hasAuthority(TokenScope.PROFILE.authority())
                    .anyRequest()
                    .hasAuthority(TokenScope.ACCOUNT.authority()))
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

  @Bean
  static RoleHierarchy roleHierarchy() {
    return ScopeHierarchy.roleHierarchy();
  }

  // The XSRF-TOKEN cookie is deliberately script-readable (S3330): its whole purpose is the
  // double-submit echo — the page reads it and sends X-XSRF-TOKEN. It is not a credential; both
  // auth cookies remain httpOnly.
  @SuppressWarnings("java:S3330")
  private CsrfFilter cookieScopedCsrfFilter() {
    var filter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
    filter.setRequireCsrfProtectionMatcher(new CookieAuthenticationCsrfMatcher());
    filter.setRequestHandler(new SpaCookieCsrfTokenRequestHandler());
    filter.setAccessDeniedHandler(accessDeniedHandler);
    return filter;
  }
}
