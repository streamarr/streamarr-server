package com.streamarr.server.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

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
   * <p>CSRF (SPA shape: readable XSRF-TOKEN cookie, Xor handler) protects exactly the
   * cookie-authenticated requests. The filter is wired manually because the resource-server DSL
   * exempts any request its bearer resolver finds a token on — and our resolver reads the access
   * cookie, which is precisely the ambient credential CSRF must cover. Built-in CSRF remains
   * enabled for the framework's normal filter-chain contract; both filters share the cookie SPA
   * shape and the same request matcher.
   */
  @SuppressWarnings("java:S4502")
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    return http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCookieCsrfTokenRequestHandler())
                    .requireCsrfProtectionMatcher(new CookieAuthenticationCsrfMatcher()))
        .addFilterAfter(cookieScopedCsrfFilter(), HeaderWriterFilter.class)
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
