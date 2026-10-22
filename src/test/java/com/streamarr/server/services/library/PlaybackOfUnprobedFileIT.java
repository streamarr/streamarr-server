package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.ProbeWorkers;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.support.ControlledClockConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("Playback of a file without a probe outcome")
@Import(ControlledClockConfiguration.class)
class PlaybackOfUnprobedFileIT extends AbstractProbeSchedulerIntegrationTest {

  private static final int QUEUED_BY_SCAN = 20;
  private static final long BOUND_SECONDS = 30;
  private static final ConnectedProbeWorkers PROBE_WORKERS = new ConnectedProbeWorkers();

  @Autowired private StreamingService streamingService;
  @Autowired private ProbeTaskRequests scheduling;
  @Autowired private MutableClock clock;
  @Autowired private ProbeSchedulingProperties probeScheduling;
  @Autowired private StreamingProperties streamingProperties;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;
  @TestBean ProbeWorkers probeWorkers;

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }

  static PlaybackAuthorityGate authorityGate() {
    return (_, _) -> true;
  }

  static ProbeWorkers probeWorkers() {
    return PROBE_WORKERS;
  }

  private final HeldWorker worker = new HeldWorker();
  private final ExecutorService viewers = Executors.newVirtualThreadPerTaskExecutor();

  @BeforeEach
  void connectProbeWorkerAndStartTheClockBetweenMicroseconds() {
    PROBE_WORKERS.reset(true);
    // A Linux clock reads in nanoseconds, and PostgreSQL keeps each time to the nearest
    // microsecond. Each test starts 400 ns past a microsecond, so a time read back from the
    // database rounds down, as it does for most Linux readings.
    var pastMicrosecond = clock.instant().getNano() % 1_000;
    clock.advance(Duration.ofNanos(Math.floorMod(400 - pastMicrosecond, 1_000)));
  }

  @AfterEach
  void releaseWorkerAndViewers() throws InterruptedException {
    worker.releaseAll();
    viewers.shutdownNow();
    assertThat(viewers.awaitTermination(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("Should start the stream session when the worker returns the probe of the file")
  void shouldStartTheStreamSessionWhenTheWorkerReturnsTheProbeOfTheFile() throws Exception {
    var file = createUnchangedFiles(1).getFirst();
    worker.answerAtOnce(file);
    startScheduler();

    var playback = createSession(file);
    pickUpPlaybackRequests(1);
    var session = accepted(playback.get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertThat(session.getMediaFileId()).isEqualTo(file.mediaFileId());
    assertThat(reader.find(file.mediaFileId())).isPresent();
  }

  @Test
  @DisplayName(
      "Should probe the requested file next and start its session when the worker frees capacity")
  void shouldProbeTheRequestedFileNextAndStartItsSessionWhenTheWorkerFreesCapacity()
      throws Exception {
    var files = createUnchangedFiles(QUEUED_BY_SCAN + 1);
    var holdingCapacity = files.getFirst();
    var queuedByScan = files.subList(1, files.size());
    var requested = queuedByScan.getLast();
    scheduling.request(holdingCapacity);
    startScheduler();
    worker.awaitStarted(holdingCapacity);
    queuedByScan.forEach(scheduling::request);
    worker.answerAtOnce(requested);

    var playback = createSession(requested);
    PROBE_WORKERS.awaitAsked(1);
    worker.release(holdingCapacity);
    var firstTwoDispatched = worker.awaitStarts(2);
    var session = accepted(playback.get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertThat(firstTwoDispatched).containsExactly(sourceOf(holdingCapacity), sourceOf(requested));
    assertThat(session.getMediaFileId()).isEqualTo(requested.mediaFileId());
    assertThat(worker.finished()).containsExactly(sourceOf(holdingCapacity), sourceOf(requested));
  }

  @Test
  @DisplayName(
      "Should refuse as not ready when the wait reaches the limit and finish the probe afterwards")
  void shouldRefuseAsNotReadyWhenTheWaitReachesTheLimitAndFinishTheProbeAfterwards()
      throws Exception {
    var file = createUnchangedFiles(1).getFirst();
    // The first attempt finds every worker busy after the wait limit has passed.
    worker.answerWith(
        file,
        attempt -> {
          if (attempt == 1) {
            clock.advance(streamingProperties.probeWaitLimit());
            throw new ProbeWorkersBusyException();
          }

          return null;
        });
    var completions = new Semaphore(0);
    startScheduler(
        probeExecution.toBuilder().producer(worker).build(),
        releasingOnCompletion(completions),
        clock);

    var playback = createSession(file);
    pickUpPlaybackRequests(1);
    var refused = playback.get(BOUND_SECONDS, TimeUnit.SECONDS);
    // The playback can refuse before the busy attempt ends. When the attempt ends, the scheduler
    // sets the retry time from the clock. If the test advanced the clock first, the retry would
    // never become due.
    awaitCompletion(completions);
    clock.advance(probeScheduling.busyWorkerRetryDelay());
    checkForDueExecutions();
    worker.awaitFinished(file);

    assertThat(refused).isEqualTo(notReady());
    assertThat(accepted(createSession(file).get(BOUND_SECONDS, TimeUnit.SECONDS)).getMediaFileId())
        .isEqualTo(file.mediaFileId());
  }

  @Test
  @DisplayName(
      "Should refuse as not ready at once without a compatible worker and probe the file first"
          + " when one connects")
  void shouldRefuseAsNotReadyAtOnceWithoutACompatibleWorkerAndProbeTheFileFirstWhenOneConnects()
      throws Exception {
    var queuedByScan = createUnchangedFiles(QUEUED_BY_SCAN);
    var requested = queuedByScan.getLast();
    queuedByScan.forEach(scheduling::request);
    PROBE_WORKERS.reset(false);

    var refused = createSession(requested).get(BOUND_SECONDS, TimeUnit.SECONDS);
    PROBE_WORKERS.reset(true);
    startScheduler();

    assertThat(refused).isEqualTo(notReady());
    assertThat(worker.awaitStarts(1)).containsExactly(sourceOf(requested));
  }

  @Test
  @DisplayName(
      "Should refuse as not ready when the probe attempt fails and keep the probe's backoff")
  void shouldRefuseAsNotReadyWhenTheProbeAttemptFailsAndKeepTheProbesBackoff() throws Exception {
    var file = createUnchangedFiles(1).getFirst();
    worker.answerWith(
        file,
        _ -> {
          throw new ProbeExecutionException(
              ItemFailureReason.SOURCE_INACCESSIBLE, "The worker could not read the source");
        });
    var client = startScheduler();

    var playback = createSession(file);
    pickUpPlaybackRequests(1);
    var refused = playback.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertThat(refused).isEqualTo(notReady());
    assertThat(client.getScheduledExecution(instanceOf(file)))
        .hasValueSatisfying(
            retry -> {
              assertThat(retry.getConsecutiveFailures()).isOne();
              assertThat(retry.getExecutionTime())
                  .isCloseTo(
                      clock.instant().plus(CappedExponentialBackoff.delayAfter(0)),
                      within(1, ChronoUnit.MICROS));
            });
  }

  @Test
  @DisplayName("Should run one probe and start both sessions when two viewers play the same file")
  void shouldRunOneProbeAndStartBothSessionsWhenTwoViewersPlayTheSameFile() throws Exception {
    var file = createUnchangedFiles(1).getFirst();
    var client = startScheduler();

    var firstViewer = createSession(file);
    var secondViewer = createSession(file);
    pickUpPlaybackRequests(2);
    worker.awaitStarted(file);
    worker.release(file);
    var first = accepted(firstViewer.get(BOUND_SECONDS, TimeUnit.SECONDS));
    var second = accepted(secondViewer.get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertThat(List.of(first.getMediaFileId(), second.getMediaFileId()))
        .containsOnly(file.mediaFileId());
    assertThat(first.getSessionId()).isNotEqualTo(second.getSessionId());
    assertThat(worker.attempts(file)).isOne();
    assertThat(client.getScheduledExecution(instanceOf(file))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should not start a second probe when playback requests a file whose probe is running")
  void shouldNotStartASecondProbeWhenPlaybackRequestsAFileWhoseProbeIsRunning() throws Exception {
    var file = createUnchangedFiles(1).getFirst();
    startScheduler();
    var firstViewer = createSession(file);
    pickUpPlaybackRequests(1);
    worker.awaitStarted(file);

    var secondViewer = createSession(file);
    pickUpPlaybackRequests(1);
    worker.release(file);
    accepted(firstViewer.get(BOUND_SECONDS, TimeUnit.SECONDS));
    accepted(secondViewer.get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertThat(worker.attempts(file)).isOne();
  }

  @Test
  @DisplayName(
      "Should probe the requested file next when the scan's running probe of it finds every worker"
          + " busy")
  void shouldProbeTheRequestedFileNextWhenTheScansRunningProbeOfItFindsEveryWorkerBusy()
      throws Exception {
    var files = createUnchangedFiles(QUEUED_BY_SCAN + 1);
    var requested = files.getFirst();
    var findWorkersBusy = new CompletableFuture<Void>();
    worker.answerWith(
        requested,
        attempt -> {
          if (attempt == 1) {
            findWorkersBusy.orTimeout(BOUND_SECONDS, TimeUnit.SECONDS).join();
            throw new ProbeWorkersBusyException();
          }

          return null;
        });
    var completions = new Semaphore(0);
    scheduling.request(requested);
    startScheduler(
        probeExecution.toBuilder().producer(worker).build(),
        releasingOnCompletion(completions),
        clock);
    worker.awaitStarted(requested);
    files.subList(1, files.size()).forEach(scheduling::request);

    var playback = createSession(requested);
    PROBE_WORKERS.awaitAsked(1);
    findWorkersBusy.complete(null);
    awaitCompletion(completions);
    var holdingCapacity = worker.awaitStarts(2).getLast();
    clock.advance(probeScheduling.busyWorkerRetryDelay());
    worker.release(holdingCapacity);
    awaitCompletion(completions);
    worker.awaitStarts(1);

    assertThat(worker.started().get(2)).isEqualTo(sourceOf(requested));
    assertThat(accepted(playback.get(BOUND_SECONDS, TimeUnit.SECONDS)).getMediaFileId())
        .isEqualTo(requested.mediaFileId());
  }

  // The scheduler polls once the thread that ran the completed attempt is free.
  private void awaitCompletion(Semaphore completions) throws InterruptedException {
    assertThat(completions.tryAcquire(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    checkForDueExecutions();
  }

  private static AbstractSchedulerListener releasingOnCompletion(Semaphore completions) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        completions.release();
      }
    };
  }

  // The test scheduler is not the client that stores the requests, and its frozen clock never
  // ends a polling interval, so it polls when told to.
  private void pickUpPlaybackRequests(int requests) throws InterruptedException {
    PROBE_WORKERS.awaitAsked(requests);
    checkForDueExecutions();
  }

  @Override
  ProbeSchedulerStarter probeSchedulerStarter() {
    return super.probeSchedulerStarter().toBuilder().threads(1).build();
  }

  private SchedulerClient startScheduler() {
    return startScheduler(
        probeExecution.toBuilder().producer(worker).build(),
        new AbstractSchedulerListener() {},
        clock);
  }

  private CompletableFuture<Outcome<StreamSession, CreateStreamSessionRejection>> createSession(
      ProbeTaskRequest file) {
    var command =
        createStreamSessionCommand(
            file.mediaFileId(),
            UUID.randomUUID(),
            StreamingOptions.builder()
                .quality(VideoQuality.AUTO)
                .supportedCodecs(List.of("h264"))
                .build());
    return CompletableFuture.supplyAsync(() -> streamingService.createSession(command), viewers);
  }

  private static Path sourceOf(ProbeTaskRequest file) {
    return FilepathCodec.decode(file.filepathUri());
  }

  private static Outcome<StreamSession, CreateStreamSessionRejection> notReady() {
    return Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady());
  }

  /** Counts the probe requests that asked whether a compatible worker is connected. */
  private static final class ConnectedProbeWorkers implements ProbeWorkers {

    private volatile boolean connected;
    private volatile Semaphore asked = new Semaphore(0);

    @Override
    public boolean hasCompatibleWorker(int probeVersion) {
      asked.release();
      return connected && probeVersion == ProbeVersion.CURRENT;
    }

    void reset(boolean connected) {
      this.connected = connected;
      this.asked = new Semaphore(0);
    }

    // Playback asks after the library saved its probe request, so the request is stored by then.
    void awaitAsked(int requests) throws InterruptedException {
      assertThat(asked.tryAcquire(requests, BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
  }

  /**
   * A worker with one probe slot per scheduler thread. It holds each probe until the test releases
   * it, unless the test answers that file's probe at once or with its own answer.
   */
  private static final class HeldWorker implements FfprobeService {

    private final FakeFfprobeService results = new FakeFfprobeService();
    private final List<Path> started = new CopyOnWriteArrayList<>();
    private final Semaphore starts = new Semaphore(0);
    private final List<Path> finished = new CopyOnWriteArrayList<>();
    private final Map<Path, CompletableFuture<Void>> startedProbes = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> finishedProbes = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> releases = new ConcurrentHashMap<>();
    private final Map<Path, Function<Integer, ProbeOutcome>> answers = new ConcurrentHashMap<>();
    private final Map<Path, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private volatile boolean releasedAll;

    @Override
    public ProbeOutcome probe(ProbeExecutionRequest request) {
      var source = request.sourcePath();
      var attempt = attempts.computeIfAbsent(source, _ -> new AtomicInteger()).incrementAndGet();
      started.add(source);
      starts.release();
      futureOf(startedProbes, source).complete(null);
      var answer = answers.get(source);
      if (answer == null) {
        awaitRelease(source);
        return finish(request);
      }

      var outcome = answer.apply(attempt);
      if (outcome == null) {
        return finish(request);
      }

      return outcome;
    }

    private ProbeOutcome finish(ProbeExecutionRequest request) {
      var outcome = results.probe(request);
      finished.add(request.sourcePath());
      futureOf(finishedProbes, request.sourcePath()).complete(null);
      return outcome;
    }

    // Registering the release before reading releasedAll keeps releaseAll from missing it.
    private void awaitRelease(Path source) {
      var release = futureOf(releases, source);
      if (releasedAll) {
        return;
      }

      try {
        release.get();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new ProbeCancelledException("The held probe was cancelled", exception);
      } catch (ExecutionException exception) {
        throw new IllegalStateException(exception);
      }
    }

    void answerAtOnce(ProbeTaskRequest file) {
      answerWith(file, _ -> null);
    }

    // An answer that returns null finishes with the default probe result.
    void answerWith(ProbeTaskRequest file, Function<Integer, ProbeOutcome> answer) {
      answers.put(sourceOf(file), answer);
    }

    void release(ProbeTaskRequest file) {
      release(sourceOf(file));
    }

    void release(Path source) {
      futureOf(releases, source).complete(null);
    }

    void releaseAll() {
      releasedAll = true;
      releases.values().forEach(release -> release.complete(null));
    }

    void awaitStarted(ProbeTaskRequest file) throws Exception {
      futureOf(startedProbes, sourceOf(file)).get(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    void awaitFinished(ProbeTaskRequest file) throws Exception {
      futureOf(finishedProbes, sourceOf(file)).get(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    /** Waits until the worker has started this many probes, and returns them in start order. */
    List<Path> awaitStarts(int count) throws InterruptedException {
      assertThat(starts.tryAcquire(count, BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
      return List.copyOf(started).subList(0, count);
    }

    List<Path> started() {
      return List.copyOf(started);
    }

    List<Path> finished() {
      return List.copyOf(finished);
    }

    int attempts(ProbeTaskRequest file) {
      return attempts.getOrDefault(sourceOf(file), new AtomicInteger()).get();
    }

    private static CompletableFuture<Void> futureOf(
        Map<Path, CompletableFuture<Void>> futures, Path source) {
      return futures.computeIfAbsent(source, _ -> new CompletableFuture<>());
    }
  }
}
