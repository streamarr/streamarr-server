package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.CommittedStreamSessionTimeline;
import com.streamarr.server.services.streaming.RuntimeSessionReservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class InMemoryStreamSessionRepositoryTest {

  private InMemoryStreamSessionRepository repository;

  @BeforeEach
  void setUp() {
    repository = new InMemoryStreamSessionRepository();
  }

  @Test
  @DisplayName("Should return session when saved by id")
  void shouldReturnSessionWhenSavedById() {
    var session = StreamSessionFixture.buildMpegtsSession();

    repository.save(session);

    var found = repository.findById(session.getSessionId());
    assertThat(found).isPresent().contains(session);
  }

  @Test
  @DisplayName("Should return empty when session not found")
  void shouldReturnEmptyWhenSessionNotFound() {
    var found = repository.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("Should remove and return session when removed by id")
  void shouldRemoveAndReturnSessionWhenRemovedById() {
    var session = StreamSessionFixture.buildMpegtsSession();
    repository.save(session);

    var removed = repository.removeById(session.getSessionId());

    assertThat(removed).isPresent().contains(session);
    assertThat(repository.findById(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when removing nonexistent session")
  void shouldReturnEmptyWhenRemovingNonexistentSession() {
    var removed = repository.removeById(UUID.randomUUID());

    assertThat(removed).isEmpty();
  }

  @Test
  @DisplayName("Should return all saved sessions")
  void shouldReturnAllSavedSessions() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    repository.save(session1);
    repository.save(session2);

    var all = repository.findAll();

    assertThat(all).containsExactlyInAnyOrder(session1, session2);
  }

  @Test
  @DisplayName("Should track count across saves and removes")
  void shouldTrackCountAcrossSavesAndRemoves() {
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();

    assertThat(repository.count()).isZero();

    repository.save(session1);
    assertThat(repository.count()).isEqualTo(1);

    repository.save(session2);
    assertThat(repository.count()).isEqualTo(2);

    repository.removeById(session1.getSessionId());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should overwrite session when saving existing id")
  void shouldOverwriteSessionWhenSavingExistingId() {
    var session = StreamSessionFixture.buildMpegtsSession();
    repository.save(session);

    session.mirrorCommittedPlaybackState(
        300, PlaybackState.PLAYING, session.getPlaybackSnapshot().accessedAt());
    repository.save(session);

    assertThat(repository.count()).isEqualTo(1);
    var found = repository.findById(session.getSessionId());
    assertThat(found).isPresent();
    assertThat(found.get().getPlaybackSnapshot().positionSeconds()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should reclaim terminal slot only after its late starter is stopped")
  void shouldReclaimTerminalSlotOnlyAfterLateStarterIsStopped() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(reservation, session)).isTrue();
    var starter = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();

    assertThat(repository.terminalize(session.getSessionId())).isTrue();
    repository.markRuntimeStopped(session.getSessionId());
    repository.releaseTerminal(session.getSessionId());
    repository.releaseReservation(reservation);

    assertThat(repository.reserve(session.getSessionId())).isEmpty();
    assertThat(repository.completeTranscodeStart(starter)).isFalse();
    repository.finishRejectedTranscodeStart(starter, true);
    assertThat(repository.reserve(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should retain terminal slot when stopping a late starter fails")
  void shouldRetainTerminalSlotWhenStoppingLateStarterFails() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();
    var starter = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();
    repository.terminalize(session.getSessionId());
    repository.markRuntimeStopped(session.getSessionId());
    repository.releaseTerminal(session.getSessionId());
    repository.releaseReservation(reservation);

    assertThat(repository.completeTranscodeStart(starter)).isFalse();
    repository.finishRejectedTranscodeStart(starter, false);

    assertThat(repository.reserve(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should expose cleanup candidates without exposing an empty reservation")
  void shouldExposeCleanupCandidatesWithoutExposingEmptyReservation() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();

    assertThat(repository.snapshotCleanupCandidateIds()).isEmpty();

    assertThat(repository.attach(reservation, session)).isTrue();
    assertThat(repository.snapshotCleanupCandidateIds()).containsExactly(session.getSessionId());

    assertThat(repository.terminalize(session.getSessionId())).isTrue();
    assertThat(repository.snapshotCleanupCandidateIds()).containsExactly(session.getSessionId());

    repository.markRuntimeStopped(session.getSessionId());
    assertThat(repository.snapshotCleanupCandidateIds()).isEmpty();
  }

  @Test
  @DisplayName("Should not let stale starter affect a reclaimed replacement slot")
  void shouldNotLetStaleStarterAffectReclaimedReplacementSlot() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var firstReservation = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(firstReservation, session)).isTrue();
    var staleStarter = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();
    repository.terminalize(session.getSessionId());
    repository.markRuntimeStopped(session.getSessionId());
    repository.releaseTerminal(session.getSessionId());
    repository.releaseReservation(firstReservation);
    repository.finishRejectedTranscodeStart(staleStarter, true);

    var replacement = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(replacement, session)).isTrue();

    assertThat(repository.completeTranscodeStart(staleStarter)).isFalse();
    assertThat(repository.markRunning(replacement)).isTrue();
    assertThat(repository.findById(session.getSessionId())).contains(session);
  }

  @Test
  @DisplayName("Should mirror committed access monotonically")
  void shouldMirrorCommittedAccessMonotonically() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var current = Instant.parse("2026-07-10T20:00:00Z");
    session.setLastAccessedAt(current);
    repository.save(session);

    repository.mirrorCommittedAccess(session.getSessionId(), Instant.parse("2026-07-10T19:59:59Z"));
    assertThat(session.getLastAccessedAt()).isEqualTo(current);

    var later = Instant.parse("2026-07-10T20:00:01Z");
    repository.mirrorCommittedAccess(session.getSessionId(), later);
    assertThat(session.getLastAccessedAt()).isEqualTo(later);
  }

  @Test
  @DisplayName("Should preserve committed timeline when a later access is already mirrored")
  void shouldPreserveCommittedTimelineWhenLaterAccessAlreadyMirrored() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var timelineAccess = Instant.parse("2026-07-10T20:00:00Z");
    var laterAccess = timelineAccess.plusSeconds(1);
    session.setLastAccessedAt(timelineAccess.minusSeconds(1));
    repository.save(session);

    repository.mirrorCommittedAccess(session.getSessionId(), laterAccess);
    repository.mirrorCommittedTimeline(
        CommittedStreamSessionTimeline.builder()
            .streamSessionId(session.getSessionId())
            .positionSeconds(420)
            .state(PlaybackState.PLAYING)
            .accessedAt(timelineAccess)
            .build());

    var snapshot = session.getPlaybackSnapshot();
    assertThat(snapshot.positionSeconds()).isEqualTo(420);
    assertThat(snapshot.state()).isEqualTo(PlaybackState.PLAYING);
    assertThat(snapshot.accessedAt()).isEqualTo(laterAccess);
  }

  @Test
  @DisplayName("Should reclaim repeated terminal cycles and reject stale attachment")
  void shouldReclaimRepeatedTerminalCyclesAndRejectStaleAttachment() {
    RuntimeSessionReservation staleReservation = null;
    StreamSession staleSession = null;
    for (var cycle = 0; cycle < 100; cycle++) {
      var session = StreamSessionFixture.buildMpegtsSession();
      var reservation = repository.reserve(session.getSessionId()).orElseThrow();
      assertThat(repository.attach(reservation, session)).isTrue();
      var start = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();
      assertThat(repository.completeTranscodeStart(start)).isTrue();
      assertThat(repository.markRunning(reservation)).isTrue();
      repository.releaseReservation(reservation);

      assertThat(repository.terminalize(session.getSessionId())).isTrue();
      repository.markRuntimeStopped(session.getSessionId());
      repository.releaseTerminal(session.getSessionId());

      assertThat(repository.findById(session.getSessionId())).isEmpty();
      assertThat(repository.count()).isZero();
      staleReservation = reservation;
      staleSession = session;
    }

    assertThat(repository.attach(staleReservation, staleSession)).isFalse();
  }

  @Test
  @DisplayName("Should publish and fence a runtime session across concurrent readers")
  void shouldPublishAndFenceRuntimeSessionAcrossConcurrentReaders() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(reservation, session)).isTrue();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var readerCount = 32;
      var readyToFence = new CountDownLatch(readerCount);
      var terminalized = new CountDownLatch(1);
      var readers = new ArrayList<Future<?>>();
      for (var reader = 0; reader < readerCount; reader++) {
        readers.add(
            executor.submit(
                () -> {
                  assertThat(repository.findById(session.getSessionId())).contains(session);
                  assertThat(repository.findAll()).contains(session);
                  readyToFence.countDown();
                  if (!terminalized.await(5, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Session was not terminalized");
                  }
                  assertThat(repository.findById(session.getSessionId())).isEmpty();
                  assertThat(repository.findAll()).doesNotContain(session);
                  return null;
                }));
      }

      assertThat(readyToFence.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(repository.terminalize(session.getSessionId())).isTrue();
      terminalized.countDown();
      for (var reader : readers) {
        reader.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName("Should await the current starter drain without blocking its completion")
  void shouldAwaitCurrentStarterDrainWithoutBlockingItsCompletion() throws Exception {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var firstStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(firstStart)).isTrue();
    var currentStart = repository.beginTranscodeStart(sessionId).orElseThrow();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting = executor.submit(() -> repository.awaitTranscodeStarts(sessionId));
      assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      var completion = executor.submit(() -> repository.completeTranscodeStart(currentStart));
      assertThat(completion.get(5, TimeUnit.SECONDS)).isTrue();
      waiting.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("Should refuse reservations and runtime transitions after shutdown fence")
  void shouldRefuseReservationsAndRuntimeTransitionsAfterShutdownFence() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(reservation, session)).isTrue();

    assertThat(repository.fenceAll()).containsExactly(session.getSessionId());

    assertThat(repository.reserve(UUID.randomUUID())).isEmpty();
    assertThat(repository.attach(reservation, session)).isFalse();
    assertThat(repository.beginTranscodeStart(session.getSessionId())).isEmpty();
    assertThat(repository.markRunning(reservation)).isFalse();
    assertThat(repository.findById(session.getSessionId())).isEmpty();
  }
}
