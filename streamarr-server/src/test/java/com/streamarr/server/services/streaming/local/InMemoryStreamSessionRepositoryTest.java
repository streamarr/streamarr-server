package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.CommittedStreamSessionTimeline;
import com.streamarr.server.services.streaming.RuntimeSessionReservation;
import com.streamarr.server.services.streaming.RuntimeTranscodeStart;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
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
  @DisplayName("Should retain exact-job cleanup when removing a visible session")
  void shouldRetainExactJobCleanupWhenRemovingVisibleSession() {
    var session = StreamSessionFixture.buildMpegtsSession();
    repository.save(session);
    var start = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();
    assertThat(repository.completeTranscodeStart(start)).isTrue();

    assertThat(repository.removeById(session.getSessionId())).contains(session);

    assertThat(repository.findById(session.getSessionId())).isEmpty();
    assertThat(repository.snapshotTranscodeJobRefs(session.getSessionId()))
        .containsExactly(start.jobRef());
    assertThat(repository.snapshotCleanupCandidateIds()).contains(session.getSessionId());
    repository.markTranscodeJobReleased(start.jobRef());
    assertThat(repository.reserve(session.getSessionId())).isPresent();
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
  @DisplayName("Should issue exact transcode jobs monotonically for a session")
  void shouldIssueExactTranscodeJobsMonotonicallyForSession() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();

    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    var second = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(first.jobRef().jobId()).isEqualTo(sessionId);
    assertThat(first.jobRef().generation()).isEqualTo(1);
    assertThat(second.jobRef().jobId()).isEqualTo(sessionId);
    assertThat(second.jobRef().generation()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should expose an exact job as active only after start acceptance")
  void shouldExposeExactJobAsActiveOnlyAfterStartAcceptance() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var start = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(repository.activeTranscodeJobRef(sessionId)).isEmpty();

    assertThat(repository.completeTranscodeStart(start)).isTrue();
    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(start.jobRef());
  }

  @Test
  @DisplayName("Should reject duplicate acceptance without changing exact active authority")
  void shouldRejectDuplicateAcceptanceWithoutChangingExactActiveAuthority() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var start = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(repository.completeTranscodeStart(start)).isTrue();

    assertThat(repository.completeTranscodeStart(start)).isFalse();
    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(start.jobRef());
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(start.jobRef());
  }

  @Test
  @DisplayName("Should refuse activation after the exact in-flight job is released")
  void shouldRefuseActivationAfterExactInFlightJobReleased() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var start = repository.beginTranscodeStart(sessionId).orElseThrow();

    repository.markTranscodeJobReleased(start.jobRef());

    assertThat(repository.completeTranscodeStart(start)).isFalse();
    assertThat(repository.activeTranscodeJobRef(sessionId)).isEmpty();
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(start.jobRef());

    repository.finishRejectedTranscodeStart(start, false);
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a conflicting duplicate result after the exact start settles")
  void shouldIgnoreConflictingDuplicateResultAfterExactStartSettles() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var start = repository.beginTranscodeStart(sessionId).orElseThrow();

    repository.abortTranscodeStart(start);
    repository.finishRejectedTranscodeStart(start, true);

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(start.jobRef());
    repository.markTranscodeJobReleased(start.jobRef());
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should retain an immutable exact-job cleanup snapshot after terminalization")
  void shouldRetainImmutableExactJobCleanupSnapshotAfterTerminalization() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    var second = repository.beginTranscodeStart(sessionId).orElseThrow();

    var beforeTerminal = repository.snapshotTranscodeJobRefs(sessionId);
    assertThat(repository.terminalize(sessionId)).isTrue();

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).isEqualTo(beforeTerminal);
    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(first.jobRef(), second.jobRef());
    assertThatThrownBy(() -> beforeTerminal.clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("Should release only the exact transcode job proven cleaned")
  void shouldReleaseOnlyExactTranscodeJobProvenCleaned() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(first)).isTrue();
    var second = repository.beginTranscodeStart(sessionId).orElseThrow();
    repository.abortTranscodeStart(second);
    repository.terminalize(sessionId);

    repository.markTranscodeJobReleased(first.jobRef());
    repository.markTranscodeJobReleased(first.jobRef());

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(second.jobRef());
  }

  @Test
  @DisplayName("Should retry terminal cleanup until the exact job is released")
  void shouldRetryTerminalCleanupUntilExactJobReleased() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var start = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(start)).isTrue();
    repository.releaseReservation(reservation);
    repository.terminalize(sessionId);

    assertThat(repository.releaseTerminal(sessionId)).isFalse();
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(start.jobRef());
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(start.jobRef());

    repository.markTranscodeJobReleased(start.jobRef());

    assertThat(repository.releaseTerminal(sessionId)).isTrue();
    assertThat(repository.reserve(sessionId)).isPresent();
  }

  @Test
  @DisplayName("Should retain an older exact job until cleanup after replacement acceptance")
  void shouldRetainOlderExactJobUntilCleanupAfterReplacementAcceptance() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(first)).isTrue();
    var replacement = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(first.jobRef(), replacement.jobRef());

    assertThat(repository.completeTranscodeStart(replacement)).isTrue();

    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(first.jobRef(), replacement.jobRef());

    repository.markTranscodeJobReleased(first.jobRef());

    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(replacement.jobRef());
  }

  @Test
  @DisplayName("Should keep fallback active when replacement cleanup is pending")
  void shouldKeepFallbackActiveWhenReplacementCleanupPending() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var fallback = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(fallback)).isTrue();
    var failedReplacement = repository.beginTranscodeStart(sessionId).orElseThrow();

    repository.abortTranscodeStart(failedReplacement);

    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(fallback.jobRef());
    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(fallback.jobRef(), failedReplacement.jobRef());
  }

  @Test
  @DisplayName("Should clear active authority only when that exact job is released")
  void shouldClearActiveAuthorityOnlyWhenExactJobReleased() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(first)).isTrue();
    var replacement = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(replacement)).isTrue();

    repository.markTranscodeJobReleased(first.jobRef());

    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(replacement.jobRef());

    repository.markTranscodeJobReleased(replacement.jobRef());

    assertThat(repository.activeTranscodeJobRef(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should not reactivate an older start after releasing a newer active job")
  void shouldNotReactivateOlderStartAfterReleasingNewerActiveJob() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var olderStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    var newerStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(newerStart)).isTrue();
    repository.markTranscodeJobReleased(newerStart.jobRef());
    assertThat(repository.activeTranscodeJobRef(sessionId)).isEmpty();

    assertThat(repository.completeTranscodeStart(olderStart)).isTrue();

    assertThat(repository.activeTranscodeJobRef(sessionId)).isEmpty();
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(olderStart.jobRef());
  }

  @Test
  @DisplayName("Should retain active authority through terminal fence until broad cleanup")
  void shouldRetainActiveAuthorityThroughTerminalFenceUntilBroadCleanup() {
    var session = StreamSessionFixture.buildMpegtsSession();
    var reservation = repository.reserve(session.getSessionId()).orElseThrow();
    assertThat(repository.attach(reservation, session)).isTrue();
    var active = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();
    assertThat(repository.completeTranscodeStart(active)).isTrue();
    var lateStart = repository.beginTranscodeStart(session.getSessionId()).orElseThrow();

    repository.terminalize(session.getSessionId());

    assertThat(repository.findById(session.getSessionId())).isEmpty();
    assertThat(repository.activeTranscodeJobRef(session.getSessionId())).contains(active.jobRef());

    repository.markRuntimeStopped(session.getSessionId());

    assertThat(repository.activeTranscodeJobRef(session.getSessionId())).isEmpty();
    assertThat(repository.snapshotTranscodeJobRefs(session.getSessionId()))
        .containsExactly(lateStart.jobRef());
  }

  @Test
  @DisplayName("Should retain an older in-flight start after replacement acceptance")
  void shouldRetainOlderInFlightStartAfterReplacementAcceptance() {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();
    var olderStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    var acceptedReplacement = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(repository.completeTranscodeStart(acceptedReplacement)).isTrue();
    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(olderStart.jobRef(), acceptedReplacement.jobRef());
    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(acceptedReplacement.jobRef());

    assertThat(repository.completeTranscodeStart(olderStart)).isTrue();

    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(olderStart.jobRef(), acceptedReplacement.jobRef());
    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(acceptedReplacement.jobRef());
  }

  @Test
  @DisplayName("Should keep a late exact start fenced until exact cleanup succeeds")
  void shouldKeepLateExactStartFencedUntilExactCleanupSucceeds() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var active = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(active)).isTrue();
    var lateStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    repository.releaseReservation(reservation);

    repository.terminalize(sessionId);
    repository.markRuntimeStopped(sessionId);
    assertThat(repository.releaseTerminal(sessionId)).isFalse();

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(lateStart.jobRef());
    assertThat(repository.completeTranscodeStart(lateStart)).isFalse();
    repository.finishRejectedTranscodeStart(lateStart, false);
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(lateStart.jobRef());

    repository.markTranscodeJobReleased(lateStart.jobRef());
    assertThat(repository.releaseTerminal(sessionId)).isTrue();
  }

  @Test
  @DisplayName("Should release a rejected start without losing another active exact job")
  void shouldReleaseRejectedStartWithoutLosingAnotherActiveExactJob() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var active = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(active)).isTrue();
    var rejected = repository.beginTranscodeStart(sessionId).orElseThrow();
    repository.releaseReservation(reservation);
    repository.terminalize(sessionId);

    assertThat(repository.completeTranscodeStart(rejected)).isFalse();
    repository.finishRejectedTranscodeStart(rejected, true);

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(active.jobRef());
    assertThat(repository.releaseTerminal(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should issue unique monotonic generations across concurrent begins")
  void shouldIssueUniqueMonotonicGenerationsAcrossConcurrentBegins() throws Exception {
    var sessionId = UUID.randomUUID();
    repository.reserve(sessionId).orElseThrow();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var begins = new ArrayList<Future<Long>>();
      for (var attempt = 0; attempt < 64; attempt++) {
        begins.add(
            executor.submit(
                () ->
                    repository.beginTranscodeStart(sessionId).orElseThrow().jobRef().generation()));
      }

      var generations = new ArrayList<Long>();
      for (var begin : begins) {
        generations.add(begin.get(5, TimeUnit.SECONDS));
      }

      assertThat(generations)
          .doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 64).boxed().toList());
    }
  }

  @Test
  @DisplayName("Should preserve exact-job generation high-water across slot reclamation")
  void shouldPreserveExactJobGenerationHighWaterAcrossSlotReclamation() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(first)).isTrue();
    repository.releaseReservation(reservation);
    repository.terminalize(sessionId);
    assertThat(repository.releaseTerminal(sessionId)).isFalse();
    repository.markTranscodeJobReleased(first.jobRef());

    repository.reserve(sessionId).orElseThrow();
    var replacement = repository.beginTranscodeStart(sessionId).orElseThrow();

    assertThat(replacement.jobRef().generation()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should restore cleanup obligation when a new exact job begins")
  void shouldRestoreCleanupObligationWhenNewExactJobBegins() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var first = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(first)).isTrue();
    repository.markTranscodeJobReleased(first.jobRef());

    var replacement = repository.beginTranscodeStart(sessionId).orElseThrow();
    assertThat(repository.completeTranscodeStart(replacement)).isTrue();
    repository.releaseReservation(reservation);
    repository.terminalize(sessionId);

    assertThat(repository.snapshotTranscodeJobRefs(sessionId))
        .containsExactly(replacement.jobRef());
    assertThat(repository.releaseTerminal(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should retain a released exact job until its in-flight start settles")
  void shouldRetainReleasedExactJobUntilInFlightStartSettles() {
    var sessionId = UUID.randomUUID();
    var reservation = repository.reserve(sessionId).orElseThrow();
    var inFlight = repository.beginTranscodeStart(sessionId).orElseThrow();
    repository.releaseReservation(reservation);
    repository.terminalize(sessionId);

    repository.markTranscodeJobReleased(inFlight.jobRef());

    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).containsExactly(inFlight.jobRef());
    assertThat(repository.completeTranscodeStart(inFlight)).isFalse();
    repository.finishRejectedTranscodeStart(inFlight, false);
    assertThat(repository.snapshotTranscodeJobRefs(sessionId)).isEmpty();
    assertThat(repository.releaseTerminal(sessionId)).isTrue();
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
  @DisplayName("Should reject a start result carrying a stale slot generation")
  void shouldRejectStartResultCarryingStaleSlotGeneration() {
    var sessionId = UUID.randomUUID();
    var staleReservation = repository.reserve(sessionId).orElseThrow();
    var staleStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    repository.releaseReservation(staleReservation);
    repository.terminalize(sessionId);
    repository.markRuntimeStopped(sessionId);
    repository.releaseTerminal(sessionId);
    repository.finishRejectedTranscodeStart(staleStart, true);

    repository.reserve(sessionId).orElseThrow();
    var currentStart = repository.beginTranscodeStart(sessionId).orElseThrow();
    var crossedStart =
        new RuntimeTranscodeStart(sessionId, staleStart.slotGeneration(), currentStart.jobRef());

    repository.finishRejectedTranscodeStart(crossedStart, true);

    assertThat(repository.completeTranscodeStart(currentStart)).isTrue();
    assertThat(repository.activeTranscodeJobRef(sessionId)).contains(currentStart.jobRef());
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
