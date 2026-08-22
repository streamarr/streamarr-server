package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Live Playback Access Integration Tests")
class LivePlaybackAccessIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;

  private AuthTestSupport.TestIdentity identity;
  private StreamSession streamSession;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    authenticateAsIdentity();
    streamSession = defaultSessionBuilder().authority(authority()).build();
    runtimeRegistry.save(streamSession);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    runtimeRegistry.removeById(streamSession.getSessionId());
    authTestSupport.deleteIdentity(identity);
  }

  /** The playback gate decides for the request's identity — segment requests arrive signed. */
  private void authenticateAsIdentity() {
    var authenticated =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(identity)));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                authenticated,
                null,
                List.of(new SimpleGrantedAuthority(authenticated.scope().authority()))));
  }

  @Test
  @DisplayName("Should deny an existing runtime session when logout commits")
  void shouldDenyExistingRuntimeSessionWhenLogoutCommits() {
    assertThat(streamingService.accessSession(playbackRequest(streamSession))).isPresent();

    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, Instant.now());

    assertThat(streamingService.accessSession(playbackRequest(streamSession))).isEmpty();
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
