package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeRequests;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Probe publication and changed-source requests")
class SchedulerProbePublicationRaceIT extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private ProbeRequests scheduling;
  @Autowired private DataSource dataSource;
  @Autowired private Serializer probeTaskSerializer;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProbeTaskCompletion probeTaskCompletion;
  @Autowired private DbSchedulerCustomizer schedulerCustomizer;
  @Autowired private PlatformTransactionManager transactionManager;

  private final FakeFfprobeService producer = new FakeFfprobeService();
  private final CountDownLatch executionFinished = new CountDownLatch(1);
  private UUID libraryId;
  private UUID mediaFileId;
  private Task<ProbeRequest> task;
  private ProbeExecution probeExecution;
  private SchedulerClient client;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    probeExecution =
        ProbeExecution.builder()
            .mediaFiles(mediaFiles)
            .reader(reader)
            .producer(producer)
            .stabilityChecker(_ -> true)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .build();
    task = MediaProbeTask.create(probeExecution, probeTaskCompletion);
    client =
        SchedulerClient.Builder.create(dataSource, task).serializer(probeTaskSerializer).build();
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    if (mediaFileId != null) {
      mediaFiles.deleteById(mediaFileId);
    }

    if (libraryId != null) {
      libraries.deleteById(libraryId);
    }
  }

  @Test
  @DisplayName("Should retain changed-source work when the running probe is waiting to publish")
  void shouldRetainChangedSourceWorkWhenTheRunningProbeIsWaitingToPublish() throws Exception {
    var file = createMediaFile();
    var original = request(file);
    scheduling.request(original);
    var instance = TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString());
    ProbeRequest changed;

    try (var requester = Executors.newVirtualThreadPerTaskExecutor();
        var publicationGate = dataSource.getConnection()) {
      publicationGate.setAutoCommit(false);
      var blockerPid = lockMediaFile(publicationGate, file.getId());
      try {
        startScheduler();
        await().atMost(Duration.ofSeconds(15)).until(() -> waiterCount(blockerPid) == 1);
        assertThat(client.getScheduledExecution(instance))
            .hasValueSatisfying(execution -> assertThat(execution.isPicked()).isTrue());

        Files.write(FilepathCodec.decode(file.getFilepathUri()), new byte[] {4, 5, 6, 7, 8});
        changed = request(file);
        assertThat(changed.snapshot()).isNotEqualTo(original.snapshot());
        var changedRequest = requester.submit(() -> scheduling.request(changed));

        // The database wait is unhookable without replacing the real publication repository.
        // Also permit a request that joins the lock, so serializing both operations is a valid fix.
        await()
            .atMost(Duration.ofSeconds(15))
            .until(() -> changedRequest.isDone() || waiterCount(blockerPid) >= 2);
        publicationGate.commit();
        changedRequest.get(15, TimeUnit.SECONDS);
      } finally {
        publicationGate.rollback();
      }
    }

    assertThat(executionFinished.await(15, TimeUnit.SECONDS))
        .as("The original execution must finish before observing its durable outcome")
        .isTrue();
    var remainingWork = client.getScheduledExecution(instance);
    var stored = reader.find(file.getId());

    assertThat(
            stored
                    .filter(outcome -> outcome.matches(changed.snapshot(), ProbeVersion.CURRENT))
                    .isPresent()
                || remainingWork
                    .filter(execution -> changed.equals(execution.getData()))
                    .isPresent())
        .as(
            "Changed source must have a current outcome or durable work. Stored snapshot: %s, requested snapshot: %s, remaining work: %s",
            stored.map(outcome -> outcome.snapshot()), changed.snapshot(), remainingWork)
        .isTrue();
  }

  @Test
  @DisplayName("Should retain changed-source work when publication finished before task completion")
  void shouldRetainChangedSourceWorkWhenPublicationFinishedBeforeTaskCompletion() throws Exception {
    var publicationFinished = new CountDownLatch(1);
    var allowCompletion = new CountDownLatch(1);
    var originalTask = task;
    task =
        Tasks.custom(MediaProbeTask.NAME, ProbeRequest.class)
            .execute(
                (instance, context) -> {
                  var completion = originalTask.execute(instance, context);
                  publicationFinished.countDown();
                  try {
                    assertThat(allowCompletion.await(15, TimeUnit.SECONDS)).isTrue();
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "Interrupted before task completion was released", exception);
                  }

                  return completion;
                });
    var file = createMediaFile();
    var original = request(file);
    scheduling.request(original);
    var instance = TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString());
    startScheduler();

    ProbeRequest changed;
    try {
      assertThat(publicationFinished.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(reader.find(file.getId()))
          .hasValueSatisfying(
              stored -> assertThat(stored.snapshot()).isEqualTo(original.snapshot()));
      assertThat(client.getScheduledExecution(instance))
          .hasValueSatisfying(execution -> assertThat(execution.isPicked()).isTrue());

      Files.write(FilepathCodec.decode(file.getFilepathUri()), new byte[] {4, 5, 6, 7, 8});
      changed = request(file);
      scheduling.request(changed);
    } finally {
      allowCompletion.countDown();
    }

    assertThat(executionFinished.await(15, TimeUnit.SECONDS)).isTrue();
    var remainingWork = client.getScheduledExecution(instance);
    var stored = reader.find(file.getId());
    assertThat(
            stored
                    .filter(outcome -> outcome.matches(changed.snapshot(), ProbeVersion.CURRENT))
                    .isPresent()
                || remainingWork
                    .filter(execution -> changed.equals(execution.getData()))
                    .isPresent())
        .as(
            "Changed-source work must use %s after original completion, but its task carried %s",
            changed, remainingWork.map(ScheduledExecution::getData))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(NativeCompletion.class)
  @DisplayName("Should retain task and desired inputs when native completion rolls back")
  void shouldRetainTaskAndDesiredInputsWhenNativeCompletionRollsBack(NativeCompletion operation)
      throws Exception {
    var file = createMediaFile();
    var original = request(file);
    scheduling.request(original);
    if (operation == NativeCompletion.RESCHEDULE) {
      Files.write(FilepathCodec.decode(file.getFilepathUri()), new byte[] {4, 5, 6, 7, 8});
    }

    var originalTask =
        MediaProbeTask.create(
            probeExecution,
            new ProbeTaskCompletion(
                outcomes, transactionManager, Clock.offset(Clock.systemUTC(), Duration.ofDays(1))));
    task =
        Tasks.custom(MediaProbeTask.NAME, ProbeRequest.class)
            .execute((instance, context) -> rollbackAfter(originalTask.execute(instance, context)));

    startScheduler();

    assertThat(executionFinished.await(15, TimeUnit.SECONDS)).isTrue();
    var instance = TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString());
    assertThat(client.getScheduledExecution(instance))
        .hasValueSatisfying(
            scheduled -> {
              assertThat(scheduled.isPicked()).isTrue();
              assertThat(scheduled.getData()).isEqualTo(original);
            });
    var desired =
        new TransactionTemplate(transactionManager)
            .execute(_ -> outcomes.lockProbeInputs(file.getId()));
    assertThat(desired).contains(new ProbeInputs(original.snapshot(), original.probeVersion()));
  }

  @Test
  @DisplayName("Should remove the task when its media file was deleted before execution")
  void shouldRemoveTheTaskWhenItsMediaFileWasDeletedBeforeExecution() throws Exception {
    var file = createMediaFile();
    scheduling.request(request(file));
    mediaFiles.deleteById(file.getId());

    startScheduler();

    assertThat(executionFinished.await(15, TimeUnit.SECONDS)).isTrue();
    assertThat(
            client.getScheduledExecution(
                TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString())))
        .isEmpty();
    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should leave no task when the media file was deleted before requesting a probe")
  void shouldLeaveNoTaskWhenTheMediaFileWasDeletedBeforeRequestingAProbe() throws IOException {
    var file = createMediaFile();
    var request = request(file);
    mediaFiles.deleteById(file.getId());

    scheduling.request(request);

    assertThat(
            client.getScheduledExecution(
                TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString())))
        .isEmpty();
  }

  private CompletionHandler<ProbeRequest> rollbackAfter(CompletionHandler<ProbeRequest> handler) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                status -> {
                  handler.complete(complete, operations);
                  status.setRollbackOnly();
                });
  }

  private enum NativeCompletion {
    REMOVE,
    RESCHEDULE
  }

  private int lockMediaFile(Connection connection, UUID mediaFileId) throws SQLException {
    try (var lock =
        connection.prepareStatement("SELECT id FROM media_file WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, mediaFileId);
      try (var locked = lock.executeQuery()) {
        assertThat(locked.next()).isTrue();
      }
    }

    try (var query = connection.createStatement();
        var result = query.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private int waiterCount(int blockerPid) {
    return jdbc.queryForObject(
        """
        WITH RECURSIVE waiters(pid) AS (
          SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
          UNION
          SELECT activity.pid FROM pg_stat_activity activity
          JOIN waiters ON waiters.pid = ANY(pg_blocking_pids(activity.pid))
        )
        SELECT count(*) FROM waiters
        """,
        Integer.class,
        blockerPid);
  }

  private void startScheduler() {
    scheduler =
        Scheduler.create(schedulerCustomizer.dataSource().orElse(dataSource), task)
            .threads(1)
            .pollingInterval(Duration.ofMillis(200))
            .executorService(Executors.newVirtualThreadPerTaskExecutor())
            .serializer(probeTaskSerializer)
            .addSchedulerListener(
                new AbstractSchedulerListener() {
                  @Override
                  public void onExecutionComplete(ExecutionComplete executionComplete) {
                    executionFinished.countDown();
                  }
                })
            .schedulerName(new SchedulerName.Fixed("probe-publication-race-it"))
            .build();
    scheduler.start();
  }

  private MediaFile createMediaFile() throws IOException {
    var source = Files.createTempFile(tempDir, "probe", ".mkv");
    Files.write(source, new byte[] {1, 2, 3});
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .status(MediaFileStatus.MATCHED)
                .filename(source.getFileName().toString())
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    mediaFileId = file.getId();
    return file;
  }

  private static ProbeRequest request(MediaFile file) throws IOException {
    var attributes =
        Files.readAttributes(
            FilepathCodec.decode(file.getFilepathUri()), BasicFileAttributes.class);
    return ProbeRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }
}
