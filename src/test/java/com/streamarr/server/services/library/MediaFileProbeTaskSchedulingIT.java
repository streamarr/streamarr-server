package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.ProbeTaskRequestFixture.requestFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.validation.IgnoredFileValidator;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.LibraryOperationDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Durable media file probe task scheduling")
class MediaFileProbeTaskSchedulingIT extends AbstractIntegrationTest {

  private static final Duration SCAN_BOUND = Duration.ofSeconds(20);
  private static final Duration REQUEST_BOUND = Duration.ofSeconds(10);

  @TempDir Path directory;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeTaskRequests probeTaskRequests;
  @Autowired private IgnoredFileValidator ignoredFileValidator;
  @Autowired private SchedulerClient client;

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
    try (var _ = rejectProbeTaskRequests()) {
      scan();

      assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.UNHEALTHY);
      assertThat(scheduledCount()).isZero();
      assertThat(libraryOperationDriver.completedScans(library.getId())).isZero();
    }
  }

  @Test
  @DisplayName("Should complete the scan when probe task requests recover after a failure")
  void shouldCompleteTheScanWhenProbeTaskRequestsRecoverAfterAFailure() throws Exception {
    try (var _ = rejectProbeTaskRequests()) {
      scan();
    }

    try (var dispatcher = BoundedTask.start(this::storeTheRequestedOutcome)) {
      scan();
      dispatcher.await(REQUEST_BOUND);
    }

    assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(libraryOperationDriver.completedScans(library.getId())).isOne();
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId()));
  }

  private AutoCloseable rejectProbeTaskRequests() {
    jdbc.execute("CREATE SEQUENCE adversarial_probe_request_attempt");
    jdbc.execute(
        """
        CREATE FUNCTION adversarial_reject_probe_request() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          PERFORM nextval('adversarial_probe_request_attempt');
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
      jdbc.execute("DROP SEQUENCE adversarial_probe_request_attempt");
      throw exception;
    }

    return () -> {
      jdbc.execute("DROP TRIGGER IF EXISTS adversarial_reject_probe_request ON scheduled_tasks");
      jdbc.execute("DROP FUNCTION adversarial_reject_probe_request()");
      jdbc.execute("DROP SEQUENCE adversarial_probe_request_attempt");
    };
  }

  @Test
  @DisplayName(
      "Should recover watcher scheduling when the database recovers without another file event")
  void shouldRecoverWatcherSchedulingWhenTheDatabaseRecoversWithoutAnotherFileEvent()
      throws Exception {
    var watcher =
        new DirectoryWatchingService(
            libraries, _ -> true, libraryManagementService, ignoredFileValidator);
    try {
      watcher.addDirectory(directory);
      try (var _ = rejectProbeTaskRequests()) {
        Files.writeString(path, "replacement media");
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    Boolean.TRUE.equals(
                        jdbc.queryForObject(
                            "SELECT is_called FROM adversarial_probe_request_attempt",
                            Boolean.class)));
        assertThat(scheduledRequest()).isEmpty();
      }

      var replacementSize = Files.size(path);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(scheduledRequest())
                      .hasValueSatisfying(
                          request -> {
                            assertThat(request.mediaFileId()).isEqualTo(mediaFile.getId());
                            assertThat(request.snapshot().size()).isEqualTo(replacementSize);
                          }));
    } finally {
      watcher.stopWatching();
    }
  }

  @Test
  @DisplayName("Should record one request when the same probe is requested concurrently")
  void shouldRecordOneRequestWhenTheSameProbeIsRequestedConcurrently() throws Exception {
    var request = requestFor(mediaFile);
    var start = new CountDownLatch(1);
    try (var first =
            BoundedTask.start(
                () -> {
                  awaitStart(start);
                  probeTaskRequests.request(request);
                });
        var second =
            BoundedTask.start(
                () -> {
                  awaitStart(start);
                  probeTaskRequests.request(request);
                })) {
      start.countDown();
      first.await(REQUEST_BOUND);
      second.await(REQUEST_BOUND);
    }

    assertThat(scheduledCount()).isEqualTo(1);
    assertThat(scheduledRequest()).contains(request);
  }

  @Test
  @DisplayName("Should record one request when scan and watcher processing overlap")
  void shouldRecordOneRequestWhenScanAndWatcherProcessingOverlap() throws Exception {
    var start = new CountDownLatch(1);
    try (var scan =
            BoundedTask.start(
                () -> {
                  awaitStart(start);
                  libraryOperationDriver.scanToEnd(library.getId());
                });
        var discovered =
            BoundedTask.start(
                () -> {
                  awaitStart(start);
                  libraryManagementService.processDiscoveredFile(library.getId(), path);
                })) {
      start.countDown();
      discovered.await(REQUEST_BOUND);
      storeTheRequestedOutcome();
      scan.await(SCAN_BOUND);
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

  // Stands in for the disabled dispatcher: stores a successful outcome for the requested inputs.
  // It polls for the request because the scan saves it inside the service, which gives a test no
  // hook to wait on.
  private void storeTheRequestedOutcome() {
    await().atMost(Duration.ofSeconds(10)).until(() -> scheduledRequest().isPresent());
    var request = scheduledRequest().orElseThrow();
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(request.mediaFileId())
                    .snapshot(request.snapshot())
                    .probeVersion(request.probeVersion())
                    .outcome(new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM))
                    .build()))
        .isTrue();
  }

  private void scan() throws Exception {
    BoundedTask.runWithin(SCAN_BOUND, () -> libraryOperationDriver.scanToEnd(library.getId()));
  }

  @Test
  @DisplayName("Should request a probe when the stored keyframe positions cannot be decoded")
  void shouldRequestAProbeWhenTheStoredKeyframePositionsCannotBeDecoded() throws Exception {
    var current = requestFor(mediaFile);
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(mediaFile.getId())
                    .snapshot(current.snapshot())
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(knownKeyframesOutcome())
                    .build()))
        .isTrue();
    // A format byte of 2 names no format this server can decode.
    jdbc.update(
        "UPDATE media_file_keyframe_positions SET encoded_times = '\\x02' WHERE media_file_id = ?",
        mediaFile.getId());

    libraryManagementService.processDiscoveredFile(library.getId(), path);

    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.snapshot()).isEqualTo(current.snapshot()));
  }

  private static ProbeOutcome knownKeyframesOutcome() {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().index(0).codecType("video").build()),
        new KeyframeKnowledge.Known(
            KeyframePositions.builder()
                .streamIndex(0)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.CONTAINER_INDEX)
                .keyframes(List.of(new KeyframePosition(0, 0)))
                .build()));
  }

  private Optional<ProbeTaskRequest> scheduledRequest() {
    return client
        .getScheduledExecution(TaskInstanceId.of(MediaProbeTask.NAME, mediaFile.getId().toString()))
        .map(execution -> (ProbeTaskRequest) execution.getData());
  }

  private int scheduledCount() {
    return jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks", Integer.class);
  }

  private static void awaitStart(CountDownLatch start) throws InterruptedException {
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
