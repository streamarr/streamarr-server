package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Live Playback Access Integration Tests")
class LivePlaybackAccessIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private AuthTestSupport authTestSupport;

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
  @DisplayName("Should deny an existing runtime session after logout commits")
  void shouldDenyExistingRuntimeSessionAfterLogoutCommits() {
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
