package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeLearnedStreamEndRepository;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Learned Stream End Listener Tests")
class LearnedStreamEndListenerTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4_096, Instant.parse("2026-11-06T09:00:00.123456789Z"));
  private static final Duration HEADER_VIDEO_END = Duration.ofSeconds(64);

  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private final FakeLearnedStreamEndRepository learnedStreamEnds =
      new FakeLearnedStreamEndRepository();
  private final LearnedStreamEndListener listener =
      new LearnedStreamEndListener(runtimeRegistry, learnedStreamEnds);

  @Test
  @DisplayName(
      "Should store where a completed attempt read a stream's last sample when the stream's header"
          + " states no end time")
  void shouldStoreWhereACompletedAttemptReadAStreamsLastSampleWhenTheHeaderStatesNoEndTime() {
    var session = savedSession(probeWithSnapshot(Optional.of(SNAPSHOT)));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(1, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), SNAPSHOT))
        .containsExactly(
            LearnedStreamEnd.builder()
                .mediaFileId(session.getMediaFileId())
                .snapshot(SNAPSHOT)
                .streamIndex(1)
                .endTime(Duration.ofMillis(84_021))
                .build());
  }

  @Test
  @DisplayName("Should store nothing for a stream whose header states its end time")
  void shouldStoreNothingForAStreamWhoseHeaderStatesItsEndTime() {
    var session = savedSession(probeWithSnapshot(Optional.of(SNAPSHOT)));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(0, HEADER_VIDEO_END.plusMillis(1))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the attempt's stream session has ended")
  void shouldStoreNothingWhenTheAttemptsStreamSessionHasEnded() {
    var session = savedSession(probeWithSnapshot(Optional.of(SNAPSHOT)));
    runtimeRegistry.removeById(session.getSessionId());

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(1, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the stream session's probe names no source snapshot")
  void shouldStoreNothingWhenTheStreamSessionsProbeNamesNoSourceSnapshot() {
    var session = savedSession(probeWithSnapshot(Optional.empty()));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(1, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), SNAPSHOT)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing for a stream that the session's probe does not list")
  void shouldStoreNothingForAStreamThatTheSessionsProbeDoesNotList() {
    var session = savedSession(probeWithSnapshot(Optional.of(SNAPSHOT)));

    listener.onJobAttemptCompleted(
        completion(session, new SourceStreamEnd(7, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), SNAPSHOT)).isEmpty();
  }

  private StreamSession savedSession(MediaProbe probe) {
    var session = defaultSessionBuilder().mediaProbe(probe).build();
    runtimeRegistry.save(session);
    return session;
  }

  // A video stream whose header states its end, and an audio stream whose header states none.
  private static MediaProbe probeWithSnapshot(Optional<SourceFileSnapshot> snapshot) {
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
