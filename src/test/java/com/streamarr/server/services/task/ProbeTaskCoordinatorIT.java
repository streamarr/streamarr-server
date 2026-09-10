package com.streamarr.server.services.task;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
class ProbeTaskCoordinatorIT extends AbstractIntegrationTest {

  @Autowired private FileProcessingTaskCoordinator coordinator;
  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository files;
  @Autowired private FileProcessingTaskRepository tasks;
  @Autowired private PersistedProbeReader probes;
  @Autowired private MediaFileContainerInfoRepository containers;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clearTasks() {
    tasks.deleteAll();
  }

  @Test
  @DisplayName("Should retain one pending probe when the same source is enqueued twice")
  void shouldRetainOnePendingProbeWhenTheSameSourceIsEnqueuedTwice() {
    var request = request();

    var first = coordinator.enqueueProbe(request);
    var second = coordinator.enqueueProbe(request);

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("Should claim the requested source exactly once with a fresh lease")
  void shouldClaimTheRequestedSourceExactlyOnceWithAFreshLease() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);

    var claim = coordinator.claimProbeTask().orElseThrow();

    assertThat(claim.taskId()).isEqualTo(taskId);
    assertThat(claim.claimId()).isNotNull();
    assertThat(claim.request()).isEqualTo(request);
    assertThat(claim.leaseExpiresAt()).isAfter(Instant.now());
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  private enum LegacyOperation {
    CLAIM,
    COMPLETE,
    FAIL,
    RECOVER
  }

  @ParameterizedTest
  @EnumSource(LegacyOperation.class)
  @DisplayName("Should leave probe tasks untouched by legacy watcher transitions")
  void shouldLeaveProbeTasksUntouchedByLegacyWatcherTransitions(LegacyOperation operation) {
    var taskId = coordinator.enqueueProbe(request());

    var result =
        switch (operation) {
          case CLAIM -> coordinator.claimNextTask();
          case COMPLETE -> coordinator.complete(taskId);
          case FAIL -> coordinator.fail(taskId, "legacy failure");
          case RECOVER -> coordinator.reclaimOrphanedTasks(10).stream().findFirst();
        };

    assertThat(result).isEmpty();
    assertThat(coordinator.claimProbeTask()).isPresent();
  }

  @Test
  @DisplayName("Should replace a claimed source when a changed snapshot is enqueued")
  void shouldReplaceAClaimedSourceWhenAChangedSnapshotIsEnqueued() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var oldClaim = coordinator.claimProbeTask().orElseThrow();
    var changed =
        request.toBuilder()
            .snapshot(new SourceFileSnapshot(41, request.snapshot().modifiedAt().minusSeconds(1)))
            .build();

    assertThat(coordinator.enqueueProbe(changed)).isEqualTo(taskId);
    var replacement = coordinator.claimProbeTask().orElseThrow();

    assertThat(replacement.request()).isEqualTo(changed);
    assertThat(replacement.claimId()).isNotEqualTo(oldClaim.claimId());
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  @Test
  @DisplayName("Should refresh a higher probe version without letting an older request replace it")
  void shouldRefreshAHigherProbeVersionWithoutLettingAnOlderRequestReplaceIt() {
    var request = request();
    coordinator.enqueueProbe(request);
    var original = coordinator.claimProbeTask().orElseThrow();
    var upgrade = request.toBuilder().probeVersion(2).build();

    coordinator.enqueueProbe(upgrade);
    var current = coordinator.claimProbeTask().orElseThrow();
    coordinator.enqueueProbe(request);

    assertThat(current.request()).isEqualTo(upgrade);
    assertThat(current.claimId()).isNotEqualTo(original.claimId());
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  @Test
  @DisplayName("Should persist a retry without retaining a lease or bypassing its delay")
  void shouldPersistARetryWithoutRetainingALeaseOrBypassingItsDelay() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();

    assertThat(coordinator.retryProbe(claim, "Storage unavailable")).isTrue();
    coordinator.enqueueProbe(request);

    var task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getLeaseExpiresAt()).isNull();
    assertThat(task.getOwnerInstanceId()).isNull();
    assertThat(task.getErrorMessage()).isEqualTo("Storage unavailable");
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  @Test
  @DisplayName("Should increase persisted retry delays up to the cap")
  void shouldIncreasePersistedRetryDelaysUpToTheCap() {
    var retryTime = Instant.parse("2020-01-01T00:00:00Z");
    var retrying =
        new FileProcessingTaskCoordinator(
            tasks, Clock.fixed(retryTime, ZoneOffset.UTC), Duration.ofMinutes(1));
    var taskId = coordinator.enqueueProbe(request());

    for (int attempt = 0; attempt < 8; attempt++) {
      var claim = coordinator.claimProbeTask().orElseThrow();
      assertThat(claim.retryCount()).isEqualTo(attempt);
      assertThat(retrying.retryProbe(claim, "Transient failure")).isTrue();
      assertThat(tasks.findById(taskId).orElseThrow().getRetryAt())
          .isEqualTo(retryTime.plusSeconds(Math.min(300, 5L << attempt)));
      assertThat(retrying.retryProbe(claim, "Obsolete retry")).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should recover an expired probe claim without letting the legacy heartbeat revive it")
  void shouldRecoverAnExpiredProbeClaimWithoutLettingTheLegacyHeartbeatReviveIt() {
    var expired =
        new FileProcessingTaskCoordinator(
            tasks, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-5)), Duration.ofMinutes(1));
    var request = request();
    coordinator.enqueueProbe(request);
    var oldClaim = expired.claimProbeTask().orElseThrow();

    coordinator.extendLeases();
    var replacement = coordinator.claimProbeTask().orElseThrow();

    assertThat(replacement.request()).isEqualTo(request);
    assertThat(replacement.claimId()).isNotEqualTo(oldClaim.claimId());
    assertThat(replacement.leaseExpiresAt()).isAfter(Instant.now());
    assertThat(coordinator.retryProbe(oldClaim, "Old execution")).isFalse();
  }

  @Test
  @DisplayName("Should publish a complete probe outcome and finish its claim atomically")
  void shouldPublishACompleteProbeOutcomeAndFinishItsClaimAtomically() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();
    var outcome = successfulOutcome();
    var publication =
        ProbePublication.builder()
            .claim(claim)
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .outcome(outcome)
            .build();

    assertThat(coordinator.publishProbe(publication)).isTrue();

    assertThat(probes.find(request.mediaFileId()))
        .contains(new PersistedProbeOutcome(request.snapshot(), request.probeVersion(), outcome));
    assertThat(tasks.findById(taskId).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(coordinator.publishProbe(publication)).isFalse();
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  @Test
  @DisplayName("Should complete a claimed probe only when its exact outcome is already stored")
  void shouldCompleteAClaimedProbeOnlyWhenItsExactOutcomeIsAlreadyStored() {
    var request = request();
    coordinator.enqueueProbe(request);
    var first = coordinator.claimProbeTask().orElseThrow();
    assertThat(coordinator.completeProbe(first)).isFalse();
    assertThat(
            coordinator.publishProbe(
                ProbePublication.builder()
                    .claim(first)
                    .snapshot(request.snapshot())
                    .probeVersion(request.probeVersion())
                    .outcome(successfulOutcome())
                    .build()))
        .isTrue();
    var nextId = coordinator.enqueueProbe(request);
    var next = coordinator.claimProbeTask().orElseThrow();

    assertThat(coordinator.completeProbe(next)).isTrue();
    assertThat(tasks.findById(nextId).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(coordinator.completeProbe(next)).isFalse();
  }

  @Test
  @DisplayName("Should fail only the current claim without creating a probe outcome")
  void shouldFailOnlyTheCurrentClaimWithoutCreatingAProbeOutcome() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var obsolete = coordinator.claimProbeTask().orElseThrow();
    coordinator.enqueueProbe(request.toBuilder().probeVersion(2).build());
    var current = coordinator.claimProbeTask().orElseThrow();

    assertThat(coordinator.failProbe(obsolete, "Old failure")).isFalse();
    assertThat(coordinator.failProbe(current, "Source removed")).isTrue();
    var failed = tasks.findById(taskId).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo("Source removed");
    assertThat(failed.getLeaseExpiresAt()).isNull();
    assertThat(probes.find(request.mediaFileId())).isEmpty();
    assertThat(coordinator.failProbe(current, "Late failure")).isFalse();
  }

  @Test
  @DisplayName("Should reschedule changed source inputs only from the current claim")
  void shouldRescheduleChangedSourceInputsOnlyFromTheCurrentClaim() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();
    var changed =
        request.toBuilder()
            .snapshot(new SourceFileSnapshot(43, request.snapshot().modifiedAt().plusNanos(1)))
            .build();

    assertThat(coordinator.rescheduleProbe(claim, changed)).isTrue();
    assertThat(coordinator.rescheduleProbe(claim, request)).isFalse();
    var next = coordinator.claimProbeTask().orElseThrow();
    assertThat(next.taskId()).isEqualTo(taskId);
    assertThat(next.claimId()).isNotEqualTo(claim.claimId());
    assertThat(next.request()).isEqualTo(changed);
    assertThat(next.retryCount()).isZero();
  }

  @Test
  @DisplayName("Should renew only the current unexpired claim without reducing its lease")
  void shouldRenewOnlyTheCurrentUnexpiredClaimWithoutReducingItsLease() {
    var request = request();
    coordinator.enqueueProbe(request);
    var previous = coordinator.claimProbeTask().orElseThrow();
    coordinator.enqueueProbe(request.toBuilder().probeVersion(2).build());
    var claim = coordinator.claimProbeTask().orElseThrow();
    var later =
        new FileProcessingTaskCoordinator(
            tasks, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(1)), Duration.ofMinutes(1));

    assertThat(later.renewProbe(previous)).isFalse();
    assertThat(later.renewProbe(claim)).isTrue();
    var renewed = tasks.findById(claim.taskId()).orElseThrow().getLeaseExpiresAt();
    assertThat(renewed).isAfter(claim.leaseExpiresAt());
    assertThat(coordinator.renewProbe(claim)).isTrue();
    assertThat(tasks.findById(claim.taskId()).orElseThrow().getLeaseExpiresAt()).isEqualTo(renewed);
  }

  private enum InputUpdate {
    ENQUEUE,
    RESCHEDULE
  }

  @ParameterizedTest
  @EnumSource(InputUpdate.class)
  @DisplayName(
      "Should retain a compatible version during refresh but invalidate changed source inputs")
  void shouldRetainACompatibleVersionDuringRefreshButInvalidateChangedSourceInputs(
      InputUpdate update) {
    var request = request();
    coordinator.enqueueProbe(request);
    var original = coordinator.claimProbeTask().orElseThrow();
    coordinator.publishProbe(
        ProbePublication.builder()
            .claim(original)
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .outcome(successfulOutcome())
            .build());
    var upgrade = request.toBuilder().probeVersion(2).build();
    coordinator.enqueueProbe(upgrade);
    var refreshing = coordinator.claimProbeTask().orElseThrow();
    assertThat(probes.find(request.mediaFileId())).isPresent();
    var changed =
        upgrade.toBuilder()
            .snapshot(new SourceFileSnapshot(1, request.snapshot().modifiedAt().minusSeconds(1)))
            .build();

    switch (update) {
      case ENQUEUE -> coordinator.enqueueProbe(changed);
      case RESCHEDULE -> assertThat(coordinator.rescheduleProbe(refreshing, changed)).isTrue();
    }

    assertThat(probes.find(request.mediaFileId())).isEmpty();
    assertThat(coordinator.claimProbeTask().orElseThrow().request()).isEqualTo(changed);
  }

  @Test
  @DisplayName("Should enumerate legacy work in bounded pages without including probe tasks")
  void shouldEnumerateLegacyWorkInBoundedPagesWithoutIncludingProbeTasks() {
    var firstRequest = request();
    var secondRequest = request();
    var first =
        coordinator.createTask(
            Path.of(URI.create(firstRequest.filepathUri())), firstRequest.libraryId());
    var second =
        coordinator.createTask(
            Path.of(URI.create(secondRequest.filepathUri())), secondRequest.libraryId());
    coordinator.enqueueProbe(request());

    var page = coordinator.findLegacyTasks(Optional.empty(), 1);
    assertThat(page).hasSize(1);
    var next = coordinator.findLegacyTasks(Optional.of(page.getFirst().getId()), 1);
    assertThat(next).hasSize(1);
    assertThat(List.of(page.getFirst().getId(), next.getFirst().getId()))
        .containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(coordinator.findLegacyTasks(Optional.of(next.getFirst().getId()), 1)).isEmpty();
  }

  @Test
  @DisplayName("Should revoke a claimed probe when its source is cancelled")
  void shouldRevokeAClaimedProbeWhenItsSourceIsCancelled() {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();

    coordinator.cancelTask(Path.of(URI.create(request.filepathUri())));

    assertThat(tasks.findById(taskId)).isEmpty();
    for (var mutation : ClaimMutation.values()) {
      assertThat(mutate(mutation, claim)).as(mutation.name()).isFalse();
    }

    assertThat(probes.find(request.mediaFileId())).isEmpty();
  }

  @Test
  @DisplayName("Should adopt an active legacy task without allowing its old watcher to finish it")
  void shouldAdoptAnActiveLegacyTaskWithoutAllowingItsOldWatcherToFinishIt() {
    var request = request();
    var legacy =
        coordinator.createTask(Path.of(URI.create(request.filepathUri())), request.libraryId());
    assertThat(coordinator.claimNextTask()).isPresent();
    assertThat(coordinator.claimProbeTask()).isEmpty();

    assertThat(coordinator.enqueueProbe(request)).isEqualTo(legacy.getId());

    var adopted = coordinator.claimProbeTask().orElseThrow();
    assertThat(adopted.taskId()).isEqualTo(legacy.getId());
    assertThat(adopted.request()).isEqualTo(request);
    assertThat(coordinator.complete(legacy.getId())).isEmpty();
    assertThat(coordinator.fail(legacy.getId(), "Old watcher")).isEmpty();
    assertThat(coordinator.findLegacyTasks(Optional.empty(), 10)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should reject publication when a newer version is already stored for the same source")
  void shouldRejectPublicationWhenANewerVersionIsAlreadyStoredForTheSameSource() {
    var request = request();
    var newer = request.toBuilder().probeVersion(2).build();
    coordinator.enqueueProbe(newer);
    var first = coordinator.claimProbeTask().orElseThrow();
    assertThat(
            coordinator.publishProbe(
                ProbePublication.builder()
                    .claim(first)
                    .snapshot(newer.snapshot())
                    .probeVersion(newer.probeVersion())
                    .outcome(successfulOutcome())
                    .build()))
        .isTrue();
    coordinator.enqueueProbe(request);
    var obsolete = coordinator.claimProbeTask().orElseThrow();

    assertThat(mutate(ClaimMutation.PUBLISH, obsolete)).isFalse();

    assertThat(containers.findByMediaFileId(request.mediaFileId()).orElseThrow().getProbeVersion())
        .isEqualTo(2);
    assertThat(tasks.findById(obsolete.taskId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.PROCESSING);
  }

  @ParameterizedTest
  @EnumSource(ProbeError.class)
  @DisplayName(
      "Should replace previous streams with a terminal error without changing matching status")
  void shouldReplacePreviousStreamsWithATerminalErrorWithoutChangingMatchingStatus(
      ProbeError error) {
    var request = request();
    coordinator.enqueueProbe(request);
    var first = coordinator.claimProbeTask().orElseThrow();
    assertThat(mutate(ClaimMutation.PUBLISH, first)).isTrue();
    coordinator.enqueueProbe(request);
    var replacement = coordinator.claimProbeTask().orElseThrow();
    var failure = new ProbeOutcome.Failure(error);

    assertThat(
            coordinator.publishProbe(
                ProbePublication.builder()
                    .claim(replacement)
                    .snapshot(request.snapshot())
                    .probeVersion(request.probeVersion())
                    .outcome(failure)
                    .build()))
        .isTrue();

    assertThat(probes.find(request.mediaFileId()).orElseThrow().outcome()).isEqualTo(failure);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM media_file_stream_info WHERE media_file_id = ?",
                Long.class,
                request.mediaFileId()))
        .isZero();
    assertThat(files.findById(request.mediaFileId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.MATCHED);
    assertThat(coordinator.completeProbe(replacement)).isFalse();
  }

  @Test
  @DisplayName(
      "Should roll back outcome replacement and task completion when stream insertion fails")
  void shouldRollBackOutcomeReplacementAndTaskCompletionWhenStreamInsertionFails() {
    var request = request();
    coordinator.enqueueProbe(request);
    assertThat(mutate(ClaimMutation.PUBLISH, coordinator.claimProbeTask().orElseThrow())).isTrue();
    var original = probes.find(request.mediaFileId()).orElseThrow();
    coordinator.enqueueProbe(request.toBuilder().probeVersion(2).build());
    var claim = coordinator.claimProbeTask().orElseThrow();
    var valid = successfulOutcome();
    var duplicateStreams =
        new ProbeOutcome.Success(
            valid.container(), List.of(valid.streams().getFirst(), valid.streams().getFirst()));
    var publication =
        ProbePublication.builder()
            .claim(claim)
            .snapshot(request.snapshot())
            .probeVersion(2)
            .outcome(duplicateStreams)
            .build();

    assertThatThrownBy(() -> coordinator.publishProbe(publication))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(probes.find(request.mediaFileId())).contains(original);
    assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.PROCESSING);
    assertThat(coordinator.renewProbe(claim)).isTrue();
  }

  @Test
  @DisplayName("Should deduplicate simultaneous requests and issue only one claim")
  void shouldDeduplicateSimultaneousRequestsAndIssueOnlyOneClaim() throws Exception {
    var request = request();

    var enqueued = concurrently(() -> coordinator.enqueueProbe(request));
    var claimed =
        concurrently(coordinator::claimProbeTask).stream().flatMap(Optional::stream).toList();

    assertThat(enqueued).containsOnly(enqueued.getFirst());
    assertThat(claimed).extracting(ProbeClaim::taskId).containsExactly(enqueued.getFirst());
  }

  private <T> List<T> concurrently(Callable<T> operation) throws Exception {
    var gate = new CyclicBarrier(4);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          IntStream.range(0, 4)
              .mapToObj(
                  _ ->
                      executor.submit(
                          () -> {
                            gate.await(5, TimeUnit.SECONDS);
                            return operation.call();
                          }))
              .toList();
      var results = new ArrayList<T>();
      for (var future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }

      return results;
    }
  }

  @ParameterizedTest
  @EnumSource(ClaimMutation.class)
  @DisplayName(
      "Should leave a replacement claim and compatible result untouched by every obsolete mutation")
  void shouldLeaveAReplacementClaimAndCompatibleResultUntouchedByEveryObsoleteMutation(
      ClaimMutation mutation) {
    var request = request();
    coordinator.enqueueProbe(request);
    assertThat(mutate(ClaimMutation.PUBLISH, coordinator.claimProbeTask().orElseThrow())).isTrue();
    var stored = probes.find(request.mediaFileId()).orElseThrow();
    coordinator.enqueueProbe(request);
    var oldClaim = coordinator.claimProbeTask().orElseThrow();
    coordinator.enqueueProbe(request.toBuilder().probeVersion(2).build());
    var replacement = coordinator.claimProbeTask().orElseThrow();

    assertThat(mutate(mutation, oldClaim)).isFalse();

    assertThat(probes.find(request.mediaFileId())).contains(stored);
    assertThat(coordinator.renewProbe(replacement)).isTrue();
    assertThat(coordinator.claimProbeTask()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should retain only replacement inputs when enqueue and publication contend")
  void shouldRetainOnlyReplacementInputsWhenEnqueueAndPublicationContend(boolean enqueueFirst)
      throws Exception {
    var request = request();
    coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();
    var changed = request.toBuilder().snapshot(new SourceFileSnapshot(1, Instant.EPOCH)).build();
    Runnable enqueue = () -> coordinator.enqueueProbe(changed);
    Runnable publish = () -> mutate(ClaimMutation.PUBLISH, claim);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("media_file")
                    .rowId(request.mediaFileId())
                    .build())) {
      var first = executor.submit(enqueueFirst ? enqueue : publish);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> waitersBehind(jdbc, held.backendPid(), "%media_file%") == 1);
      var second = executor.submit(enqueueFirst ? publish : enqueue);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> waitersBehind(jdbc, held.backendPid(), "%media_file%") == 2);
      held.release();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    }

    assertThat(probes.find(request.mediaFileId())).isEmpty();
    assertThat(coordinator.claimProbeTask().orElseThrow().request()).isEqualTo(changed);
    assertThat(mutate(ClaimMutation.PUBLISH, claim)).isFalse();
  }

  private enum InvalidPublication {
    SNAPSHOT,
    VERSION,
    REMOVED_SOURCE
  }

  @ParameterizedTest
  @EnumSource(InvalidPublication.class)
  @DisplayName("Should reject publication with mismatched inputs or a removed source")
  void shouldRejectPublicationWithMismatchedInputsOrARemovedSource(InvalidPublication invalid) {
    var request = request();
    var taskId = coordinator.enqueueProbe(request);
    var claim = coordinator.claimProbeTask().orElseThrow();
    var snapshot =
        invalid == InvalidPublication.SNAPSHOT
            ? new SourceFileSnapshot(1, Instant.EPOCH)
            : request.snapshot();
    var version = invalid == InvalidPublication.VERSION ? 2 : request.probeVersion();
    if (invalid == InvalidPublication.REMOVED_SOURCE) {
      files.deleteById(request.mediaFileId());
    }

    assertThat(
            coordinator.publishProbe(
                ProbePublication.builder()
                    .claim(claim)
                    .snapshot(snapshot)
                    .probeVersion(version)
                    .outcome(successfulOutcome())
                    .build()))
        .isFalse();
    assertThat(probes.find(request.mediaFileId())).isEmpty();
    if (invalid == InvalidPublication.REMOVED_SOURCE) {
      assertThat(tasks.findById(taskId)).isEmpty();
      return;
    }

    assertThat(tasks.findById(taskId).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.PROCESSING);
  }

  private enum ClaimMutation {
    PUBLISH,
    COMPLETE,
    FAIL,
    RETRY,
    RESCHEDULE,
    RENEW
  }

  @ParameterizedTest
  @EnumSource(ClaimMutation.class)
  @DisplayName("Should reject a mutation when its lease expires while waiting for the task lock")
  void shouldRejectAMutationWhenItsLeaseExpiresWhileWaitingForTheTaskLock(ClaimMutation mutation)
      throws Exception {
    var request = request();
    coordinator.enqueueProbe(request);
    var original = coordinator.claimProbeTask().orElseThrow();
    assertThat(
            coordinator.publishProbe(
                ProbePublication.builder()
                    .claim(original)
                    .snapshot(request.snapshot())
                    .probeVersion(request.probeVersion())
                    .outcome(successfulOutcome())
                    .build()))
        .isTrue();
    coordinator.enqueueProbe(request);
    var shortLease =
        new FileProcessingTaskCoordinator(tasks, Clock.systemUTC(), Duration.ofSeconds(3));
    var claim = shortLease.claimProbeTask().orElseThrow();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("file_processing_task")
                    .rowId(claim.taskId())
                    .build())) {
      var pending = executor.submit(() -> mutate(mutation, claim));
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> waitersBehind(jdbc, held.backendPid(), "%file_processing_task%") == 1);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> Instant.now().isAfter(claim.leaseExpiresAt()));
      held.release();

      assertThat(pending.get(5, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(probes.find(request.mediaFileId())).isPresent();
    assertThat(coordinator.claimProbeTask().orElseThrow().claimId()).isNotEqualTo(claim.claimId());
  }

  private boolean mutate(ClaimMutation mutation, ProbeClaim claim) {
    return switch (mutation) {
      case PUBLISH ->
          coordinator.publishProbe(
              ProbePublication.builder()
                  .claim(claim)
                  .snapshot(claim.request().snapshot())
                  .probeVersion(claim.request().probeVersion())
                  .outcome(successfulOutcome())
                  .build());
      case COMPLETE -> coordinator.completeProbe(claim);
      case FAIL -> coordinator.failProbe(claim, "Obsolete failure");
      case RETRY -> coordinator.retryProbe(claim, "Obsolete retry");
      case RESCHEDULE ->
          coordinator.rescheduleProbe(
              claim,
              claim.request().toBuilder()
                  .snapshot(new SourceFileSnapshot(1, Instant.EPOCH))
                  .build());
      case RENEW -> coordinator.renewProbe(claim);
    };
  }

  private ProbeOutcome.Success successfulOutcome() {
    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(Optional.of("matroska"))
            .duration(Optional.of(Duration.ofSeconds(90)))
            .bitrate(OptionalLong.of(4000000))
            .build(),
        List.of(
            StreamInfo.builder()
                .index(0)
                .codecType("video")
                .codec(Optional.of("h264"))
                .width(OptionalInt.of(1920))
                .height(OptionalInt.of(1080))
                .framerate(OptionalDouble.of(24))
                .bitrate(OptionalLong.of(3800000))
                .build(),
            StreamInfo.builder()
                .index(1)
                .codecType("audio")
                .codec(Optional.of("aac"))
                .channels(OptionalInt.of(2))
                .bitrate(OptionalLong.of(192000))
                .language(Optional.of("eng"))
                .isDefault(true)
                .build()));
  }

  private ProbeRequest request() {
    var library =
        libraries.saveAndFlush(
            Library.builder()
                .name("Probe " + UUID.randomUUID())
                .filepathUri("file:///media/" + UUID.randomUUID())
                .backend(LibraryBackend.LOCAL)
                .status(LibraryStatus.HEALTHY)
                .type(MediaType.MOVIE)
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .build());
    var file =
        files.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(library.getFilepathUri() + "/movie.mkv")
                .filename("movie.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(42)
                .build());
    return ProbeRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(library.getId())
        .filepathUri(file.getFilepathUri())
        .snapshot(new SourceFileSnapshot(42, Instant.parse("2026-09-10T12:00:00.999999999Z")))
        .probeVersion(1)
        .build();
  }
}
