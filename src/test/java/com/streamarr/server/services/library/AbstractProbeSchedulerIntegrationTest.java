package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

abstract class AbstractProbeSchedulerIntegrationTest extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired PersistedProbeReader reader;
  @Autowired ProbeExecution probeExecution;
  @Autowired private ProbeTaskRequests scheduling;
  @Autowired private DataSource dataSource;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;
  @Autowired private ProbeTaskCompletion probeTaskCompletion;
  @Autowired private DbSchedulerCustomizer schedulerCustomizer;
  @Autowired private Environment environment;

  private final List<MediaFile> createdFiles = new ArrayList<>();
  private UUID libraryId;
  private Scheduler scheduler;

  @BeforeEach
  void clearScheduledTasks() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
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
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    mediaFiles.deleteAll(createdFiles);
    if (libraryId != null) {
      libraries.deleteById(libraryId);
    }
  }

  List<ProbeTaskRequest> requestUnchangedFiles(int count) throws IOException {
    libraryId = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary()).getId();
    var requests = new ArrayList<ProbeTaskRequest>();
    for (var index = 0; index < count; index++) {
      var request = request(createMediaFile());
      requests.add(request);
      scheduling.request(request);
    }

    return requests;
  }

  SchedulerClient startScheduler(ProbeExecution execution, AbstractSchedulerListener listener) {
    return startScheduler(execution, listener, Clock.systemUTC());
  }

  SchedulerClient startScheduler(
      ProbeExecution execution, AbstractSchedulerListener listener, Clock clock) {
    var task = MediaProbeTask.create(execution, probeTaskCompletion);
    var properties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .get();
    properties.setThreads(2);
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
    var client =
        SchedulerClient.Builder.create(dataSource, task)
            .serializer(schedulerCustomizer.serializer().orElseThrow())
            .build();
    scheduler.start();
    return client;
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

  static TaskInstanceId instanceOf(ProbeTaskRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
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
}
