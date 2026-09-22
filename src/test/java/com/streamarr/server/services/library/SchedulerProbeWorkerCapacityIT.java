package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
  @Autowired private ProbeTaskCompletion probeTaskCompletion;
  @Autowired private DbSchedulerCustomizer schedulerCustomizer;
  @Autowired private Environment environment;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private final BlockingQueue<ExecutionComplete> completions = new LinkedBlockingQueue<>();
  private UUID libraryId;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    mediaFiles.deleteAll(createdFiles);
    if (libraryId != null) {
      libraries.deleteById(libraryId);
    }
  }

  @Test
  @DisplayName(
      "Should keep the second probe pending and record both when one worker slot serves two probes")
  void shouldKeepSecondProbePendingAndRecordBothWhenOneWorkerSlotServesTwoProbes()
      throws Exception {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
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
        var deferred = completions.poll(WORKER_WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(deferred).as("The busy probe must end its scheduler execution").isNotNull();
        assertThat(deferred.getResult())
            .as("A busy worker is not a failed probe")
            .isEqualTo(ExecutionComplete.Result.OK);
        assertThat(client.getScheduledExecution(deferred.getExecution().taskInstance))
            .as("The busy probe must remain pending in db-scheduler")
            .hasValueSatisfying(
                pending -> {
                  assertThat(pending.isPicked()).isFalse();
                  assertThat(pending.getConsecutiveFailures()).isZero();
                });

        worker.reply(success(held));
        worker.reply(success(worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest()));

        await()
            .atMost(WORKER_WAIT)
            .untilAsserted(
                () -> {
                  for (var request : requests) {
                    assertThat(reader.find(request.mediaFileId())).isPresent();
                    assertThat(client.getScheduledExecution(instanceOf(request))).isEmpty();
                  }
                });
        assertThat(completions)
            .extracting(ExecutionComplete::getResult)
            .doesNotContain(ExecutionComplete.Result.FAILED);
      }
    }
  }

  private SchedulerClient startScheduler(RemoteFfprobeService producer) {
    var execution =
        ProbeExecution.builder()
            .mediaFiles(mediaFiles)
            .reader(reader)
            .producer(producer)
            .stabilityChecker(_ -> true)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .build();
    var task = MediaProbeTask.create(execution, probeTaskCompletion);
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
            Instant::now,
            dataSource,
            List.of(task),
            List.of(listener),
            List.of());
    scheduler.start();
    return SchedulerClient.Builder.create(dataSource, task)
        .serializer(schedulerCustomizer.serializer().orElseThrow())
        .build();
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

  private static TaskInstanceId instanceOf(ProbeTaskRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
  }
}
