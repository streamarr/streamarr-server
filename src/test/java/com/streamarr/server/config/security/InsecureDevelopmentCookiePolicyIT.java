package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Insecure Development Cookie Policy Integration Tests")
@SpringBootTest(properties = "auth.cookies.allow-insecure=true")
class InsecureDevelopmentCookiePolicyIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("Should issue the unprefixed insecure csrf cookie in development mode")
  void shouldIssueUnprefixedInsecureCsrfCookieInDevelopmentMode() throws Exception {
    var response =
        mockMvc
            .perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var cookie = response.getCookie("XSRF-TOKEN");

    assertThat(cookie).isNotNull();
    assertAll(
        () -> assertThat(cookie.getSecure()).isFalse(),
        () -> assertThat(cookie.getPath()).isEqualTo("/"),
        () -> assertThat(cookie.getDomain()).isNull(),
        () -> assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax"),
        () -> assertThat(response.getCookie("__Host-XSRF-TOKEN")).isNull());
  }

  @Test
  @DisplayName("Should require the csrf header for an unprefixed development cookie")
  void shouldRequireCsrfHeaderForUnprefixedDevelopmentCookie() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(new Cookie("XSRF-TOKEN", "browser-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "nobody@example.com", "password": "wrong-password", \
                    "deviceName": "Test", "cookieMode": true}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Should accept an echoed unprefixed csrf token in development mode")
  void shouldAcceptEchoedUnprefixedCsrfTokenInDevelopmentMode() throws Exception {
    var csrfCookie =
        mockMvc.perform(get("/api/auth/status")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();

    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(csrfCookie)
                .header(AuthCookies.CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "nobody@example.com", "password": "wrong-password", \
                    "deviceName": "Test", "cookieMode": true}
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }
}
