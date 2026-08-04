package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.services.auth.TokenScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

@Tag("UnitTest")
@DisplayName("Security Configuration Tests")
class SecurityConfigTest {

  private static final Duration TEST_REFRESH_TOKEN_TTL = Duration.ofHours(7);

  private AnnotationConfigWebApplicationContext context;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigWebApplicationContext();
    context.register(TestSecurityConfiguration.class);
    context.refresh();

    var securityFilter = context.getBean("springSecurityFilterChain", Filter.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CsrfProbeController())
            .apply(springSecurity(securityFilter))
            .build();
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  @Test
  @DisplayName("Should require csrf when an auth cookie rides an unsafe request")
  void shouldRequireCsrfWhenAuthCookieRidesUnsafeRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should not require csrf when no Streamarr cookie is present")
  void shouldNotRequireCsrfWhenNoStreamarrCookieIsPresent() throws Exception {
    mockMvc.perform(post("/api/auth/login")).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Should require csrf when a bearer credential is ignored on login")
  void shouldRequireCsrfWhenBearerCredentialIsIgnoredOnLogin() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"))
                .header("Authorization", "Bearer opaque-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should require csrf when a differently cased bearer credential is ignored")
  void shouldRequireCsrfWhenDifferentlyCasedBearerCredentialIsIgnored() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"))
                .header("Authorization", "bearer opaque-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should require csrf when bearer value blank")
  void shouldRequireCsrfWhenBearerValueBlank() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"))
                .header("Authorization", "Bearer   "))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should require csrf when authorization scheme not bearer")
  void shouldRequireCsrfWhenAuthorizationSchemeNotBearer() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"))
                .header("Authorization", "Basic dXNlcjpwYXNz"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should require csrf when only refresh cookie present")
  void shouldRequireCsrfWhenOnlyRefreshCookiePresent() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, "ambient-credential")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should accept cookie authenticated request when csrf cookie is echoed")
  void shouldAcceptCookieAuthenticatedRequestWhenCsrfCookieIsEchoed() throws Exception {
    var tokenCookie =
        mockMvc
            .perform(get("/api/auth/login"))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.CSRF_COOKIE);

    assertThat(tokenCookie).isNotNull();
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"), tokenCookie)
                .header(AuthCookies.CSRF_HEADER, tokenCookie.getValue()))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Should wire refresh token lifetime into csrf cookie")
  void shouldWireRefreshTokenLifetimeIntoCsrfCookie() throws Exception {
    var tokenCookie =
        mockMvc
            .perform(get("/api/auth/login"))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.CSRF_COOKIE);

    assertThat(tokenCookie).isNotNull();
    assertThat(tokenCookie.getMaxAge()).isEqualTo(TEST_REFRESH_TOKEN_TTL.toSeconds());
  }

  @Test
  @DisplayName("Should reject a cookie authenticated request when the csrf token is wrong")
  void shouldRejectCookieAuthenticatedRequestWhenCsrfTokenIsWrong() throws Exception {
    var tokenCookie =
        mockMvc
            .perform(get("/api/auth/login"))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.CSRF_COOKIE);
    assertThat(tokenCookie).isNotNull();

    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"), tokenCookie)
                .header(AuthCookies.CSRF_HEADER, "wrong-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Should reject csrf before decoding an access cookie on a protected route")
  void shouldRejectCsrfBeforeDecodingAccessCookieOnProtectedRoute() throws Exception {
    mockMvc
        .perform(
            post("/graphql").cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should not expose the framework default logout endpoint")
  void shouldNotExposeFrameworkDefaultLogoutEndpoint() throws Exception {
    mockMvc
        .perform(
            get("/logout")
                .with(
                    user("test")
                        .authorities(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority()))))
        .andExpect(status().isNotFound());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @Import({
    SecurityConfig.class,
    JwtIdentityConverter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
  })
  static class TestSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
      return _ -> {
        throw new AssertionError("CSRF must reject ambient cookies before token decoding");
      };
    }

    @Bean
    AuthTokenProperties authTokenProperties() {
      return AuthTokenProperties.builder()
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(TEST_REFRESH_TOKEN_TTL)
          .rotationGrace(Duration.ofSeconds(30))
          .build();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @RestController
  static class CsrfProbeController {

    @GetMapping("/api/auth/login")
    ResponseEntity<Void> get() {
      return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<Void> post() {
      return ResponseEntity.noContent().build();
    }

    @PostMapping("/graphql")
    ResponseEntity<Void> graphQl() {
      return ResponseEntity.noContent().build();
    }
  }
}
