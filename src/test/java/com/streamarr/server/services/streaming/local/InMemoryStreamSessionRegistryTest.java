package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("In-Memory Stream Session Registry Tests")
class InMemoryStreamSessionRegistryTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");

  private InMemoryStreamSessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new InMemoryStreamSessionRegistry();
  }

  @Test
  @DisplayName("Should return empty when session not found")
  void shouldReturnEmptyWhenSessionNotFound() {
    var found = registry.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("Should remove and return session when removed by id")
  void shouldRemoveAndReturnSessionWhenRemovedById() {
    var session = StreamSessionFixture.buildActiveSession();
    registry.save(session);

    var removed = registry.removeById(session.getSessionId());

    assertThat(removed).isPresent().contains(session);
    assertThat(registry.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove a session when its last access is before the cutoff")
  void shouldRemoveSessionWhenItsLastAccessIsBeforeCutoff() {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(CUTOFF.minusSeconds(1));
    registry.save(session);

    var removed = registry.removeIfLastAccessedBefore(session.getSessionId(), CUTOFF);

    assertThat(removed).isTrue();
    assertThat(registry.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should keep a session when a request touched it at or after the cutoff")
  void shouldKeepSessionWhenRequestTouchedItAtOrAfterCutoff() {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(CUTOFF.minusSeconds(60));
    registry.save(session);
    registry.touch(session.getSessionId(), CUTOFF);

    var removed = registry.removeIfLastAccessedBefore(session.getSessionId(), CUTOFF);

    assertThat(removed).isFalse();
    assertThat(registry.findById(session.getSessionId())).contains(session);
  }

  @Test
  @DisplayName("Should keep a session when a timeline report reached it at or after the cutoff")
  void shouldKeepSessionWhenTimelineReportReachedItAtOrAfterCutoff() {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(Instant.now().minus(Duration.ofHours(1)));
    registry.save(session);
    var cutoff = Instant.now();
    registry.updatePlaybackState(session.getSessionId(), 300, PlaybackState.PLAYING);

    var removed = registry.removeIfLastAccessedBefore(session.getSessionId(), cutoff);

    assertThat(removed).isFalse();
    assertThat(registry.findById(session.getSessionId())).contains(session);
    assertThat(session.getPlaybackSnapshot().positionSeconds()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should not register a session again when a timeline report arrives after removal")
  void shouldNotRegisterSessionAgainWhenTimelineReportArrivesAfterRemoval() {
    var session = StreamSessionFixture.buildActiveSession();
    registry.save(session);
    registry.removeById(session.getSessionId());

    registry.updatePlaybackState(session.getSessionId(), 300, PlaybackState.PLAYING);

    assertThat(registry.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should report nothing removed when the session is not registered")
  void shouldReportNothingRemovedWhenSessionIsNotRegistered() {
    assertThat(registry.removeIfLastAccessedBefore(UUID.randomUUID(), CUTOFF)).isFalse();
  }

  @Test
  @DisplayName("Should track count when sessions are saved and removed")
  void shouldTrackCountWhenSessionsAreSavedAndRemoved() {
    var session1 = StreamSessionFixture.buildActiveSession();
    var session2 = StreamSessionFixture.buildActiveSession();

    assertThat(registry.count()).isZero();

    registry.save(session1);
    assertThat(registry.count()).isEqualTo(1);

    registry.save(session2);
    assertThat(registry.count()).isEqualTo(2);

    registry.removeById(session1.getSessionId());
    assertThat(registry.count()).isEqualTo(1);
  }
}
