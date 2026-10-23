package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.WorkerProbeResultFixture.version4MediaBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.v1.ProbePriority.PROBE_PRIORITY_BACKGROUND;
import static com.streamarr.transcode.v1.ProbePriority.PROBE_PRIORITY_PLAYBACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.config.ProbeCapacityJdbcCustomization;
import com.streamarr.server.config.ProbeSchedulingConfiguration;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.services.streaming.remote.ProbeDispatch;
import com.streamarr.server.services.streaming.remote.ProbeRefusal;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("Probe scheduling against busy workers")
class SchedulerProbeWorkerCapacityIT extends AbstractIntegrationTest {

  private static final Duration WORKER_WAIT = Duration.ofSeconds(20);
  private static final UUID OTHER_WORKER_ID =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  // More threads than any test has probes, so only the workers' probe capacity limits the picks.
  private static final int THREADS_BEYOND_PROBE_CAPACITY = 32;

  @TempDir Path sourceRoot;

  @Autowired private ProbeTaskRequests scheduling;
  @Autowired private DataSource dataSource;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TaskRepository probeTasks;
  @Autowired private ProbeSchedulingProperties probeScheduling;
  @Autowired private LibraryWatcherProperties watcherProperties;
  @Autowired private DbSchedulerCustomizer schedulerCustomizer;
  @Autowired private Environment environment;
  @Autowired private PlaybackProbeClaims playbackProbeClaims;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final BlockingQueue<ExecutionComplete> completions = new LinkedBlockingQueue<>();
  private MutableClock clock;
  private UUID libraryId;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    libraryId = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()).getId();
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    mediaFiles.deleteAll(createdFiles);
    libraries.deleteById(libraryId);
  }

  @Test
  @DisplayName(
      "Should keep the busy probe pending for the retry delay when one unit of probe capacity"
          + " serves two probes")
  void shouldKeepBusyProbePendingForRetryDelayWhenOneUnitOfProbeCapacityServesTwoProbes()
      throws Exception {
    whenOneUnitOfProbeCapacityServesTwoProbes(
        busy -> {
          assertThat(busy.deferred().getResult())
              .as("A busy worker is not a failed probe")
              .isEqualTo(ExecutionComplete.Result.OK);
          assertThat(
                  busy.client().getScheduledExecution(busy.deferred().getExecution().taskInstance))
              .as("The busy probe must remain pending in db-scheduler")
              .hasValueSatisfying(
                  pending -> {
                    assertThat(pending.isPicked()).isFalse();
                    assertThat(pending.getConsecutiveFailures()).isZero();
                    assertThat(pending.getExecutionTime())
                        .isEqualTo(
                            busy.deferred()
                                .getTimeDone()
                                .plus(probeScheduling.busyWorkerRetryDelay()));
                  });
          assertThat(reader.find(mediaFileIdOf(busy.deferred())))
              .as("Releasing the scheduler slot must not record a probe outcome")
              .isEmpty();
        });
  }

  @Test
  @DisplayName(
      "Should release the scheduler slot when a busy probe defers while the other probe keeps"
          + " running")
  void shouldReleaseSchedulerSlotWhenBusyProbeDefersWhileOtherProbeKeepsRunning() throws Exception {
    whenOneUnitOfProbeCapacityServesTwoProbes(
        busy -> {
          advanceClock(probeScheduling.busyWorkerRetryDelay());

          var retried = nextCompletion();

          assertThat(
                  busy.client().getScheduledExecution(busy.deferred().getExecution().taskInstance))
              .as(
                  "The running probe holds one of the two scheduler slots, so the deferred probe"
                      + " can defer again only after running in the slot its deferral released")
              .hasValueSatisfying(
                  pending ->
                      assertThat(pending.getExecutionTime())
                          .isEqualTo(
                              retried.getTimeDone().plus(probeScheduling.busyWorkerRetryDelay())));
        });
  }

  @Test
  @DisplayName(
      "Should store both probes when the held probe releases the only unit of probe capacity")
  void shouldStoreBothProbesWhenHeldProbeReleasesTheOnlyUnitOfProbeCapacity() throws Exception {
    whenOneUnitOfProbeCapacityServesTwoProbes(
        busy -> {
          busy.worker().reply(success(busy.held()));
          var held = nextCompletion();
          advanceClock(probeScheduling.busyWorkerRetryDelay());
          busy.worker()
              .reply(success(busy.worker().nextResponse(WORKER_WAIT).getStartProbe().getRequest()));
          var retried = nextCompletion();

          assertThat(List.of(busy.deferred(), held, retried))
              .extracting(ExecutionComplete::getResult)
              .containsOnly(ExecutionComplete.Result.OK);
          for (var request : busy.requests()) {
            assertThat(reader.find(request.mediaFileId())).isPresent();
            assertThat(busy.client().getScheduledExecution(instanceOf(request))).isEmpty();
          }
        });
  }

  @Test
  @DisplayName(
      "Should keep earlier probe failures when busy workers defer the probe before a real failure")
  void shouldKeepEarlierProbeFailuresWhenBusyWorkersDeferProbeBeforeRealFailure() throws Exception {
    var request = request(createMediaFile("failing.mkv"));
    scheduling.request(request);
    var earlierFailure = Instant.parse("2026-09-01T12:00:00Z");
    dsl.update(DSL.table("scheduled_tasks"))
        .set(DSL.field("consecutive_failures", Integer.class), 2)
        .set(DSL.field("last_failure", Instant.class), earlierFailure)
        .where(DSL.field("task_instance", String.class).eq(request.mediaFileId().toString()))
        .execute();
    var attempts = new AtomicInteger();
    FfprobeService producer =
        _ -> {
          if (attempts.incrementAndGet() == 1) {
            throw new ProbeWorkersBusyException("All compatible workers are busy");
          }

          throw new ProbeExecutionException(ItemFailureReason.TEMPORARY, "Worker lost the probe");
        };
    var client = startScheduler(producer);

    var deferred = nextCompletion();

    assertThat(deferred.getResult()).isEqualTo(ExecutionComplete.Result.OK);
    assertThat(client.getScheduledExecution(instanceOf(request)))
        .as("A busy deferral must neither clear nor add to the failure history")
        .hasValueSatisfying(
            pending -> {
              assertThat(pending.getConsecutiveFailures()).isEqualTo(2);
              assertThat(pending.getLastFailure()).isEqualTo(earlierFailure);
              assertThat(pending.getLastSuccess()).isNull();
            });

    advanceClock(probeScheduling.busyWorkerRetryDelay());
    var failed = nextCompletion();

    assertThat(failed.getResult()).isEqualTo(ExecutionComplete.Result.FAILED);
    assertThat(client.getScheduledExecution(instanceOf(request)))
        .as("The third consecutive failure backs off 20 seconds")
        .hasValueSatisfying(
            pending -> {
              assertThat(pending.getConsecutiveFailures()).isEqualTo(3);
              assertThat(pending.getExecutionTime())
                  .isEqualTo(failed.getTimeDone().plus(Duration.ofSeconds(20)));
            });
  }

  @Test
  @DisplayName(
      "Should store the probe failure as final and schedule no retry when the worker reports a"
          + " result too large for one session message")
  void shouldStoreProbeFailureAsFinalAndScheduleNoRetryWhenWorkerReportsResultTooLarge()
      throws Exception {
    var request = request(createMediaFile("crowded.mkv"));
    scheduling.request(request);

    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(ProbeVersion.CURRENT)
              .availableSlots(1)
              .backgroundProbeCapacity(1)
              .build()) {
        var client =
            startScheduler(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot));
        var dispatched = worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest();

        worker.reply(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(dispatched.getProbeAttemptId())
                .setProbeVersion(dispatched.getProbeVersion())
                .setFailure(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE)
                .build());

        assertThat(nextCompletion().getResult())
            .as("A result too large for one session message is not a retryable failure")
            .isEqualTo(ExecutionComplete.Result.OK);
        assertThat(reader.find(request.mediaFileId()))
            .hasValueSatisfying(
                stored -> {
                  assertThat(stored.outcome())
                      .isEqualTo(new ProbeOutcome.Failure(ProbeError.RESULT_TOO_LARGE));
                  assertThat(stored.matches(request.snapshot(), request.probeVersion())).isTrue();
                });
        assertThat(client.getScheduledExecution(instanceOf(request)))
            .as("The stored failure must leave no retry scheduled")
            .isEmpty();
      }
    }
  }

  // The scheduler picks probes against a fixed capacity that does not see the busy worker, so the
  // worker refuses the second probe at dispatch. A pick meets a busy worker the same way when a
  // keyframe packet scan takes the last unit of probe capacity between the pick and the dispatch.
  @Test
  @DisplayName(
      "Should start the next due probe at the next poll, without the busy worker retry delay,"
          + " when a running probe finishes")
  void
      shouldStartTheNextDueProbeAtTheNextPollWithoutTheBusyWorkerRetryDelayWhenARunningProbeFinishes()
          throws Exception {
    var first = request(createMediaFile("first.mkv"));
    var next = request(createMediaFile("next.mkv"));
    List.of(first, next).forEach(scheduling::request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var worker = probeWorker(server).backgroundProbeCapacity(1).build()) {
      var client = startSchedulerPickingFrom(server);
      var running = worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest();
      assertThat(running.getSource().getRelativeKey()).isEqualTo("first.mkv");
      assertThat(client.getScheduledExecution(instanceOf(next)))
          .as("The next probe waits unpicked, without a failure or a later execution time")
          .hasValueSatisfying(
              pending -> {
                assertThat(pending.isPicked()).isFalse();
                assertThat(pending.getConsecutiveFailures()).isZero();
                assertThat(pending.getExecutionTime()).isBeforeOrEqualTo(clock.instant());
              });
      var pollTime = clock.instant();

      worker.reply(invalidMedia(running));
      assertThat(nextCompletion().getResult()).isEqualTo(ExecutionComplete.Result.OK);
      scheduler.triggerCheckForDueExecutions();

      assertThat(
              worker
                  .nextResponse(WORKER_WAIT)
                  .getStartProbe()
                  .getRequest()
                  .getSource()
                  .getRelativeKey())
          .as("The poll right after the finished probe starts the next probe")
          .isEqualTo("next.mkv");
      assertThat(clock.instant()).isEqualTo(pollTime);
      assertThat(deferred(meterRegistry, ProbeRefusal.WORKERS_BUSY)).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should run eight probes at once and keep the ninth waiting when two workers advertise probe"
          + " capacities of three and five")
  void shouldRunEightProbesAtOnceAndKeepTheNinthWaitingWhenTwoWorkersAdvertiseThreeAndFive()
      throws Exception {
    var requests = new ArrayList<ProbeTaskRequest>();
    for (var index = 0; index < 9; index++) {
      requests.add(request(createMediaFile("probe-" + index + ".mkv")));
    }

    requests.forEach(scheduling::request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var three = probeWorker(server).backgroundProbeCapacity(3).build();
        var five =
            probeWorker(server).workerId(OTHER_WORKER_ID).backgroundProbeCapacity(5).build()) {
      startSchedulerPickingFrom(server);
      var onThree = startedProbes(three, 3);
      startedProbes(five, 5);
      assertThat(server.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT).runningProbes())
          .isEqualTo(8);

      three.reply(invalidMedia(onThree.getFirst()));
      assertThat(nextCompletion().getResult()).isEqualTo(ExecutionComplete.Result.OK);
      var pollingInterval = schedulerProperties().getPollingInterval();
      assertThat(pollingInterval).isLessThan(probeScheduling.busyWorkerRetryDelay());
      advanceClock(pollingInterval);

      assertThat(three.nextResponse(WORKER_WAIT).hasStartProbe())
          .as(
              "The ninth probe starts at the next poll in the unit that the finished probe freed,"
                  + " without the busy-worker retry delay")
          .isTrue();
      assertThat(deferred(meterRegistry, ProbeRefusal.WORKERS_BUSY)).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should start a probe when a keyframe packet scan uses the only keyframe packet"
          + " scan unit")
  void shouldStartAProbeWhenAKeyframePacketScanUsesTheOnlyKeyframePacketScanUnit()
      throws Exception {
    var requests =
        List.of(request(createMediaFile("first.mkv")), request(createMediaFile("second.mkv")));
    requests.forEach(scheduling::request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var worker = probeWorker(server).backgroundProbeCapacity(1).build()) {
      var packetScan =
          ProbeRequest.newBuilder()
              .setProbeAttemptId(toProto(UUID.randomUUID()))
              .setProbeVersion(ProbeVersion.CURRENT)
              .setScanKeyframePackets(true)
              .setSource(
                  MediaSourceRef.newBuilder()
                      .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                      .setRelativeKey("scanned.mkv"))
              .build();
      assertThat(server.dispatchProbe(packetScan)).isInstanceOf(ProbeDispatch.Dispatched.class);
      assertThat(worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest())
          .isEqualTo(packetScan);

      startSchedulerPickingFrom(server);
      var probe = worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest();

      assertThat(probe.getScanKeyframePackets()).isFalse();
      assertThat(server.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT))
          .as("The probe and the keyframe packet scan run at once in their own units")
          .returns(1, ProbeCapacity::runningProbes)
          .returns(1, ProbeCapacity::runningKeyframePacketScans);

      worker.reply(invalidMedia(probe));
      assertThat(nextCompletion().getResult()).isEqualTo(ExecutionComplete.Result.OK);
      scheduler.triggerCheckForDueExecutions();

      assertThat(
              worker
                  .nextResponse(WORKER_WAIT)
                  .getStartProbe()
                  .getRequest()
                  .getSource()
                  .getRelativeKey())
          .as("The second probe starts in the background unit that the first probe freed")
          .isNotEqualTo(probe.getSource().getRelativeKey());
      assertThat(deferred(meterRegistry, ProbeRefusal.WORKERS_BUSY)).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should keep the probe pending without a failure and send it to another worker when the"
          + " first worker reports no free capacity for it")
  void shouldSendTheProbeToAnotherWorkerWhenTheFirstWorkerReportsNoFreeCapacityForIt()
      throws Exception {
    var request = request(createMediaFile("refused.mkv"));
    scheduling.request(request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var first = probeWorker(server).backgroundProbeCapacity(1).build();
        var second =
            probeWorker(server).workerId(OTHER_WORKER_ID).backgroundProbeCapacity(0).build()) {
      var client = startSchedulerPickingFrom(server);
      var refused = first.nextResponse(WORKER_WAIT).getStartProbe().getRequest();
      // The first worker lowered its capacity while the server sent the probe.
      first.changeProbeCapacities(0, 1);
      second.changeProbeCapacities(2, 1);
      first.reply(
          ProbeAttemptResult.newBuilder()
              .setProbeAttemptId(refused.getProbeAttemptId())
              .setProbeVersion(refused.getProbeVersion())
              .setFailure(ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY)
              .build());
      var deferral = nextCompletion();

      assertThat(deferral.getResult()).isEqualTo(ExecutionComplete.Result.OK);
      assertThat(client.getScheduledExecution(instanceOf(request)))
          .as("The refused probe waits for the busy-worker retry delay without a failure")
          .hasValueSatisfying(
              pending -> {
                assertThat(pending.isPicked()).isFalse();
                assertThat(pending.getConsecutiveFailures()).isZero();
                assertThat(pending.getExecutionTime())
                    .isEqualTo(deferral.getTimeDone().plus(probeScheduling.busyWorkerRetryDelay()));
              });
      assertThat(reader.find(request.mediaFileId())).isEmpty();
      // Only both changes together report a background probe capacity of two.
      awaitProbeCapacity(server, 2);
      advanceClock(probeScheduling.busyWorkerRetryDelay());
      assertThat(second.nextResponse(WORKER_WAIT).getStartProbe().getRequest().getSource())
          .returns("refused.mkv", MediaSourceRef::getRelativeKey);
      assertThat(first.hasNoResponse()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should start no second probe when playback requests a file whose background probe is"
          + " running")
  void shouldStartNoSecondProbeWhenPlaybackRequestsAFileWhoseBackgroundProbeIsRunning()
      throws Exception {
    var request = request(createMediaFile("running.mkv"));
    scheduling.request(request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var worker = probeWorker(server).backgroundProbeCapacity(2).build()) {
      var polls = new Semaphore(0);
      startSchedulerPickingFrom(server, polls);
      var running = worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest();

      scheduling.requestAheadOfQueuedProbes(request);
      awaitCompletedPoll(polls);

      assertThat(worker.hasNoResponse())
          .as("The playback request waits for the running probe")
          .isTrue();
      worker.reply(success(running));
      assertThat(nextCompletion().getResult()).isEqualTo(ExecutionComplete.Result.OK);
      assertThat(reader.find(request.mediaFileId())).isPresent();
      awaitCompletedPoll(polls);
      assertThat(worker.hasNoResponse())
          .as("The stored result answers the playback request without a second probe")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should claim a playback probe ahead of older background probes, and one"
          + " background probe, when one background unit is free")
  void shouldClaimAPlaybackProbeAheadOfOlderBackgroundProbesWhenOneBackgroundUnitIsFree()
      throws Exception {
    for (var index = 0; index < 3; index++) {
      scheduling.request(request(createMediaFile("background-" + index + ".mkv")));
    }

    scheduling.requestAheadOfQueuedProbes(request(createMediaFile("playback.mkv")));
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var worker = probeWorker(server).backgroundProbeCapacity(1).build()) {
      var polls = new Semaphore(0);
      startSchedulerPickingFrom(server, polls);
      var started = startedProbes(worker, 2);
      awaitCompletedPoll(polls);

      assertThat(started)
          .filteredOn(probe -> probe.getSource().getRelativeKey().equals("playback.mkv"))
          .singleElement()
          .returns(PROBE_PRIORITY_PLAYBACK, ProbeRequest::getPriority);
      assertThat(started)
          .filteredOn(probe -> probe.getSource().getRelativeKey().startsWith("background-"))
          .singleElement()
          .returns(PROBE_PRIORITY_BACKGROUND, ProbeRequest::getPriority);
      assertThat(worker.hasNoResponse())
          .as("The free background unit takes one background probe, and the others wait unclaimed")
          .isTrue();
      assertThat(deferred(meterRegistry, ProbeRefusal.WORKERS_BUSY)).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should keep a probe pending without a failure when no connected worker can run"
          + " probes, until one connects")
  void shouldKeepAProbePendingWithoutAFailureWhenNoConnectedWorkerCanRunProbes() throws Exception {
    var request = request(createMediaFile("waiting.mkv"));
    scheduling.request(request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry)) {
      var polls = new Semaphore(0);
      var client = startSchedulerPickingFrom(server, polls);
      awaitCompletedPoll(polls);
      assertProbePendingWithoutFailure(client, request);

      try (var worker = probeWorker(server).backgroundProbeCapacity(1).build()) {
        scheduler.triggerCheckForDueExecutions();

        assertThat(
                worker
                    .nextResponse(WORKER_WAIT)
                    .getStartProbe()
                    .getRequest()
                    .getSource()
                    .getRelativeKey())
            .as("The poll after the worker connects starts the waiting probe")
            .isEqualTo("waiting.mkv");
      }
    }
  }

  @Test
  @DisplayName(
      "Should keep a probe pending without a failure when the worker grants no"
          + " background probe unit, until it raises its capacity")
  void shouldKeepAProbePendingWithoutAFailureWhenTheWorkerGrantsNoBackgroundProbeUnit()
      throws Exception {
    var request = request(createMediaFile("waiting.mkv"));
    scheduling.request(request);
    var meterRegistry = new SimpleMeterRegistry();

    try (var server = startedServer(meterRegistry);
        var worker = probeWorker(server).backgroundProbeCapacity(0).build()) {
      var polls = new Semaphore(0);
      var client = startSchedulerPickingFrom(server, polls);
      awaitCompletedPoll(polls);
      assertProbePendingWithoutFailure(client, request);
      assertThat(worker.hasNoResponse()).as("The worker received no probe").isTrue();

      worker.changeProbeCapacities(1, 1);
      awaitProbeCapacity(server, 1);

      assertThat(
              worker
                  .nextResponse(WORKER_WAIT)
                  .getStartProbe()
                  .getRequest()
                  .getSource()
                  .getRelativeKey())
          .as("The poll after the worker raises its probe capacity starts the waiting probe")
          .isEqualTo("waiting.mkv");
    }
  }

  // The scheduler reads the probe capacity at the start of each poll, and one thread runs the
  // polls. A trigger during a poll starts the next poll as soon as it ends, so the second poll that
  // this wait triggers shows that a poll started after the wait began has finished.
  private void awaitCompletedPoll(Semaphore polls) throws InterruptedException {
    polls.drainPermits();
    scheduler.triggerCheckForDueExecutions();
    assertThat(polls.tryAcquire(WORKER_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    scheduler.triggerCheckForDueExecutions();
    assertThat(polls.tryAcquire(WORKER_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  // The worker's capacity change arrives on its session with no signal to the test, so the test
  // waits until the server reports the new capacity, then polls once.
  private void awaitProbeCapacity(WorkerSessionServer server, int advertised) {
    await()
        .atMost(WORKER_WAIT)
        .until(
            () ->
                server
                        .probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT)
                        .backgroundProbeCapacity()
                    == advertised);
    scheduler.triggerCheckForDueExecutions();
  }

  private void assertProbePendingWithoutFailure(SchedulerClient client, ProbeTaskRequest request) {
    assertThat(client.getScheduledExecution(instanceOf(request)))
        .as("The probe waits unpicked, without a failure or a later execution time")
        .hasValueSatisfying(
            pending -> {
              assertThat(pending.isPicked()).isFalse();
              assertThat(pending.getConsecutiveFailures()).isZero();
              assertThat(pending.getExecutionTime()).isBeforeOrEqualTo(clock.instant());
            });
    assertThat(completions).as("No probe execution ran").isEmpty();
  }

  @Test
  @DisplayName(
      "Should hold no database connection for a probe in flight when more probes run at once than"
          + " the connection pool holds")
  void shouldHoldNoDatabaseConnectionForAProbeInFlightWhenMoreProbesRunAtOnceThanThePoolHolds()
      throws Exception {
    var pool = dataSource.unwrap(HikariDataSource.class);
    var probeCapacity = pool.getMaximumPoolSize() + 5;
    assertThat(probeCapacity).isLessThan(THREADS_BEYOND_PROBE_CAPACITY);
    for (var index = 0; index < probeCapacity; index++) {
      scheduling.request(request(createMediaFile("held-" + index + ".mkv")));
    }

    try (var server = startedServer(new SimpleMeterRegistry());
        var worker = probeWorker(server).backgroundProbeCapacity(probeCapacity).build()) {
      startSchedulerPickingFrom(server);
      var held = startedProbes(worker, probeCapacity);

      assertThat(server.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT).runningProbes())
          .as("Every probe reached the worker while the others waited for their results")
          .isEqualTo(probeCapacity);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections())
          .as("No probe waiting at the worker holds a pooled connection")
          .isLessThan(pool.getMaximumPoolSize());
      assertThat(dsl.selectOne().fetchSingle().value1())
          .as("The pool still serves an unrelated query")
          .isEqualTo(1);

      held.forEach(probe -> worker.reply(invalidMedia(probe)));
      for (var index = 0; index < probeCapacity; index++) {
        assertThat(nextCompletion().getResult()).isEqualTo(ExecutionComplete.Result.OK);
      }
    }
  }

  private void whenOneUnitOfProbeCapacityServesTwoProbes(BusyWorkerScenario scenario)
      throws Exception {
    var requests =
        List.of(request(createMediaFile("first.mkv")), request(createMediaFile("second.mkv")));
    requests.forEach(scheduling::request);

    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(ProbeVersion.CURRENT)
              .availableSlots(1)
              .backgroundProbeCapacity(1)
              .build()) {
        var client =
            startScheduler(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot));
        var held = worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest();

        scenario.run(
            BusyWorker.builder()
                .requests(requests)
                .client(client)
                .worker(worker)
                .held(held)
                .deferred(nextCompletion())
                .build());
      }
    }
  }

  private static WorkerSessionServer startedServer(SimpleMeterRegistry meterRegistry)
      throws IOException {
    var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(meterRegistry)
            .build();
    server.start();
    return server;
  }

  private static LoopbackProbeWorker.LoopbackProbeWorkerBuilder probeWorker(
      WorkerSessionServer server) {
    return LoopbackProbeWorker.builder()
        .port(server.port())
        .probeVersion(ProbeVersion.CURRENT)
        .availableSlots(1);
  }

  private static List<ProbeRequest> startedProbes(LoopbackProbeWorker worker, int count)
      throws InterruptedException {
    var started = new ArrayList<ProbeRequest>();
    for (var index = 0; index < count; index++) {
      started.add(worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest());
    }

    return started;
  }

  private static double deferred(SimpleMeterRegistry meterRegistry, ProbeRefusal reason) {
    var counter =
        meterRegistry
            .find("streamarr.probes.deferred")
            .tag("reason", reason.name().toLowerCase(Locale.ROOT))
            .counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  // The scheduler picks no more probes than the free probe capacity of the server's workers.
  private SchedulerClient startSchedulerPickingFrom(WorkerSessionServer server) {
    return startSchedulerPickingFrom(server, new Semaphore(0));
  }

  // Each poll's read of the probe capacity releases one permit of polls.
  private SchedulerClient startSchedulerPickingFrom(WorkerSessionServer server, Semaphore polls) {
    ProbeCapacitySource capacity =
        () -> {
          var workers = server.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT);
          polls.release();
          return workers;
        };
    return startScheduler(
        new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot),
        customizer(capacity, THREADS_BEYOND_PROBE_CAPACITY),
        THREADS_BEYOND_PROBE_CAPACITY);
  }

  // The scheduler picks probes against a fixed probe capacity of two, as if the worker's capacity
  // were free, so the worker itself refuses a probe that finds its capacity in use.
  private SchedulerClient startScheduler(FfprobeService producer) {
    return startScheduler(
        producer,
        customizer(() -> ProbeCapacity.builder().freeBackgroundProbeUnits(2).build(), 2),
        2);
  }

  private DbSchedulerCustomizer customizer(ProbeCapacitySource capacity, int threads) {
    return new ProbeSchedulingConfiguration()
        .dbSchedulerCustomizer(
            schedulerCustomizer.serializer().orElseThrow(),
            dataSource,
            ProbeCapacityJdbcCustomization.builder()
                .capacitySource(capacity)
                .playbackProbeClaims(playbackProbeClaims)
                .probeSchedulerUpperLimit(threads)
                .scanSchedulerUpperLimit(KeyframePacketScanScheduler.THREADS)
                .build());
  }

  // The scheduler and the completion read time from one clock, so a deferred probe runs again
  // only when a test advances it.
  private SchedulerClient startScheduler(
      FfprobeService producer, DbSchedulerCustomizer customizer, int threads) {
    clock =
        new MutableClock(
            new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)));
    var execution =
        ProbeExecution.builder()
            .mediaFiles(mediaFiles)
            .reader(reader)
            .producer(producer)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .packetScans(new CapturingKeyframePacketScanRequests())
            .transactionManager(transactionManager)
            .playbackProbeClaims(playbackProbeClaims)
            .build();
    var completion =
        ProbeTaskCompletion.builder()
            .outcomes(outcomes)
            .transactionManager(transactionManager)
            .clock(clock)
            .properties(probeScheduling)
            .probeTasks(probeTasks)
            .watcherProperties(watcherProperties)
            .build();
    var task = MediaProbeTask.create(execution, completion, clock);
    var properties = schedulerProperties();
    properties.setThreads(threads);
    var listener =
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete executionComplete) {
            completions.add(executionComplete);
          }
        };
    scheduler =
        DbSchedulerConfigurationSupport.buildScheduler(
            properties,
            customizer,
            StatsRegistry.NOOP,
            clock::instant,
            dataSource,
            List.of(task),
            List.of(listener),
            List.of());
    scheduler.start();
    return SchedulerClient.Builder.create(dataSource, task)
        .serializer(schedulerCustomizer.serializer().orElseThrow())
        .build();
  }

  private DbSchedulerProperties schedulerProperties() {
    return Binder.get(environment)
        .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
        .get();
  }

  // db-scheduler's poll waiter measures its interval on the scheduler clock, so a frozen clock
  // polls
  // again only when woken.
  private void advanceClock(Duration duration) {
    clock.advance(duration);
    scheduler.triggerCheckForDueExecutions();
  }

  // db-scheduler notifies listeners after the completion handler's transaction commits.
  private ExecutionComplete nextCompletion() throws InterruptedException {
    var complete = completions.poll(WORKER_WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(complete).as("The scheduler must finish a probe execution").isNotNull();
    return complete;
  }

  private static ProbeAttemptResult invalidMedia(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
        .build();
  }

  private static ProbeAttemptResult success(ProbeRequest request) {
    var media = version4MediaBuilder();
    media.getStreamsBuilder(0).setWidth(1920);
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setMedia(media)
        .build();
  }

  private MediaFile createMediaFile(String filename) throws IOException {
    var source = Files.write(sourceRoot.resolve(filename), new byte[] {1, 2, 3});
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .status(MediaFileStatus.MATCHED)
                .filename(filename)
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    createdFiles.add(file);
    return file;
  }

  private static ProbeTaskRequest request(MediaFile file) throws IOException {
    var path = FilepathCodec.decode(file.getFilepathUri());
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    return ProbeTaskRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static UUID mediaFileIdOf(ExecutionComplete complete) {
    return UUID.fromString(complete.getExecution().taskInstance.getId());
  }

  private static TaskInstanceId instanceOf(ProbeTaskRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
  }

  @FunctionalInterface
  private interface BusyWorkerScenario {
    void run(BusyWorker busy) throws Exception;
  }

  /**
   * Two scheduled probes: the worker holds one in its only unit of probe capacity, and the other
   * deferred.
   */
  @Builder
  private record BusyWorker(
      List<ProbeTaskRequest> requests,
      SchedulerClient client,
      LoopbackProbeWorker worker,
      ProbeRequest held,
      ExecutionComplete deferred) {}
}
