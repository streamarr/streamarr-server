package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.PersistedProbeFixture;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.ProbeRequests;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("HLS Streaming Service Integration Tests")
class HlsStreamingServiceIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private ProbeRequests probeRequests;
  @Autowired private HlsPlaylistService playlistService;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbc;

  @Qualifier("probeSchedulerClient")
  @Autowired
  private SchedulerClient client;

  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @TempDir Path directory;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;
  @TestBean PlaybackAuthorityGate authorityGate;

  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  static FfprobeService ffprobeService() {
    return _ -> {
      throw new AssertionError("Playback must not run ffprobe");
    };
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  static PlaybackAuthorityGate authorityGate() {
    return (_, _) -> true;
  }

  private MediaFile savedMediaFile;
  private SourceFileSnapshot sourceSnapshot;

  @BeforeEach
  void setUp() throws IOException {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var path = Files.writeString(directory.resolve("test.mkv"), "stable media file");
    sourceSnapshot =
        new SourceFileSnapshot(Files.size(path), Files.getLastModifiedTime(path).toInstant());

    var file =
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(path))
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(sourceSnapshot.size())
            .libraryId(library.getId())
            .build();
    savedMediaFile = mediaFileRepository.saveAndFlush(file);
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return accepted(
        streamingService.createSession(
            createStreamSessionCommand(mediaFileId, profileId, options)));
  }

  @Test
  @DisplayName("Should remove session when session is destroyed")
  void shouldRemoveSessionWhenSessionIsDestroyed() {
    storeProbe(defaultProbeBuilder().build());
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    streamingService.destroySession(session.getSessionId());

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should durably enqueue one task when repeated playback requests have no probe outcome")
  void shouldDurablyEnqueueOneTaskWhenRepeatedPlaybackRequestsHaveNoProbeOutcome() {
    var command =
        createStreamSessionCommand(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(streamingService.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    var first = scheduledRequest().orElseThrow();

    assertThat(streamingService.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    assertThat(scheduledRequest()).contains(first);
    assertThat(scheduledCount()).isEqualTo(1);
    assertThat(streamingService.getActiveSessionCount()).isZero();
  }

  @Test
  @DisplayName("Should preserve the terminal probe error when playback is requested again")
  void shouldPreserveTheTerminalProbeErrorWhenPlaybackIsRequestedAgain() {
    var row =
        MediaFileContainerInfo.builder()
            .mediaFileId(savedMediaFile.getId())
            .snapshot(sourceSnapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .probeError(ProbeError.INVALID_MEDIA)
            .build();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ -> PersistedProbeFixture.storeProbe(entityManager, row));
    var command =
        createStreamSessionCommand(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThat(streamingService.createSession(command))
          .isEqualTo(
              Outcome.rejected(
                  new CreateStreamSessionRejection.ProbeFailed(ProbeError.INVALID_MEDIA)));
    }

    assertThat(scheduledRequest()).isEmpty();
    assertThat(streamingService.getActiveSessionCount()).isZero();
  }

  @Test
  @DisplayName("Should keep serving persisted values when a compatible refresh is pending")
  void shouldKeepServingPersistedValuesWhenACompatibleRefreshIsPending() {
    var storedProbe =
        defaultProbeBuilder()
            .duration(Duration.ofSeconds(73))
            .width(1280)
            .height(720)
            .bitrate(2_000_000)
            .build();
    storeProbe(storedProbe);
    var refresh =
        ProbeRequest.builder()
            .mediaFileId(savedMediaFile.getId())
            .libraryId(savedMediaFile.getLibraryId())
            .filepathUri(savedMediaFile.getFilepathUri())
            .snapshot(sourceSnapshot)
            .probeVersion(ProbeVersion.CURRENT + 1)
            .build();
    probeRequests.request(refresh);

    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getMediaProbe())
        .isEqualTo(ProbeFixture.completeProbe(storedProbe).mediaProbe());
    assertThat(playlistService.generateMultivariantPlaylist(session, "token"))
        .contains("BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720");
    assertThat(playlistService.generateMediaPlaylist(session, "token"))
        .contains("#EXTINF:1.000000,", "segment12.ts?t=token", "#EXT-X-ENDLIST")
        .doesNotContain("segment13.ts");
    assertThat(scheduledRequest()).contains(refresh);
  }

  private void storeProbe(MediaProbe probe) {
    var row =
        PersistedProbeFixture.storedProbeBuilder(
                savedMediaFile.getId(), ProbeFixture.completeProbe(probe))
            .snapshot(sourceSnapshot)
            .build();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ -> PersistedProbeFixture.storeProbe(entityManager, row));
  }

  private Optional<ProbeRequest> scheduledRequest() {
    return client
        .getScheduledExecution(
            TaskInstanceId.of(MediaProbeTask.NAME, savedMediaFile.getId().toString()))
        .map(execution -> (ProbeRequest) execution.getData());
  }

  private int scheduledCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM scheduled_tasks WHERE task_instance = ?",
        Integer.class,
        savedMediaFile.getId().toString());
  }

  @AfterEach
  void cleanUpPlayback() {
    streamingService.getAllSessions().stream()
        .filter(session -> session.getMediaFileId().equals(savedMediaFile.getId()))
        .forEach(session -> streamingService.destroySession(session.getSessionId()));
    jdbc.update(
        "DELETE FROM scheduled_tasks WHERE task_instance = ?", savedMediaFile.getId().toString());
  }

  @Test
  @DisplayName("Should reject creation when media file is not found")
  void shouldRejectCreationWhenMediaFileIsNotFound() {
    var nonExistentId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var options = defaultOptions();

    assertThat(
            streamingService.createSession(
                createStreamSessionCommand(nonExistentId, profileId, options)))
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.MediaFileNotFound(nonExistentId)));
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }
}
