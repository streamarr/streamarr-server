package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
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
  @TestBean PlaybackAuthorityGate authorityGate;

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

  static PlaybackAuthorityGate authorityGate() {
    return _ -> true;
  }

  private MediaFile savedMediaFile;

  @BeforeEach
  void setUp() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var file =
        MediaFile.builder()
            .filepathUri("/media/movies/test-" + UUID.randomUUID() + ".mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .libraryId(library.getId())
            .build();
    savedMediaFile = mediaFileRepository.saveAndFlush(file);
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return streamingService.createSession(
        createStreamSessionCommand(mediaFileId, profileId, options));
  }

  @Test
  @DisplayName("Should assign session identity when media file is valid")
  void shouldAssignSessionIdentityWhenMediaFileIsValid() {
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session).isNotNull();
    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(savedMediaFile.getId());
  }

  @Test
  @DisplayName("Should initialize transcode pipeline when media file is valid")
  void shouldInitializeTranscodePipelineWhenMediaFileIsValid() {
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getMediaProbe()).isNotNull();
    assertThat(session.getTranscodeDecision()).isNotNull();
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should retrieve session when session exists")
  void shouldRetrieveSessionWhenSessionExists() {
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    var retrieved = streamingService.accessSession(playbackRequest(session));

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should remove session when session is destroyed")
  void shouldRemoveSessionWhenSessionIsDestroyed() {
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());

    streamingService.destroySession(session.getSessionId());

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName("Should throw when media file not found")
  void shouldThrowWhenMediaFileNotFound() {
    var nonExistentId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var options = defaultOptions();

    assertThatThrownBy(() -> createSession(nonExistentId, profileId, options))
        .isInstanceOf(MediaFileNotFoundException.class);
  }

  @Test
  @DisplayName("Should resume transcode when segment requested from suspended session")
  void shouldResumeTranscodeWhenSegmentRequestedFromSuspendedSession() {
    var session = createSession(savedMediaFile.getId(), UUID.randomUUID(), defaultOptions());
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
