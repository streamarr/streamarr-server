package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeLearnedStreamEndRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Learned Stream End Listener Tests")
class LearnedStreamEndListenerTest {

  private static final Duration HEADER_VIDEO_END = Duration.ofSeconds(64);
  private static final SourceStreamEnd AUDIO_END =
      new SourceStreamEnd(1, Duration.ofMillis(84_021));

  @TempDir private Path temporaryDirectory;

  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeLearnedStreamEndRepository learnedStreamEnds =
      new FakeLearnedStreamEndRepository(probeOutcomes);
  private final LearnedStreamEndListener listener =
      new LearnedStreamEndListener(runtimeRegistry, learnedStreamEnds);
  private Path source;
  private SourceFileSnapshot probedSnapshot;

  @BeforeEach
  void writeTheProbedSource() throws IOException {
    source = Files.write(temporaryDirectory.resolve("movie.mkv"), new byte[] {1, 2, 3});
    probedSnapshot = snapshotOf(source);
  }

  @Test
  @DisplayName(
      "Should store where a completed attempt read a stream's last sample when the stream's header"
          + " states no end time")
  void shouldStoreWhereACompletedAttemptReadAStreamsLastSampleWhenTheHeaderStatesNoEndTime() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));

    listener.onJobAttemptCompleted(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot))
        .containsExactly(
            LearnedStreamEnd.builder()
                .mediaFileId(session.getMediaFileId())
                .snapshot(probedSnapshot)
                .streamIndex(1)
                .endTime(Duration.ofMillis(84_021))
                .build());
  }

  @Test
  @DisplayName("Should store nothing for a stream when its header states its end time")
  void shouldStoreNothingForAStreamWhenItsHeaderStatesItsEndTime() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(0, HEADER_VIDEO_END.plusMillis(1))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the source file changed since its probe")
  void shouldStoreNothingWhenTheSourceFileChangedSinceItsProbe() throws IOException {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    Files.write(source, new byte[] {1, 2, 3, 4, 5, 6});

    listener.onJobAttemptCompleted(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the source file is gone")
  void shouldStoreNothingWhenTheSourceFileIsGone() throws IOException {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    Files.delete(source);

    listener.onJobAttemptCompleted(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the attempt's stream session has ended")
  void shouldStoreNothingWhenTheAttemptsStreamSessionHasEnded() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    runtimeRegistry.removeById(session.getSessionId());

    listener.onJobAttemptCompleted(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the stream session's probe names no source snapshot")
  void shouldStoreNothingWhenTheStreamSessionsProbeNamesNoSourceSnapshot() {
    var session = savedSession(probeOf(Optional.empty()));

    listener.onJobAttemptCompleted(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing for a stream when the session's probe does not list it")
  void shouldStoreNothingForAStreamWhenTheSessionsProbeDoesNotListIt() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(7, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  // The file's stored probe outcome holds the snapshot that the source had when it was probed.
  private StreamSession savedSession(MediaProbe probe) {
    var session = defaultSessionBuilder().sourcePath(source).mediaProbe(probe).build();
    probeOutcomes.store(
        MediaFileContainerInfo.builder()
            .mediaFileId(session.getMediaFileId())
            .snapshot(probedSnapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .container(ProbeContainer.builder().build())
            .build());
    runtimeRegistry.save(session);
    return session;
  }

  // A video stream whose header states its end, and an audio stream whose header states none.
  private static MediaProbe probeOf(Optional<SourceFileSnapshot> snapshot) {
    return defaultProbeBuilder()
        .sourceSnapshot(snapshot)
        .streams(
            List.of(
                StreamInfo.builder()
                    .index(0)
                    .codecType("video")
                    .endTime(Optional.of(HEADER_VIDEO_END))
                    .build(),
                StreamInfo.builder().index(1).codecType("audio").build()))
        .build();
  }

  private static SourceFileSnapshot snapshotOf(Path file) throws IOException {
    return SourceFileSnapshot.of(Files.readAttributes(file, BasicFileAttributes.class));
  }

  private static JobAttemptCompletedEvent completion(
      StreamSession session, SourceStreamEnd streamEnd) {
    return JobAttemptCompletedEvent.builder()
        .streamSessionId(session.getSessionId())
        .jobAttemptId(UUID.randomUUID())
        .variantLabel(StreamSession.defaultVariant())
        .streamEnds(List.of(streamEnd))
        .build();
  }
}
