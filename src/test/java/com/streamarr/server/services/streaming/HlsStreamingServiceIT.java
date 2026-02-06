package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

@Tag("IntegrationTest")
@DisplayName("HLS Streaming Service Integration Tests")
class HlsStreamingServiceIT extends AbstractIntegrationTest {

  @Autowired private StreamingService streamingService;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();
  private static final FakeFfprobeService FAKE_FFPROBE = new FakeFfprobeService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  static FfprobeService ffprobeService() {
    return FAKE_FFPROBE;
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  private MediaFile savedMediaFile;

  @BeforeEach
  void setUp() {
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

    var retrieved = streamingService.accessSession(session.getSessionId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should remove session when session is destroyed")
  void shouldRemoveSessionWhenSessionIsDestroyed() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());

    streamingService.destroySession(session.getSessionId());

    assertThat(streamingService.accessSession(session.getSessionId())).isEmpty();
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

  @Test
  @DisplayName("Should resume transcode when segment requested from suspended session")
  void shouldResumeTranscodeWhenSegmentRequestedFromSuspendedSession() {
    var session = streamingService.createSession(savedMediaFile.getId(), defaultOptions());
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);

    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    FAKE_EXECUTOR.markDead(session.getSessionId());

    streamingService.resumeSessionIfNeeded(session.getSessionId(), "segment0.ts");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(FAKE_EXECUTOR.isRunning(session.getSessionId())).isTrue();
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }
}
