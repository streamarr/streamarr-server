package com.streamarr.server.config.security;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The permit/deny matrix as executable specification — changes here are contract changes. */
@Tag("IntegrationTest")
@DisplayName("Security Filter Chain Integration Tests")
class SecurityFilterChainIT extends AbstractIntegrationTest {

  private static final String LIBRARIES_QUERY = "{\"query\": \"{ libraries { id } }\"}";

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
  @DisplayName("Should reject graphql when unauthenticated")
  void shouldRejectGraphQlWhenUnauthenticated() throws Exception {
    mockMvc
        .perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(LIBRARIES_QUERY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("Should reject images when unauthenticated")
  void shouldRejectImagesWhenUnauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/images/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("Should permit stream endpoints without token")
  void shouldPermitStreamEndpointsWithoutToken() throws Exception {
    // Transitional until playback-URL tokens land (the next PR flips this to SCOPE_PLAYBACK).
    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should permit auth and health endpoints")
  void shouldPermitAuthAndHealthEndpoints() throws Exception {
    mockMvc.perform(get("/api/auth/status")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should permit graphql when account scoped")
  void shouldPermitGraphQlWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LIBRARIES_QUERY)
                .with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("Should permit graphql when profile scoped through hierarchy")
  void shouldPermitGraphQlWhenProfileScopedThroughHierarchy() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LIBRARIES_QUERY)
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }
}
