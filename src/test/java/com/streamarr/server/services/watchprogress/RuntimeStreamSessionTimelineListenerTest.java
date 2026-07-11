package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.StreamSessionTimelineCommittedEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Runtime Stream Session Timeline Listener Tests")
class RuntimeStreamSessionTimelineListenerTest {

  @Test
  @DisplayName("Should mirror the exact committed timeline into runtime")
  void shouldMirrorExactCommittedTimelineIntoRuntime() {
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var runtimeSession = StreamSessionFixture.defaultSessionBuilder().build();
    var committedAt = Instant.parse("2026-07-11T01:00:00Z");
    runtimeSession.setLastAccessedAt(Instant.parse("2026-07-11T00:00:00Z"));
    runtimeRegistry.save(runtimeSession);
    var listener = new RuntimeStreamSessionTimelineListener(runtimeRegistry);

    listener.onTimelineCommitted(
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(runtimeSession.getSessionId())
            .positionSeconds(420)
            .state(PlaybackState.PAUSED)
            .accessedAt(committedAt)
            .build());

    var snapshot = runtimeSession.getPlaybackSnapshot();
    assertThat(snapshot.positionSeconds()).isEqualTo(420);
    assertThat(snapshot.state()).isEqualTo(PlaybackState.PAUSED);
    assertThat(snapshot.accessedAt()).isEqualTo(committedAt);
  }

  @Test
  @DisplayName("Should not regress runtime when an older committed timeline arrives")
  void shouldNotRegressRuntimeWhenOlderCommittedTimelineArrives() {
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var runtimeSession = StreamSessionFixture.defaultSessionBuilder().build();
    var currentAccess = Instant.parse("2026-07-11T02:00:00Z");
    runtimeSession.setLastAccessedAt(currentAccess.minusSeconds(10));
    runtimeRegistry.save(runtimeSession);
    var listener = new RuntimeStreamSessionTimelineListener(runtimeRegistry);

    listener.onTimelineCommitted(
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(runtimeSession.getSessionId())
            .positionSeconds(600)
            .state(PlaybackState.PLAYING)
            .accessedAt(currentAccess)
            .build());
    listener.onTimelineCommitted(
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(runtimeSession.getSessionId())
            .positionSeconds(300)
            .state(PlaybackState.PAUSED)
            .accessedAt(currentAccess.minusSeconds(1))
            .build());

    var snapshot = runtimeSession.getPlaybackSnapshot();
    assertThat(snapshot.positionSeconds()).isEqualTo(600);
    assertThat(snapshot.state()).isEqualTo(PlaybackState.PLAYING);
    assertThat(snapshot.accessedAt()).isEqualTo(currentAccess);
  }

  @Test
  @DisplayName("Should apply committed timeline after a later access callback")
  void shouldApplyCommittedTimelineAfterLaterAccessCallback() {
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var runtimeSession = StreamSessionFixture.defaultSessionBuilder().build();
    var timelineAccess = Instant.parse("2026-07-11T01:00:00Z");
    var laterAccess = timelineAccess.plusSeconds(1);
    runtimeSession.setLastAccessedAt(timelineAccess.minusSeconds(1));
    runtimeRegistry.save(runtimeSession);
    runtimeRegistry.mirrorCommittedAccess(runtimeSession.getSessionId(), laterAccess);
    var listener = new RuntimeStreamSessionTimelineListener(runtimeRegistry);

    listener.onTimelineCommitted(
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(runtimeSession.getSessionId())
            .positionSeconds(420)
            .state(PlaybackState.PLAYING)
            .accessedAt(timelineAccess)
            .build());

    var snapshot = runtimeSession.getPlaybackSnapshot();
    assertThat(snapshot.positionSeconds()).isEqualTo(420);
    assertThat(snapshot.state()).isEqualTo(PlaybackState.PLAYING);
    assertThat(snapshot.accessedAt()).isEqualTo(laterAccess);
  }

  @Test
  @DisplayName("Should do nothing when runtime vanished before the committed event")
  void shouldDoNothingWhenRuntimeVanishedBeforeCommittedEvent() {
    var runtimeRegistry = new InMemoryStreamSessionRepository();
    var runtimeSession = StreamSessionFixture.defaultSessionBuilder().build();
    runtimeRegistry.save(runtimeSession);
    runtimeRegistry.terminalize(runtimeSession.getSessionId());
    var listener = new RuntimeStreamSessionTimelineListener(runtimeRegistry);
    var event =
        StreamSessionTimelineCommittedEvent.builder()
            .sessionId(runtimeSession.getSessionId())
            .positionSeconds(420)
            .state(PlaybackState.PLAYING)
            .accessedAt(Instant.parse("2026-07-11T01:00:00Z"))
            .build();

    assertThatNoException().isThrownBy(() -> listener.onTimelineCommitted(event));

    assertThat(runtimeRegistry.findById(runtimeSession.getSessionId())).isEmpty();
    assertThat(runtimeRegistry.count()).isZero();
  }
}
