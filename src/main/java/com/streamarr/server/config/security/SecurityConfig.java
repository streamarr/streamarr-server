package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
   * Configures the application's Spring Security filter chain and authorization rules.
   *
   * <p>Public endpoints and health checks are permitted, while actuator endpoints, streams, images,
   * and all other requests require their respective authorities. The chain also configures JWT
   * authentication, live-identity authorization, cookie-scoped CSRF protection, and custom
   * authentication and access-denied handling.
   *
   * @return the configured security filter chain
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

  /**
   * Configures the application's role hierarchy.
   *
   * @return the configured role hierarchy
   */
  @Bean
  static RoleHierarchy roleHierarchy() {
    return ScopeHierarchy.roleHierarchy();
  }

  /**
   * Disables automatic servlet-container registration for the live-identity authorization filter.
   *
   * @param filter the live-identity authorization filter
   * @return the disabled filter registration
   */
  @Bean
  static FilterRegistrationBean<LiveIdentityAuthorizationFilter>
      liveIdentityAuthorizationFilterRegistration(LiveIdentityAuthorizationFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * Creates the CSRF filter configured for cookie-based requests.
   *
   * @return the configured CSRF filter
   */
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
