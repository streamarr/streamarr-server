package com.streamarr.server.graphql;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("GraphQL Authentication Integration Tests")
class GraphQlAuthenticationIT extends AbstractIntegrationTest {

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
  @DisplayName("Should return expired token code when access token expired")
  void shouldReturnExpiredTokenCodeWhenAccessTokenExpired() throws Exception {
    identity = authTestSupport.createIdentity();

    // The refresh-and-retry signal at the GraphQL surface: an HTTP 401 with EXPIRED_TOKEN,
    // never a 200-with-errors — the service worker keys on exactly this.
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\": \"{ libraries { id } }\"}")
                .with(bearer(authTestSupport.expiredProfileBearer(identity))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"));
  }
}
