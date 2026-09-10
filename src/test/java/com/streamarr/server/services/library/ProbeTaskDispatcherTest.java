package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Probe task dispatcher tests")
class ProbeTaskDispatcherTest {

  @TempDir Path directory;

  private final MutableClock clock = new MutableClock();
  private final ProbeTasks repository = new ProbeTasks();
  private final FileProcessingTaskCoordinator coordinator =
      new FileProcessingTaskCoordinator(repository, clock, Duration.ofSeconds(60));
  private final ProbeOutcome outcome =
      new ProbeOutcome.Success(
          ProbeContainer.builder().build(),
          List.of(StreamInfo.builder().codecType("video").build()));
  private ProbeClaim claim;

  @BeforeEach
  void setUp() throws IOException {
    var path = Files.writeString(directory.resolve("movie.mkv"), "media");
    var request =
        ProbeRequest.builder()
            .mediaFileId(UUID.randomUUID())
            .libraryId(UUID.randomUUID())
            .filepathUri(FilepathCodec.encode(path))
            .snapshot(
                new SourceFileSnapshot(
                    Files.size(path), Files.getLastModifiedTime(path).toInstant()))
            .probeVersion(1)
            .build();
    claim =
        ProbeClaim.builder()
            .taskId(UUID.randomUUID())
            .claimId(UUID.randomUUID())
            .request(request)
            .leaseExpiresAt(clock.instant().plusSeconds(60))
            .build();
    repository.available.add(claim);
  }

  @Test
  @DisplayName("Should publish a complete outcome when a queued probe succeeds")
  void shouldPublishACompleteOutcomeWhenAQueuedProbeSucceeds() {
    try (var dispatcher =
        dispatcher().producer(path -> outcome).stabilityChecker(path -> true).build()) {
      dispatcher.dispatch();

      await()
          .untilAsserted(
              () ->
                  assertThat(repository.published)
                      .containsExactly(
                          ProbePublication.builder()
                              .claim(claim)
                              .snapshot(claim.request().snapshot())
                              .probeVersion(1)
                              .outcome(outcome)
                              .build()));
    }
  }

  @Test
  @DisplayName("Should schedule a retry without probing when the source does not stabilize")
  void shouldScheduleARetryWithoutProbingWhenTheSourceDoesNotStabilize() {
    try (var dispatcher =
        dispatcher().producer(path -> outcome).stabilityChecker(path -> false).build()) {
      dispatcher.dispatch();

      await().untilAsserted(() -> assertThat(repository.retried).containsExactly(claim));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should schedule a persisted retry when the producer is temporarily unavailable")
  void shouldScheduleAPersistedRetryWhenTheProducerIsTemporarilyUnavailable() {
    try (var dispatcher =
        dispatcher()
            .producer(
                path -> {
                  throw new ProbeExecutionException();
                })
            .stabilityChecker(path -> true)
            .build()) {
      dispatcher.dispatch();

      await().untilAsserted(() -> assertThat(repository.retried).containsExactly(claim));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should leave work unclaimed when all probe execution slots are occupied")
  void shouldLeaveWorkUnclaimedWhenAllProbeExecutionSlotsAreOccupied() throws Exception {
    var secondPath = Files.writeString(directory.resolve("second.mkv"), "second media");
    var secondRequest =
        claim.request().toBuilder()
            .mediaFileId(UUID.randomUUID())
            .filepathUri(FilepathCodec.encode(secondPath))
            .snapshot(
                new SourceFileSnapshot(
                    Files.size(secondPath), Files.getLastModifiedTime(secondPath).toInstant()))
            .build();
    var second =
        ProbeClaim.builder()
            .taskId(UUID.randomUUID())
            .claimId(UUID.randomUUID())
            .request(secondRequest)
            .leaseExpiresAt(claim.leaseExpiresAt())
            .build();
    repository.available.add(second);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var dispatcher =
        dispatcher()
            .producer(
                path -> {
                  started.countDown();
                  awaitRelease(release);
                  return outcome;
                })
            .stabilityChecker(path -> true)
            .maxConcurrent(1)
            .build()) {
      dispatcher.dispatch();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      dispatcher.dispatch();

      assertThat(repository.available).containsExactly(second);
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("Should leave queued work untouched when the dispatcher has shut down")
  void shouldLeaveQueuedWorkUntouchedWhenTheDispatcherHasShutDown() {
    var dispatcher = dispatcher().producer(path -> outcome).stabilityChecker(path -> true).build();
    dispatcher.close();

    dispatcher.dispatch();

    assertThat(repository.available).containsExactly(claim);
  }

  private ProbeTaskDispatcher.ProbeTaskDispatcherBuilder dispatcher() {
    return ProbeTaskDispatcher.builder()
        .coordinator(coordinator)
        .reader(new PersistedProbeReader(id -> Optional.empty()))
        .producer(path -> outcome)
        .fileSystem(FileSystems.getDefault())
        .stabilityChecker(path -> true);
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProbeExecutionException(exception);
    }
  }

  @Test
  @DisplayName(
      "Should reschedule without probing when the source differs from its claimed snapshot")
  void shouldRescheduleWithoutProbingWhenTheSourceDiffersFromItsClaimedSnapshot()
      throws IOException {
    var path = FilepathCodec.decode(FileSystems.getDefault(), claim.request().filepathUri());
    var modified = claim.request().snapshot().modifiedAt().minusSeconds(1);
    Files.setLastModifiedTime(path, FileTime.from(modified));
    try (var dispatcher =
        dispatcher().producer(ignored -> outcome).stabilityChecker(ignored -> true).build()) {
      dispatcher.dispatch();

      await()
          .untilAsserted(
              () ->
                  assertThat(repository.rescheduled)
                      .containsExactly(
                          claim.request().toBuilder()
                              .snapshot(new SourceFileSnapshot(Files.size(path), modified))
                              .build()));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should discard the result and reschedule when the source changes during probing")
  void shouldDiscardTheResultAndRescheduleWhenTheSourceChangesDuringProbing() {
    try (var dispatcher =
        dispatcher()
            .producer(
                path -> {
                  try {
                    Files.writeString(path, "replacement media");
                    return outcome;
                  } catch (IOException exception) {
                    throw new ProbeExecutionException(exception);
                  }
                })
            .stabilityChecker(ignored -> true)
            .build()) {
      dispatcher.dispatch();

      await().untilAsserted(() -> assertThat(repository.rescheduled).hasSize(1));
      assertThat(repository.rescheduled.getFirst().snapshot().size()).isEqualTo(17);
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should complete without probing when the exact terminal outcome already exists")
  void shouldCompleteWithoutProbingWhenTheExactTerminalOutcomeAlreadyExists() {
    var stored =
        MediaFileContainerInfo.builder()
            .mediaFileId(claim.request().mediaFileId())
            .snapshot(claim.request().snapshot())
            .probeVersion(claim.request().probeVersion())
            .probeError(ProbeError.INVALID_MEDIA)
            .build();
    try (var dispatcher =
        dispatcher().reader(new PersistedProbeReader(id -> Optional.of(stored))).build()) {
      dispatcher.dispatch();

      await().untilAsserted(() -> assertThat(repository.completed).containsExactly(claim));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should fail the task without probing when its requested version is unsupported")
  void shouldFailTheTaskWithoutProbingWhenItsRequestedVersionIsUnsupported() {
    repository.available.clear();
    var future =
        ProbeClaim.builder()
            .taskId(claim.taskId())
            .claimId(claim.claimId())
            .request(claim.request().toBuilder().probeVersion(ProbeVersion.CURRENT + 1).build())
            .leaseExpiresAt(claim.leaseExpiresAt())
            .build();
    repository.available.add(future);
    try (var dispatcher = dispatcher().build()) {
      dispatcher.dispatch();

      await().untilAsserted(() -> assertThat(repository.failed).containsExactly(future));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should reschedule for the current producer when the request uses an older version")
  void shouldRescheduleForTheCurrentProducerWhenTheRequestUsesAnOlderVersion() {
    repository.available.clear();
    var older =
        ProbeClaim.builder()
            .taskId(claim.taskId())
            .claimId(claim.claimId())
            .request(claim.request().toBuilder().probeVersion(ProbeVersion.CURRENT - 1).build())
            .leaseExpiresAt(claim.leaseExpiresAt())
            .build();
    repository.available.add(older);
    try (var dispatcher = dispatcher().build()) {
      dispatcher.dispatch();

      await()
          .untilAsserted(() -> assertThat(repository.rescheduled).containsExactly(claim.request()));
      assertThat(repository.published).isEmpty();
    }
  }

  @Test
  @DisplayName("Should renew only executing claims while the dispatcher is running")
  void shouldRenewOnlyExecutingClaimsWhileTheDispatcherIsRunning() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var dispatcher =
        dispatcher()
            .producer(
                path -> {
                  started.countDown();
                  awaitRelease(release);
                  return outcome;
                })
            .build();
    try (dispatcher) {
      dispatcher.heartbeat();
      assertThat(repository.renewed).isEmpty();
      dispatcher.dispatch();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      dispatcher.heartbeat();

      assertThat(repository.renewed).containsExactly(claim);
    } finally {
      release.countDown();
    }

    dispatcher.heartbeat();
    assertThat(repository.renewed).containsExactly(claim);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should discard an interrupted execution when its lease cannot be renewed")
  void shouldDiscardAnInterruptedExecutionWhenItsLeaseCannotBeRenewed(boolean renewalUnavailable)
      throws InterruptedException {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var replacement =
        ProbeClaim.builder()
            .taskId(claim.taskId())
            .claimId(UUID.randomUUID())
            .request(claim.request())
            .leaseExpiresAt(claim.leaseExpiresAt())
            .build();
    try (var dispatcher =
        dispatcher()
            .maxConcurrent(1)
            .producer(
                path -> {
                  if (calls.incrementAndGet() == 1) {
                    started.countDown();
                    try {
                      release.await();
                    } catch (InterruptedException _) {
                      interrupted.countDown();
                      awaitRelease(release);
                    }
                  }

                  return outcome;
                })
            .build()) {
      dispatcher.dispatch();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      repository.renewable = false;
      repository.renewalUnavailable = renewalUnavailable;

      dispatcher.heartbeat();

      assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
      repository.available.add(replacement);
      dispatcher.dispatch();
      assertThat(repository.available).containsExactly(replacement);
      release.countDown();
      await()
          .untilAsserted(
              () -> {
                dispatcher.dispatch();
                assertThat(repository.published)
                    .extracting(ProbePublication::claim)
                    .containsExactly(replacement);
              });
      assertThat(repository.retried).isEmpty();
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @EnumSource(ProbeError.class)
  @DisplayName("Should persist a terminal outcome without retrying when the media cannot be probed")
  void shouldPersistATerminalOutcomeWithoutRetryingWhenTheMediaCannotBeProbed(ProbeError error) {
    var failure = new ProbeOutcome.Failure(error);
    try (var dispatcher = dispatcher().producer(_ -> failure).build()) {
      dispatcher.dispatch();

      await()
          .untilAsserted(
              () ->
                  assertThat(repository.published)
                      .extracting(ProbePublication::outcome)
                      .containsExactly(failure));
      assertThat(repository.retried).isEmpty();
    }
  }

  @Test
  @DisplayName("Should stop renewing a claim when its virtual thread finishes")
  void shouldStopRenewingAClaimWhenItsVirtualThreadFinishes() throws InterruptedException {
    var execution = new AtomicReference<Thread>();
    try (var dispatcher =
        dispatcher()
            .producer(
                _ -> {
                  execution.set(Thread.currentThread());
                  return outcome;
                })
            .build()) {
      dispatcher.dispatch();
      await().untilAsserted(() -> assertThat(repository.published).hasSize(1));
      assertThat(execution.get().isVirtual()).isTrue();
      assertThat(execution.get().join(Duration.ofSeconds(5))).isTrue();

      dispatcher.heartbeat();

      assertThat(repository.renewed).isEmpty();
    }
  }

  private static class ProbeTasks extends FakeFileProcessingTaskRepository {

    private final ConcurrentLinkedQueue<ProbeClaim> available = new ConcurrentLinkedQueue<>();
    private final List<ProbePublication> published = new CopyOnWriteArrayList<>();
    private final List<ProbeClaim> retried = new CopyOnWriteArrayList<>();
    private final List<ProbeRequest> rescheduled = new CopyOnWriteArrayList<>();
    private final List<ProbeClaim> completed = new CopyOnWriteArrayList<>();
    private final List<ProbeClaim> failed = new CopyOnWriteArrayList<>();
    private final List<ProbeClaim> renewed = new CopyOnWriteArrayList<>();
    private volatile boolean renewable = true;
    private volatile boolean renewalUnavailable;

    @Override
    public boolean renewProbe(ProbeClaim currentClaim, Instant leaseExpiresAt) {
      if (renewalUnavailable) {
        throw new DataAccessResourceFailureException("Database unavailable");
      }

      if (!renewable) {
        return false;
      }

      renewed.add(currentClaim);
      return true;
    }

    @Override
    public boolean failProbe(ProbeClaim currentClaim, String errorMessage) {
      failed.add(currentClaim);
      return true;
    }

    @Override
    public boolean completeProbe(ProbeClaim currentClaim) {
      completed.add(currentClaim);
      return true;
    }

    @Override
    public Optional<ProbeClaim> claimProbeTask(String instanceId, Instant leaseExpiresAt) {
      return Optional.ofNullable(available.poll());
    }

    @Override
    public boolean publishProbe(ProbePublication publication) {
      published.add(publication);
      return true;
    }

    @Override
    public boolean retryProbe(ProbeClaim retry, String errorMessage, Instant retryAt) {
      retried.add(retry);
      return true;
    }

    @Override
    public boolean rescheduleProbe(ProbeClaim currentClaim, ProbeRequest replacement) {
      rescheduled.add(replacement);
      return true;
    }
  }
}
