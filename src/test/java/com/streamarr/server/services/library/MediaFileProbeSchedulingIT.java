package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeRequests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
@Tag("IntegrationTest")
@DisplayName("Durable media file probe scheduling")
class MediaFileProbeSchedulingIT extends AbstractIntegrationTest {

  @TempDir Path directory;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationEvents events;
  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeRequests probeRequests;

  @Qualifier("probeSchedulerClient")
  @Autowired
  private SchedulerClient client;

  private Library library;
  private MediaFile mediaFile;
  private Path path;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update("DELETE FROM scheduled_tasks");
    path = Files.writeString(directory.resolve("Movie (2024).mkv"), "media");
    library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name("Probe scheduling")
                .filepathUri(FilepathCodec.encode(directory))
                .status(LibraryStatus.HEALTHY)
                .build());
    mediaFile =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(FilepathCodec.encode(path))
                .filename(path.getFileName().toString())
                .size(Files.size(path))
                .status(MediaFileStatus.MATCHED)
                .build());
  }

  @Test
  @DisplayName("Should fail the scan when the probe request cannot be recorded")
  void shouldFailTheScanWhenTheProbeRequestCannotBeRecorded() throws Exception {
    try (var _ = rejectProbeRequests()) {
      libraryManagementService.scanLibrary(library.getId());

      assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.UNHEALTHY);
      assertThat(scheduledCount()).isZero();
      assertThat(events.stream(ScanCompletedEvent.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should complete the scan when probe requests recover after a failure")
  void shouldCompleteTheScanWhenProbeRequestsRecoverAfterAFailure() throws Exception {
    try (var _ = rejectProbeRequests()) {
      libraryManagementService.scanLibrary(library.getId());
    }

    libraryManagementService.scanLibrary(library.getId());

    assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(events.stream(ScanCompletedEvent.class)).hasSize(1);
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId()));
  }

  private AutoCloseable rejectProbeRequests() {
    jdbc.execute(
        """
        CREATE FUNCTION adversarial_reject_probe_request() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'adversarial probe queue unavailable';
        END;
        $$
        """);
    try {
      jdbc.execute(
          """
          CREATE TRIGGER adversarial_reject_probe_request BEFORE INSERT ON scheduled_tasks
          FOR EACH ROW EXECUTE FUNCTION adversarial_reject_probe_request()
          """);
    } catch (RuntimeException exception) {
      jdbc.execute("DROP FUNCTION adversarial_reject_probe_request()");
      throw exception;
    }

    return () -> {
      jdbc.execute("DROP TRIGGER IF EXISTS adversarial_reject_probe_request ON scheduled_tasks");
      jdbc.execute("DROP FUNCTION adversarial_reject_probe_request()");
    };
  }

  @Test
  @DisplayName("Should record one request when the same probe is requested concurrently")
  void shouldRecordOneRequestWhenTheSameProbeIsRequestedConcurrently() throws Exception {
    var request = requestFor(mediaFile);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                awaitStart(start);
                probeRequests.request(request);
              });
      var second =
          executor.submit(
              () -> {
                awaitStart(start);
                probeRequests.request(request);
              });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(scheduledCount()).isEqualTo(1);
    assertThat(scheduledRequest()).contains(request);
  }

  @Test
  @DisplayName("Should record the probe request before a matched-file scan completes")
  void shouldRecordTheProbeRequestBeforeAMatchedFileScanCompletes() {
    libraryManagementService.scanLibrary(library.getId());

    assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(reader.find(mediaFile.getId())).isEmpty();
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId()));
  }

  @Test
  @DisplayName("Should record one request when scan and watcher processing overlap")
  void shouldRecordOneRequestWhenScanAndWatcherProcessingOverlap() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var scan =
          executor.submit(
              () -> {
                awaitStart(start);
                libraryManagementService.scanLibrary(library.getId());
              });
      var discovered =
          executor.submit(
              () -> {
                awaitStart(start);
                libraryManagementService.processDiscoveredFile(library.getId(), path);
              });
      start.countDown();
      scan.get(10, TimeUnit.SECONDS);
      discovered.get(10, TimeUnit.SECONDS);
    }

    assertThat(scheduledCount()).isEqualTo(1);
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId()));
  }

  @Test
  @DisplayName("Should invalidate the recorded outcome when a matched source changes")
  void shouldInvalidateTheRecordedOutcomeWhenAMatchedSourceChanges() throws Exception {
    var stale = requestFor(mediaFile);
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(mediaFile.getId())
                    .snapshot(stale.snapshot())
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA))
                    .build()))
        .isTrue();
    Files.writeString(path, "replacement media");

    var replacementSize = Files.size(path);

    libraryManagementService.processDiscoveredFile(library.getId(), path);

    assertThat(reader.find(mediaFile.getId())).isEmpty();
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.snapshot().size()).isEqualTo(replacementSize));
    assertThat(mediaFiles.findById(mediaFile.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaFileStatus.MATCHED);
  }

  private Optional<ProbeRequest> scheduledRequest() {
    return client
        .getScheduledExecution(TaskInstanceId.of(MediaProbeTask.NAME, mediaFile.getId().toString()))
        .map(execution -> (ProbeRequest) execution.getData());
  }

  private int scheduledCount() {
    return jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks", Integer.class);
  }

  private static ProbeRequest requestFor(MediaFile file) throws Exception {
    var source = FilepathCodec.decode(file.getFilepathUri());
    var attributes = Files.readAttributes(source, BasicFileAttributes.class);
    return ProbeRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static void awaitStart(CountDownLatch start) {
    try {
      assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
