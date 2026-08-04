package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Insecure Auth Cookie Integration Tests")
@SpringBootTest(properties = "auth.cookies.allow-insecure=true")
class AuthInsecureCookieIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Test
  @DisplayName("Should omit only Secure when insecure cookies are enabled in a test profile")
  void shouldOmitOnlySecureWhenInsecureCookiesEnabledInTestProfile() throws Exception {
    var identity = authTestSupport.createIdentity();

    try {
      var response =
          mockMvc
              .perform(
                  post("/api/auth/login")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {"email": "%s", "password": "%s", "deviceName": "it-device", \
                          "cookieMode": true}
                          """
                              .formatted(identity.account().getEmail(), AuthTestSupport.PASSWORD)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();

      assertInsecureAuthCookie(response.getCookie("streamarr_access"));
      assertInsecureAuthCookie(response.getCookie("streamarr_refresh"));
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }

  private static void assertInsecureAuthCookie(Cookie cookie) {
    assertThat(cookie).isNotNull();
    assertThat(cookie.getSecure()).isFalse();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
  }
}
