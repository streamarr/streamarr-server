package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Jwks Endpoint Integration Tests")
class JwksEndpointIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should serve public verification keys without private material")
  void shouldServePublicVerificationKeysWithoutPrivateMaterial() throws Exception {
    var body =
        mockMvc
            .perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andExpect(jsonPath("$.keys").isArray())
            .andExpect(jsonPath("$.keys[0].kty").value("EC"))
            .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
            .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
            .andExpect(jsonPath("$.keys[0].use").value("sig"))
            .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The one property that must never regress: signing material never leaves the server.
    assertThat(body).doesNotContain("\"d\"");
  }

  @Test
  @DisplayName("Should serve keys when stale access cookie rides the request")
  void shouldServeKeysWhenStaleAccessCookieRidesTheRequest() throws Exception {
    identity = authTestSupport.createIdentity();

    // Browsers attach the Path=/ access cookie to same-origin fetches; an expired one must not
    // 401 a public-key fetch (same trap as the refresh endpoint).
    mockMvc
        .perform(
            get("/.well-known/jwks.json")
                .cookie(
                    new Cookie("streamarr_access", authTestSupport.expiredProfileBearer(identity))))
        .andExpect(status().isOk());
  }
}
