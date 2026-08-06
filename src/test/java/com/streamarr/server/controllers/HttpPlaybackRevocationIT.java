package com.streamarr.server.controllers;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that the HTTP boundary enforces live playback authority through the real service and
 * PostgreSQL. Unlike {@code StreamControllerIT}, no streaming beans are stubbed — this drives
 * {@code StreamController} → {@code HlsStreamingService.accessSession} → {@code
 * LivePlaybackAuthorityGate} → the live authority query.
 */
@Tag("IntegrationTest")
@DisplayName("HTTP Playback Revocation Integration Tests")
class HttpPlaybackRevocationIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;
  @Autowired private JwtDecoder jwtDecoder;

  private AuthTestSupport.TestIdentity identity;
  private StreamSession streamSession;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    identity.session().setActiveHouseholdId(identity.household().getId());
    identity.session().setActiveProfileId(identity.profile().getId());
    authSessionRepository.updateSelectionIfLive(identity.session(), Instant.now());
    streamSession = defaultSessionBuilder().authority(authority()).build();
    runtimeRegistry.save(streamSession);
  }

  @AfterEach
  void tearDown() {
    runtimeRegistry.removeById(streamSession.getSessionId());
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should deny the playlist over HTTP when logout revokes the auth session")
  void shouldDenyPlaylistOverHttpWhenLogoutRevokesAuthSession() throws Exception {
    var token = playbackToken();

    mockMvc
        .perform(
            get("/api/stream/{id}/multivariant.m3u8", streamSession.getSessionId())
                .param("t", token))
        .andExpect(status().isOk());

    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, Instant.now());

    mockMvc
        .perform(
            get("/api/stream/{id}/multivariant.m3u8", streamSession.getSessionId())
                .param("t", token))
        .andExpect(status().isNotFound());
  }

  private String playbackToken() {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    return playbackTokenIssuer
        .issue(authenticatedIdentity, streamSession, Duration.ofHours(1))
        .value();
  }

  private PlaybackAuthority authority() {
    return PlaybackAuthority.builder()
        .authSessionId(identity.session().getId())
        .accountId(identity.account().getId())
        .householdId(identity.household().getId())
        .profileId(identity.profile().getId())
        .build();
  }
}
