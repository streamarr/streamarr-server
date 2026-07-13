package com.streamarr.server.config.security;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/** The permit/deny matrix as executable specification — changes here are contract changes. */
@Tag("IntegrationTest")
@DisplayName("Security Filter Chain Integration Tests")
class SecurityFilterChainIT extends AbstractIntegrationTest {

  private static final String ACCOUNT_QUERY = "{\"query\": \"{ me { accountId } }\"}";

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private JwtDecoder jwtDecoder;

  @Autowired private ApplicationContext applicationContext;

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
        .perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_QUERY))
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
  @DisplayName("Should reject images when account scoped")
  void shouldRejectImagesWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should reject images when household scoped")
  void shouldRejectImagesWhenHouseholdScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .with(bearer(authTestSupport.householdBearer(identity))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should permit images when profile scoped")
  void shouldPermitImagesWhenProfileScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should reject stream endpoints when the playback token is missing")
  void shouldRejectStreamEndpointsWhenPlaybackTokenMissing() throws Exception {
    // Streams demand SCOPE_PLAYBACK carried in the ?t= parameter — headers and cookies never
    // reach them, and API tokens never authorize playback.
    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject graphql when playback scoped")
  void shouldRejectGraphQlWhenPlaybackScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY)
                .with(bearer(playbackBearer(UUID.randomUUID()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should permit requests when targeting auth or health endpoints")
  void shouldPermitRequestsWhenTargetingAuthOrHealthEndpoints() throws Exception {
    mockMvc.perform(get("/api/auth/status")).andExpect(status().isOk());
    // The contract is reachability, not health: a DOWN indicator answers 503, never 401/403.
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                    .isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should serve jwks unauthenticated")
  void shouldServeJwksUnauthenticated() throws Exception {
    // Public verification keys are public: the transcode tier fetches them with no credentials.
    mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should permit health when stale access cookie attached")
  void shouldPermitHealthWhenStaleAccessCookieAttached() throws Exception {
    mockMvc
        .perform(
            get("/actuator/health")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "stale-access-token")))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should deny non-health actuator endpoints when account scoped")
  void shouldDenyNonHealthActuatorEndpointsWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    // Operational surfaces (metrics, info) are not for ordinary accounts; the observability
    // profile exposes them, so the chain refuses everything under /actuator except health.
    mockMvc
        .perform(get("/actuator/metrics").with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should permit graphql when account scoped")
  void shouldPermitGraphQlWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY)
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
                .content(ACCOUNT_QUERY)
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("Should publish scope hierarchy for security auto detection")
  void shouldPublishScopeHierarchyForSecurityAutoDetection() {
    assertThat(applicationContext.getBeanProvider(RoleHierarchy.class).getIfUnique()).isNotNull();
  }

  private String playbackBearer(UUID streamSessionId) {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    var ownedSession =
        StreamSessionFixture.defaultSessionBuilder()
            .sessionId(streamSessionId)
            .authority(authenticatedIdentity.playbackAuthority())
            .build();
    return playbackTokenIssuer
        .issue(authenticatedIdentity, ownedSession, Duration.ofHours(1))
        .value();
  }
}
