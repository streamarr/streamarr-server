package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.ProbeTaskRequestFixture.requestFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

abstract class AbstractProbeSchedulerIntegrationTest extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired PersistedProbeReader reader;
  @Autowired ProbeExecution probeExecution;
  @Autowired private ProbeTaskRequests scheduling;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;
  @Autowired private ApplicationContext applicationContext;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private UUID libraryId;
  private Scheduler scheduler;

  @BeforeEach
  void clearScheduledTasks() {
    deleteScheduledTasks();
  }

  // Statistics gathered at one row make PostgreSQL join the claim's LIMIT subquery as a rescanned
  // inner side, the plan under which a single-statement lock-and-fetch claims every due execution.
  void analyzeScheduledTasksWithOneRow() {
    dsl.insertInto(
            DSL.table("scheduled_tasks"),
            DSL.field("task_name"),
            DSL.field("task_instance"),
            DSL.field("execution_time"),
            DSL.field("picked"),
            DSL.field("version"))
        .values("statistics-only", UUID.randomUUID().toString(), OffsetDateTime.now(), false, 0L)
        .execute();
    dsl.execute("ANALYZE scheduled_tasks");
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
  }

  @AfterEach
  void stopSchedulerAndRemoveFiles() {
    stopScheduler();
    deleteScheduledTasks();
    mediaFiles.deleteAll(createdFiles);
    if (libraryId != null) {
      libraries.deleteById(libraryId);
    }
  }

  private void deleteScheduledTasks() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    dsl.deleteFrom(DSL.table(KeyframePacketScanScheduler.TABLE_NAME)).execute();
  }

  List<ProbeTaskRequest> requestUnchangedFiles(int count) throws IOException {
    var requests = createUnchangedFiles(count);
    requests.forEach(scheduling::request);
    return requests;
  }

  /**
   * Creates media files and returns the probe requests a scan would make, without scheduling them.
   */
  List<ProbeTaskRequest> createUnchangedFiles(int count) throws IOException {
    libraryId = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()).getId();
    var requests = new ArrayList<ProbeTaskRequest>();
    for (var index = 0; index < count; index++) {
      requests.add(requestFor(createMediaFile()));
    }

    return requests;
  }

  SchedulerClient startScheduler(ProbeExecution execution, AbstractSchedulerListener listener) {
    return startScheduler(execution, listener, Clock.systemUTC());
  }

  SchedulerClient startScheduler(
      ProbeExecution execution, AbstractSchedulerListener listener, Clock clock) {
    var started = probeSchedulerStarter().start(execution, listener, clock);
    scheduler = started.scheduler();
    return started.client();
  }

  ProbeSchedulerStarter probeSchedulerStarter() {
    return ProbeSchedulerStarter.fromContext(applicationContext);
  }

  // The scheduler polls at its polling interval; a test that advanced its clock polls at once.
  void checkForDueExecutions() {
    scheduler.triggerCheckForDueExecutions();
  }

  void stopScheduler() {
    if (scheduler == null) {
      return;
    }

    scheduler.stop();
    scheduler = null;
  }

  static AbstractSchedulerListener countingOk(CountDownLatch completions) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (executionComplete.getResult() == ExecutionComplete.Result.OK) {
          completions.countDown();
        }
      }
    };
  }

  static AbstractSchedulerListener countingCompletions(CountDownLatch completions) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        completions.countDown();
      }
    };
  }

  /** Fails every attempt that reaches a worker, holding each until {@code release} completes. */
  static FfprobeService workerFailingAfter(
      CountDownLatch probing, CompletableFuture<Void> release) {
    return _ -> {
      probing.countDown();
      release.join();
      throw new ProbeExecutionException(
          ItemFailureReason.SOURCE_INACCESSIBLE, "Worker could not read the source");
    };
  }

  // A link to itself fails every stat with an I/O error other than NoSuchFileException.
  static void makeUnreadable(Path source) throws IOException {
    Files.delete(source);
    Files.createSymbolicLink(source, source.getFileName());
  }

  void assertPublished(List<ProbeTaskRequest> requests) {
    for (var request : requests) {
      assertThat(reader.find(request.mediaFileId()))
          .hasValueSatisfying(
              stored -> {
                assertThat(stored.snapshot()).isEqualTo(request.snapshot());
                assertThat(stored.probeVersion()).isEqualTo(request.probeVersion());
              });
    }
  }

  Instant delayExecution(UUID mediaFileId, int consecutiveFailures) {
    return ScheduledProbeTasks.delay(dsl, mediaFileId, consecutiveFailures);
  }

  static TaskInstanceId instanceOf(ProbeTaskRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
  }

  static TaskInstanceId instanceOf(MediaFile file) {
    return TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString());
  }

  private MediaFile createMediaFile() throws IOException {
    var source = Files.createTempFile(tempDir, "probe", ".mkv");
    Files.write(source, new byte[] {1, 2, 3});
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .status(MediaFileStatus.MATCHED)
                .filename(source.getFileName().toString())
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    createdFiles.add(file);
    return file;
  }
}
