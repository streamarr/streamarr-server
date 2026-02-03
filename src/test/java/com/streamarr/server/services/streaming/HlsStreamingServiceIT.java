package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("IntegrationTest")
@DisplayName("HLS Streaming Service Integration Tests")
class HlsStreamingServiceIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;

  @MockitoBean private TranscodeExecutor transcodeExecutor;
  @MockitoBean private FfprobeService ffprobeService;
  @MockitoBean private SegmentStore segmentStore;

  private MediaFile savedMediaFile;

  @BeforeEach
  void setUp() {
    when(ffprobeService.probe(any(Path.class)))
        .thenReturn(
            MediaProbe.builder()
                .duration(Duration.ofMinutes(120))
                .framerate(23.976)
                .width(1920)
                .height(1080)
                .videoCodec("h264")
                .audioCodec("aac")
                .bitrate(5_000_000L)
                .build());

    when(transcodeExecutor.start(any()))
        .thenReturn(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));

    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var file =
        MediaFile.builder()
            .filepath("/media/movies/test-" + UUID.randomUUID() + ".mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .libraryId(library.getId())
            .build();
    savedMediaFile = mediaFileRepository.saveAndFlush(file);
  }

  @Test
  @DisplayName("Should assign session identity when media file is valid")
  void shouldAssignSessionIdentityWhenMediaFileIsValid() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    assertThat(session).isNotNull();
    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(savedMediaFile.getId());
  }

  @Test
  @DisplayName("Should initialize transcode pipeline when media file is valid")
  void shouldInitializeTranscodePipelineWhenMediaFileIsValid() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    assertThat(session.getMediaProbe()).isNotNull();
    assertThat(session.getTranscodeDecision()).isNotNull();
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should retrieve session when session exists")
  void shouldRetrieveSessionWhenSessionExists() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    var retrieved = streamingService.getSession(session.getSessionId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should remove session when session is destroyed")
  void shouldRemoveSessionWhenSessionIsDestroyed() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    streamingService.destroySession(session.getSessionId());

    assertThat(streamingService.getSession(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should throw when media file not found")
  void shouldThrowWhenMediaFileNotFound() {
    assertThatThrownBy(() -> streamingService.createSession(UUID.randomUUID(), defaultOptions()))
        .isInstanceOf(MediaFileNotFoundException.class);
  }

  @Test
  @DisplayName("Should update seek position when seeking session")
  void shouldUpdateSeekPositionWhenSeekingSession() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    var seeked = streamingService.seekSession(session.getSessionId(), 300);

    assertThat(seeked.getSeekPosition()).isEqualTo(300);
    assertThat(seeked.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should throw when seeking nonexistent session")
  void shouldThrowWhenSeekingNonexistentSession() {
    assertThatThrownBy(() -> streamingService.seekSession(UUID.randomUUID(), 300))
        .isInstanceOf(SessionNotFoundException.class);
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }
}
