package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
      "Should keep the busy probe pending for the retry delay when one worker slot serves two"
          + " probes")
  void shouldKeepBusyProbePendingForRetryDelayWhenOneWorkerSlotServesTwoProbes() throws Exception {
    whenOneWorkerSlotServesTwoProbes(
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
    whenOneWorkerSlotServesTwoProbes(
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
  @DisplayName("Should record both probes when the held probe releases the only worker slot")
  void shouldRecordBothProbesWhenHeldProbeReleasesTheOnlyWorkerSlot() throws Exception {
    whenOneWorkerSlotServesTwoProbes(
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
            throw new ProbeWorkersBusyException();
          }

          throw new ProbeExecutionException("Worker lost the probe");
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

  private void whenOneWorkerSlotServesTwoProbes(BusyWorkerScenario scenario) throws Exception {
    var requests =
        List.of(request(createMediaFile("first.mkv")), request(createMediaFile("second.mkv")));
    requests.forEach(scheduling::request);

    try (var server =
        new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore())) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(ProbeVersion.CURRENT)
              .availableSlots(1)
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

  // The scheduler and the completion read time from one clock, so a deferred probe runs again
  // only when a test advances it.
  private SchedulerClient startScheduler(FfprobeService producer) {
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
    var properties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .get();
    properties.setThreads(2);
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
            schedulerCustomizer,
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

  private static ProbeAttemptResult success(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setMedia(
            ProbeMediaInfo.newBuilder()
                .addStreams(ProbeStreamInfo.newBuilder().setCodecType("video").setWidth(1920)))
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

  /** Two scheduled probes: the worker holds one in its only slot and deferred the other. */
  @Builder
  private record BusyWorker(
      List<ProbeTaskRequest> requests,
      SchedulerClient client,
      LoopbackProbeWorker worker,
      ProbeRequest held,
      ExecutionComplete deferred) {}
}
