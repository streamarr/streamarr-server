package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

@Tag("UnitTest")
@DisplayName("Security Configuration Tests")
class SecurityConfigTest {

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
  @DisplayName("Should not require csrf when no auth cookie is present")
  void shouldNotRequireCsrfWhenNoAuthCookieIsPresent() throws Exception {
    mockMvc.perform(post("/api/auth/login")).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Should accept cookie authenticated request when csrf cookie is echoed")
  void shouldAcceptCookieAuthenticatedRequestWhenCsrfCookieIsEchoed() throws Exception {
    var tokenCookie =
        mockMvc.perform(get("/api/auth/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");

    assertThat(tokenCookie).isNotNull();
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "ambient-credential"), tokenCookie)
                .header("X-XSRF-TOKEN", tokenCookie.getValue()))
        .andExpect(status().isNoContent());
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
  }

  @RestController
  static class CsrfProbeController {

    @GetMapping("/api/auth/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void get() {}

    @PostMapping("/api/auth/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void post() {}
  }
}
