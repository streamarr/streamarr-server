package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
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
  private final AuthTokenProperties tokenProperties;
  private final AuthCookiePolicy cookiePolicy;
  private final LiveIdentityAuthorizationFilter liveIdentityAuthorizationFilter;

  /**
   * The permit matrix: pre-auth endpoints (shared with the bearer resolver, so the two lists cannot
   * drift) and health stay open; non-health actuator endpoints are refused for everyone; streams
   * demand SCOPE_PLAYBACK carried in the playback-URL token (outside the hierarchy); images demand
   * SCOPE_PROFILE; everything else — GraphQL including introspection and future surfaces — demands
   * SCOPE_ACCOUNT, which household and profile tokens satisfy through the scope hierarchy.
   *
   * <p>CSRF (SPA shape: readable host-bound cookie, Xor rendering, header-only submission) protects
   * unsafe requests from the Streamarr cookie-carrying browser population. Explicitly insecure
   * development uses an unprefixed fallback. The filter is wired manually because the
   * resource-server DSL exempts any request its bearer resolver finds a token on — and our resolver
   * reads the access cookie, which is precisely the ambient credential CSRF must cover.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.removeConfigurer(CsrfConfigurer.class);
    return http.addFilterAfter(cookieScopedCsrfFilter(), HeaderWriterFilter.class)
        .addFilterAfter(liveIdentityAuthorizationFilter, BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        StreamarrBearerTokenResolver.UNAUTHENTICATED_PATHS.toArray(String[]::new))
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
        // Streamarr revokes refresh families through POST /api/auth/logout; expose no framework
        // logout endpoint that could imply revocation without performing it.
        .logout(AbstractHttpConfigurer::disable)
        .build();
  }

  @Bean
  static RoleHierarchy roleHierarchy() {
    return ScopeHierarchy.roleHierarchy();
  }

  private CsrfFilter cookieScopedCsrfFilter() {
    var tokenRepository =
        new StreamarrCookieCsrfTokenRepository(tokenProperties.refreshTokenTtl(), cookiePolicy);

    var filter = new CsrfFilter(tokenRepository);
    filter.setRequireCsrfProtectionMatcher(new StreamarrCookieCsrfMatcher(cookiePolicy));
    filter.setRequestHandler(new SpaCookieCsrfTokenRequestHandler());
    filter.setAccessDeniedHandler(accessDeniedHandler);
    return filter;
  }
}
