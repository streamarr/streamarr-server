package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
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

@Tag("IntegrationTest")
@DisplayName("Media probe scheduling")
class SchedulerProbeRequestsIT extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private ProbeRequests scheduling;
  @Autowired private DataSource dataSource;
  @Autowired private Serializer probeTaskSerializer;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private DSLContext dsl;

  private final FakeFfprobeService producer = new FakeFfprobeService();
  private final List<MediaFile> createdFiles = new ArrayList<>();
  private Task<ProbeRequest> task;
  private SchedulerClient client;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() {
    var execution =
        ProbeExecution.builder()
            .mediaFiles(mediaFileRepository)
            .reader(reader)
            .producer(producer)
            .stabilityChecker(_ -> true)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .build();
    task = MediaProbeTask.create(execution);
    client =
        SchedulerClient.Builder.create(dataSource, task).serializer(probeTaskSerializer).build();
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    mediaFileRepository.deleteAll(createdFiles);
    createdFiles.clear();
  }

  @Test
  @DisplayName("Should record one instance when the same probe is requested twice")
  void shouldRecordOneInstanceWhenTheSameProbeIsRequestedTwice() throws IOException {
    var request = request(createMediaFile());

    scheduling.request(request);
    scheduling.request(request);

    assertThat(client.getScheduledExecution(instanceOf(request)))
        .hasValueSatisfying(
            execution -> {
              assertThat(execution.getData()).isEqualTo(request);
              assertThat(execution.isPicked()).isFalse();
            });
    assertThat(dsl.fetchCount(DSL.table("scheduled_tasks"))).isEqualTo(1);
  }

  @Test
  @DisplayName("Should replace the pending inputs when a request carries a new snapshot")
  void shouldReplaceThePendingInputsWhenARequestCarriesANewSnapshot() throws IOException {
    var file = createMediaFile();
    var first = request(file);
    scheduling.request(first);
    var second =
        first.toBuilder()
            .snapshot(new SourceFileSnapshot(first.snapshot().size() + 1, Instant.EPOCH))
            .build();

    scheduling.request(second);

    assertThat(client.getScheduledExecution(instanceOf(second)))
        .hasValueSatisfying(execution -> assertThat(execution.getData()).isEqualTo(second));
  }

  @Test
  @DisplayName("Should delete a stored outcome when a request carries a different snapshot")
  void shouldDeleteAStoredOutcomeWhenARequestCarriesADifferentSnapshot() throws IOException {
    var file = createMediaFile();
    var stale = request(file);
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(file.getId())
            .snapshot(stale.snapshot())
            .probeVersion(ProbeVersion.CURRENT)
            .outcome(producer.probe(FilepathCodec.decode(file.getFilepathUri())))
            .build());

    scheduling.request(
        stale.toBuilder()
            .snapshot(new SourceFileSnapshot(stale.snapshot().size() + 1, Instant.EPOCH))
            .build());

    assertThat(reader.find(file.getId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should publish the outcome and remove the instance when the scheduler executes a request")
  void shouldPublishTheOutcomeAndRemoveTheInstanceWhenTheSchedulerExecutesARequest()
      throws IOException {
    var file = createMediaFile();
    var request = request(file);
    scheduling.request(request);

    startScheduler();

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              assertThat(reader.find(file.getId()))
                  .hasValueSatisfying(
                      stored -> assertThat(stored.snapshot()).isEqualTo(request.snapshot()));
              assertThat(client.getScheduledExecution(instanceOf(request))).isEmpty();
            });
    assertThat(producer.wasLastProbeOnVirtualThread()).isTrue();
  }

  @Test
  @DisplayName("Should reschedule with backoff when the producer fails transiently")
  void shouldRescheduleWithBackoffWhenTheProducerFailsTransiently() throws IOException {
    var file = createMediaFile();
    var request = request(file);
    producer.failWith(new ProbeExecutionException("no worker connected"));
    var requestedAt = Instant.now();
    scheduling.request(request);

    startScheduler();

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(client.getScheduledExecution(instanceOf(request)))
                    .hasValueSatisfying(
                        execution -> {
                          assertThat(execution.getConsecutiveFailures()).isEqualTo(1);
                          assertThat(execution.isPicked()).isFalse();
                          assertThat(execution.getExecutionTime())
                              .isAfterOrEqualTo(requestedAt.plusSeconds(4));
                        }));
    assertThat(reader.find(file.getId())).isEmpty();
  }

  private void startScheduler() {
    scheduler =
        Scheduler.create(dataSource, task)
            .threads(1)
            .pollingInterval(Duration.ofMillis(200))
            .executorService(Executors.newVirtualThreadPerTaskExecutor())
            .serializer(probeTaskSerializer)
            .schedulerName(new SchedulerName.Fixed("media-probe-it"))
            .build();
    scheduler.start();
  }

  private MediaFile createMediaFile() throws IOException {
    var source = Files.createTempFile(tempDir, "probe", ".mkv");
    Files.write(source, new byte[] {1, 2, 3});
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var file =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename(source.getFileName().toString())
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    createdFiles.add(file);
    return file;
  }

  private static ProbeRequest request(MediaFile file) throws IOException {
    var path = FilepathCodec.decode(file.getFilepathUri());
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    return ProbeRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static TaskInstanceId instanceOf(ProbeRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
  }
}
