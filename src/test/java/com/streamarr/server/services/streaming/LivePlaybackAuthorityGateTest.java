package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Live Playback Authority Gate Tests")
class LivePlaybackAuthorityGateTest {

  /**
   * Authority unreadable must mean playback denied. This pins the fail-closed contract so a future
   * "resilience" change (catch-and-allow, cached last-known-good) cannot merge green.
   */
  @Test
  @DisplayName("Should propagate repository failure instead of allowing playback")
  void shouldPropagateRepositoryFailureInsteadOfAllowingPlayback() {
    var repository =
        new FakeAuthSessionRepository() {
          @Override
          public boolean hasLivePlaybackAuthority(PlaybackAuthority authority) {
            throw new DataAccessResourceFailureException("database unavailable");
          }
        };
    var gate = new LivePlaybackAuthorityGate(repository);
    var authority = defaultPlaybackAuthorityBuilder().build();

    assertThatThrownBy(() -> gate.allows(authority)).isInstanceOf(DataAccessException.class);
  }
}
