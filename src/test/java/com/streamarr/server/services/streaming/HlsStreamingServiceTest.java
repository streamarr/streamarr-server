package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
    var properties = new StreamingProperties(3, 6, 60);
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
            properties);
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
  @DisplayName("shouldCreateSessionForValidMediaFile")
  void shouldCreateSessionForValidMediaFile() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(session).isNotNull();
    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(file.getId());
    assertThat(session.getMediaProbe()).isNotNull();
    assertThat(session.getTranscodeDecision()).isNotNull();
    assertThat(session.getHandle()).isNotNull();
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("shouldStartTranscodeWhenCreatingSession")
  void shouldStartTranscodeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = service.createSession(file.getId(), defaultOptions());

    assertThat(transcodeExecutor.getStarted()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("shouldThrowWhenMediaFileNotFound")
  void shouldThrowWhenMediaFileNotFound() {
    var invalidId = UUID.randomUUID();

    assertThatThrownBy(() -> service.createSession(invalidId, defaultOptions()))
        .isInstanceOf(MediaFileNotFoundException.class);
  }

  @Test
  @DisplayName("shouldGetExistingSession")
  void shouldGetExistingSession() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    var retrieved = service.getSession(session.getSessionId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("shouldReturnEmptyForNonexistentSession")
  void shouldReturnEmptyForNonexistentSession() {
    var result = service.getSession(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("shouldUpdateLastAccessedAtOnGetSession")
  void shouldUpdateLastAccessedAtOnGetSession() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());
    var initialAccess = session.getLastAccessedAt();

    var retrieved = service.getSession(session.getSessionId());

    assertThat(retrieved.get().getLastAccessedAt()).isAfterOrEqualTo(initialAccess);
  }

  @Test
  @DisplayName("shouldDestroySession")
  void shouldDestroySession() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(service.getSession(session.getSessionId())).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
  }

  @Test
  @DisplayName("shouldRejectFullTranscodeWhenAtConcurrencyLimit")
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
  @DisplayName("shouldAllowRemuxSessionsWhenAtTranscodeConcurrencyLimit")
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
  @DisplayName("shouldSetCorrectTranscodeDecisionOnSession")
  void shouldSetCorrectTranscodeDecisionOnSession() {
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
  @DisplayName("shouldSeekToNewPosition")
  void shouldSeekToNewPosition() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());
    var originalSessionId = session.getSessionId();

    var seeked = service.seekSession(originalSessionId, 300);

    assertThat(seeked.getSessionId()).isEqualTo(originalSessionId);
    assertThat(seeked.getSeekPosition()).isEqualTo(300);
    assertThat(seeked.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.isRunning(originalSessionId)).isTrue();
  }

  @Test
  @DisplayName("shouldStopOldTranscodeOnSeek")
  void shouldStopOldTranscodeOnSeek() {
    var file = seedMediaFile();
    var session = service.createSession(file.getId(), defaultOptions());

    service.seekSession(session.getSessionId(), 300);

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("shouldThrowWhenSeekingNonexistentSession")
  void shouldThrowWhenSeekingNonexistentSession() {
    var invalidId = UUID.randomUUID();

    assertThatThrownBy(() -> service.seekSession(invalidId, 300))
        .isInstanceOf(SessionNotFoundException.class);
  }

  @Test
  @DisplayName("shouldStartMultipleVariantsForAutoQualityFullTranscode")
  void shouldStartMultipleVariantsForAutoQualityFullTranscode() {
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
  @DisplayName("shouldUseSingleVariantForAutoQualityRemux")
  void shouldUseSingleVariantForAutoQualityRemux() {
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
  @DisplayName("shouldUseSingleVariantForExplicitQuality")
  void shouldUseSingleVariantForExplicitQuality() {
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
}
