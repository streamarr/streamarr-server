package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class HlsStreamingServiceTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeFfprobeService ffprobeService;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    ffprobeService = new FakeFfprobeService();
    var properties = new StreamingProperties(3, 6, 60, null);
    var decisionService = new TranscodeDecisionService();

    var qualityLadderService = new QualityLadderService();

    service =
        new HlsStreamingService(
            mediaFileRepository,
            transcodeExecutor,
            segmentStore,
            ffprobeService,
            decisionService,
            qualityLadderService,
            properties,
            new FakeStreamSessionRepository());
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private MediaFile seedMediaFile() {
    var file =
        MediaFile.builder()
            .filepath("/media/movies/test.mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .build();
    return mediaFileRepository.save(file);
  }

  @Test
  @DisplayName("Should assign session ID and media file when creating session")
  void shouldAssignSessionIdAndMediaFileWhenCreatingSession() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(file.getId());
  }

  @Test
  @DisplayName("Should populate media probe when creating session")
  void shouldPopulateMediaProbeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(session.getMediaProbe()).isNotNull();
  }

  @Test
  @DisplayName("Should populate transcode decision when creating session")
  void shouldPopulateTranscodeDecisionWhenCreatingSession() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(session.getTranscodeDecision()).isNotNull();
  }

  @Test
  @DisplayName("Should start transcode when creating session")
  void shouldStartTranscodeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(transcodeExecutor.getStarted()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("Should throw when media file not found")
  void shouldThrowWhenMediaFileNotFound() {
    var invalidId = UUID.randomUUID();

    assertThatThrownBy(() -> service.createSession(invalidId, defaultOptions()))
        .isInstanceOf(MediaFileNotFoundException.class);
  }

  @Test
  @DisplayName("Should return session when session exists")
  void shouldReturnSessionWhenSessionExists() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    var retrieved = service.accessSession(session.getSessionId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should return empty when session does not exist")
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    var result = service.accessSession(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should update last accessed timestamp when session is retrieved")
  void shouldUpdateLastAccessedTimestampWhenSessionIsRetrieved() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());
    var initialAccess = session.getLastAccessedAt();

    var retrieved = service.accessSession(session.getSessionId());

    assertThat(retrieved.get().getLastAccessedAt()).isAfterOrEqualTo(initialAccess);
  }

  @Test
  @DisplayName("Should remove session and stop transcode when session is destroyed")
  void shouldRemoveSessionAndStopTranscodeWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(service.accessSession(session.getSessionId())).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
  }

  @Test
  @DisplayName("Should reject full transcode when at concurrency limit")
  void shouldRejectFullTranscodeWhenAtConcurrencyLimit() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      service.createSession(file.getId(), options);
    }

    var oneMore = seedMediaFile();
    assertThatThrownBy(() -> service.createSession(oneMore.getId(), options))
        .isInstanceOf(MaxConcurrentTranscodesException.class);
  }

  @Test
  @DisplayName("Should allow remux sessions when at transcode concurrency limit")
  void shouldAllowRemuxSessionsWhenAtTranscodeConcurrencyLimit() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var transcodeOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      service.createSession(file.getId(), transcodeOptions);
    }

    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var remuxOptions = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), remuxOptions);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should set full transcode decision when video codec is incompatible")
  void shouldSetFullTranscodeDecisionWhenVideoCodecIsIncompatible() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(90))
            .framerate(24.0)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var session = service.createSession(file.getId(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should update seek position when seeking session")
  void shouldUpdateSeekPositionWhenSeekingSession() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());
    var originalSessionId = session.getSessionId();

    var seeked = service.seekSession(originalSessionId, 300);

    assertThat(seeked.getSessionId()).isEqualTo(originalSessionId);
    assertThat(seeked.getSeekPosition()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should restart transcode when seeking")
  void shouldRestartTranscodeWhenSeeking() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    var seeked = service.seekSession(session.getSessionId(), 300);

    assertThat(seeked.getHandle()).isNotNull();
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("Should stop old transcode when seeking to new position")
  void shouldStopOldTranscodeWhenSeekingToNewPosition() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    service.seekSession(session.getSessionId(), 300);

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should throw when seeking nonexistent session")
  void shouldThrowWhenSeekingNonexistentSession() {
    var invalidId = UUID.randomUUID();

    assertThatThrownBy(() -> service.seekSession(invalidId, 300))
        .isInstanceOf(SessionNotFoundException.class);
  }

  @Test
  @DisplayName("Should start multiple variants when auto quality with full transcode")
  void shouldStartMultipleVariantsWhenAutoQualityWithFullTranscode() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = service.createSession(file.getId(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(session.getVariantHandles()).hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName("Should use single variant when auto quality with remux")
  void shouldUseSingleVariantWhenAutoQualityWithRemux() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = service.createSession(file.getId(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isNotNull();
  }

  @Test
  @DisplayName("Should use single variant when explicit quality is specified")
  void shouldUseSingleVariantWhenExplicitQualityIsSpecified() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.HIGH_720P)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = service.createSession(file.getId(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isNotNull();
  }

  @Test
  @DisplayName("Should pass variant label to transcode request for ABR session")
  void shouldPassVariantLabelToTranscodeRequestForAbrSession() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = service.createSession(file.getId(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(transcodeExecutor.getStartedVariants())
        .containsExactlyInAnyOrderElementsOf(
            session.getVariants().stream().map(v -> v.label()).toList());
  }

  @Test
  @DisplayName("Should make session retrievable during transcode start")
  void shouldMakeSessionRetrievableDuringTranscodeStart() {
    var serviceRef = new AtomicReference<HlsStreamingService>();
    var capturedSession = new AtomicReference<Optional<StreamSession>>();

    var spyExecutor =
        new FakeTranscodeExecutor() {
          @Override
          public TranscodeHandle start(TranscodeRequest request) {
            capturedSession.set(serviceRef.get().accessSession(request.sessionId()));
            return super.start(request);
          }
        };

    var spyService =
        new HlsStreamingService(
            mediaFileRepository,
            spyExecutor,
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            new StreamingProperties(3, 6, 60, null),
            new FakeStreamSessionRepository());
    serviceRef.set(spyService);

    var file = seedMediaFile();

    spyService.createSession(file.getId(), defaultOptions());

    assertThat(capturedSession.get()).isPresent();
  }

  @Test
  @DisplayName("Should not throw when destroying nonexistent session")
  void shouldNotThrowWhenDestroyingNonexistentSession() {
    service.destroySession(UUID.randomUUID());

    assertThat(transcodeExecutor.getStopped()).isEmpty();
  }

  @Test
  @DisplayName("Should return all sessions when multiple sessions created")
  void shouldReturnAllSessionsWhenMultipleSessionsCreated() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    service.createSession(file1.getId(), defaultOptions());
    service.createSession(file2.getId(), defaultOptions());

    var all = service.getAllSessions();

    assertThat(all).hasSize(2);
  }

  @Test
  @DisplayName("Should return active session count when sessions exist")
  void shouldReturnActiveSessionCountWhenSessionsExist() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    var session1 = service.createSession(file1.getId(), defaultOptions());
    service.createSession(file2.getId(), defaultOptions());

    service.destroySession(session1.getSessionId());

    assertThat(service.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should truncate variants when exceeding available slots")
  void shouldTruncateVariantsWhenExceedingAvailableSlots() {
    var properties = new StreamingProperties(2, 6, 60, null);
    var limitedService =
        new HlsStreamingService(
            mediaFileRepository,
            new FakeTranscodeExecutor(),
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            properties,
            new FakeStreamSessionRepository());

    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = limitedService.createSession(file.getId(), options);

    assertThat(session.getVariants()).hasSize(2);
  }
}
