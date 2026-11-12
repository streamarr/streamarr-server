package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.PersistedProbeFixture;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.MediaProbeTask;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("HLS Streaming Service Integration Tests")
class HlsStreamingServiceIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private ProbeTaskRequests probeTaskRequests;
  @Autowired private MediaFileContainerInfoRepository probeResults;
  @Autowired private HlsPlaylistService playlistService;
  @Autowired private ProducerLifecycleService producerLifecycle;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SchedulerClient client;
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
  @DisplayName(
      "Should refuse playback without starting a job attempt when the persisted probe has no"
          + " duration")
  void shouldRefusePlaybackWithoutStartingJobAttemptWhenThePersistedProbeHasNoDuration() {
    var complete = ProbeFixture.completeProbe(defaultProbeBuilder().build());
    storeOutcome(
        new ProbeOutcome.Success(
            ProbeContainer.builder().format(complete.container().format()).build(),
            complete.streams(),
            new KeyframeKnowledge.NotReported()));
    var command =
        createStreamSessionCommand(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(streamingService.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.NoMediaSegments()));
    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getMediaFileId)
        .doesNotContain(savedMediaFile.getId());
    assertThat(FAKE_EXECUTOR.getStartedRequests())
        .extracting(TranscodeRequest::sourcePath)
        .doesNotContain(FilepathCodec.decode(savedMediaFile.getFilepathUri()));
  }

  @ParameterizedTest
  @CsvSource({"4,UNSPECIFIED", "4,ABSENT", "5,UNSPECIFIED", "5,ABSENT"})
  @DisplayName(
      "Should request a new probe without starting a job attempt when a stored result has no"
          + " compatible base without Dolby Vision")
  void
      shouldRequestNewProbeWithoutStartingJobAttemptWhenStoredResultHasNoCompatibleBaseWithoutDolbyVision(
          int probeVersion, String dolbyVision) {
    storeOutcome(ProbeFixture.completeProbe(defaultProbeBuilder().build()), probeVersion);
    assertThat(
            jdbc.update(
                """
                UPDATE media_file_stream_info
                SET dynamic_range_compatible_base = 'NONE', dolby_vision = ?
                WHERE media_file_id = ? AND dynamic_range_compatible_base IS NOT NULL
                """,
                dolbyVision,
                savedMediaFile.getId()))
        .isEqualTo(1);
    var command =
        createStreamSessionCommand(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(streamingService.createSession(command))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    assertThat(scheduledRequest())
        .hasValueSatisfying(
            request -> assertThat(request.probeVersion()).isEqualTo(ProbeVersion.CURRENT));
    assertThat(FAKE_EXECUTOR.getStartedRequests())
        .extracting(TranscodeRequest::sourcePath)
        .doesNotContain(FilepathCodec.decode(savedMediaFile.getFilepathUri()));
  }

  // The stored 73 s duration lists the playlist: the keyframe at 72 s presents less than 2 s
  // before the media end, so it opens no segment and segment 11 runs to the end.
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
        ProbeTaskRequest.builder()
            .mediaFileId(savedMediaFile.getId())
            .libraryId(savedMediaFile.getLibraryId())
            .filepathUri(savedMediaFile.getFilepathUri())
            .snapshot(sourceSnapshot)
            .probeVersion(ProbeVersion.CURRENT + 1)
            .build();
    probeTaskRequests.request(refresh);

    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getMediaProbe())
        .isEqualTo(
            ProbeFixture.completeProbe(storedProbe).mediaProbe().toBuilder()
                .sourceSnapshot(Optional.of(sourceSnapshot))
                .build());
    assertThat(playlistService.generateMultivariantPlaylist(session, "token"))
        .contains("BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720");
    assertThat(
            playlistService.generateMediaPlaylist(session, StreamSession.defaultVariant(), "token"))
        .contains(
            "#EXT-X-VERSION:6",
            "#EXT-X-MAP:URI=\"init.mp4?t=token\"",
            "#EXTINF:7.000000,",
            "segment11.m4s?t=token",
            "#EXT-X-ENDLIST")
        .doesNotContain("segment12.m4s", ".ts?");
    assertThat(scheduledRequest()).contains(refresh);
  }

  // The scan read a keyframe every 6 s. A copy opens a media segment at each of them, and a seek
  // to segment 15 reads from that segment's keyframe at 90 s.
  @Test
  @DisplayName(
      "Should copy the video at its scanned keyframes when a new probe of the unchanged file still"
          + " owes its packet scan")
  void shouldCopyVideoAtScannedKeyframesWhenNewProbeOfUnchangedFileStillOwesItsPacketScan() {
    var scanned = storeOutcomeKeepingScannedPositions();

    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());
    requestSegmentFifteen(session);

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getMediaProbe().keyframes())
        .isEqualTo(
            new KeyframeKnowledge.PacketScanRequiredWithKeptPositions(
                KeyframePacketScanReason.NO_CONTAINER_INDEX, scanned));
    assertThat(
            playlistService.generateMediaPlaylist(session, StreamSession.defaultVariant(), "token"))
        .contains("#EXTINF:6.000000,", "segment19.m4s?t=token", "#EXT-X-ENDLIST")
        .doesNotContain("segment20.m4s");
    assertThat(startedRequests(session))
        .extracting(TranscodeRequest::startSequenceNumber, TranscodeRequest::keyframeSeekPosition)
        .containsExactly(
            tuple(0, Optional.empty()), tuple(15, Optional.of(Duration.ofSeconds(90))));
  }

  @Test
  @DisplayName(
      "Should copy the video at its scanned keyframes when every packet scan that the new probe"
          + " owes failed")
  void shouldCopyVideoAtScannedKeyframesWhenEveryPacketScanThatNewProbeOwesFailed() {
    var scanned = storeOutcomeKeepingScannedPositions();
    assertThat(
            probeResults.publishKeyframePacketScanFailure(
                savedMediaFile.getId(), new ProbeInputs(sourceSnapshot, ProbeVersion.CURRENT)))
        .isTrue();

    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());
    requestSegmentFifteen(session);

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getMediaProbe().keyframes()).isEqualTo(new KeyframeKnowledge.Known(scanned));
    assertThat(startedRequests(session))
        .extracting(TranscodeRequest::startSequenceNumber, TranscodeRequest::keyframeSeekPosition)
        .containsExactly(
            tuple(0, Optional.empty()), tuple(15, Optional.of(Duration.ofSeconds(90))));
  }

  // A media error from bytes that an earlier scan read to the end shows that they changed, so the
  // file plays as any file without keyframe positions does.
  @Test
  @DisplayName(
      "Should encode the video when the packet scan that the new probe owes ends in a media error")
  void shouldEncodeVideoWhenPacketScanThatNewProbeOwesEndsInMediaError() {
    storeOutcomeKeepingScannedPositions();
    var mediaError =
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_MEDIA_ERROR);
    assertThat(
            probeResults.publishKeyframePacketScan(
                KeyframePacketScanPublication.builder()
                    .mediaFileId(savedMediaFile.getId())
                    .snapshot(sourceSnapshot)
                    .probeVersion(ProbeVersion.CURRENT)
                    .keyframes(mediaError)
                    .build()))
        .isTrue();

    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getMediaProbe().keyframes()).isEqualTo(mediaError);
  }

  // An outcome of the current probe version that keeps the positions that the earlier version's
  // packet scan read: a keyframe every 6 s of a 120 s file.
  private KeyframePositions storeOutcomeKeepingScannedPositions() {
    var unscanned =
        ProbeFixture.completeProbeBuilder(
                defaultProbeBuilder().duration(Duration.ofSeconds(120)).build())
            .keyframes(
                new KeyframeKnowledge.PacketScanRequired(
                    KeyframePacketScanReason.NO_CONTAINER_INDEX))
            .build();
    var scanned =
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(
                LongStream.range(0, 20)
                    .mapToObj(keyframe -> new KeyframePosition(keyframe * 6_000, keyframe * 6_000))
                    .toList())
            .build();
    storeOutcome(unscanned, ProbeVersion.CURRENT - 1);
    assertThat(
            probeResults.publishKeyframePacketScan(
                KeyframePacketScanPublication.builder()
                    .mediaFileId(savedMediaFile.getId())
                    .snapshot(sourceSnapshot)
                    .probeVersion(ProbeVersion.CURRENT - 1)
                    .keyframes(new KeyframeKnowledge.Known(scanned))
                    .build()))
        .isTrue();
    storeOutcome(unscanned, ProbeVersion.CURRENT);
    return scanned;
  }

  // The two calls that segment delivery makes for a media segment that is not in the store yet.
  private void requestSegmentFifteen(StreamSession session) {
    producerLifecycle.recover(
        producerLifecycle.prepareRequestedVariant(
            session.getSessionId(), StreamSession.defaultVariant(), "segment15.m4s"));
  }

  private static List<TranscodeRequest> startedRequests(StreamSession session) {
    return FAKE_EXECUTOR.getStartedRequests().stream()
        .filter(request -> request.sessionId().equals(session.getSessionId()))
        .toList();
  }

  private void storeProbe(MediaProbe probe) {
    storeOutcome(ProbeFixture.completeProbe(probe));
  }

  private void storeOutcome(ProbeOutcome.Success outcome) {
    storeOutcome(outcome, ProbeVersion.CURRENT);
  }

  private void storeOutcome(ProbeOutcome.Success outcome, int probeVersion) {
    assertThat(
            probeResults.publish(
                ProbePublication.builder()
                    .mediaFileId(savedMediaFile.getId())
                    .snapshot(sourceSnapshot)
                    .probeVersion(probeVersion)
                    .outcome(outcome)
                    .build()))
        .isTrue();
  }

  private Optional<ProbeTaskRequest> scheduledRequest() {
    return client
        .getScheduledExecution(
            TaskInstanceId.of(MediaProbeTask.NAME, savedMediaFile.getId().toString()))
        .map(execution -> (ProbeTaskRequest) execution.getData());
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
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }
}
