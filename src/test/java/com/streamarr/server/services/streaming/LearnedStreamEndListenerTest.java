package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;

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
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LearnedStreamEndListener listener =
      new LearnedStreamEndListener(runtimeRegistry, learnedStreamEnds, meterRegistry);
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

    learnFromCompletion(completion(session, AUDIO_END));

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

    learnFromCompletion(
        completion(session, new SourceStreamEnd(0, HEADER_VIDEO_END.plusMillis(1))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the source file changed since its probe")
  void shouldStoreNothingWhenTheSourceFileChangedSinceItsProbe() throws IOException {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    Files.write(source, new byte[] {1, 2, 3, 4, 5, 6});

    learnFromCompletion(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the source file is gone")
  void shouldStoreNothingWhenTheSourceFileIsGone() throws IOException {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    Files.delete(source);

    learnFromCompletion(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the attempt's stream session has ended")
  void shouldStoreNothingWhenTheAttemptsStreamSessionHasEnded() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    runtimeRegistry.removeById(session.getSessionId());

    learnFromCompletion(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing when the stream session's probe names no source snapshot")
  void shouldStoreNothingWhenTheStreamSessionsProbeNamesNoSourceSnapshot() {
    var session = savedSession(probeOf(Optional.empty()));

    learnFromCompletion(completion(session, AUDIO_END));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName("Should store nothing for a stream when the session's probe does not list it")
  void shouldStoreNothingForAStreamWhenTheSessionsProbeDoesNotListIt() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));

    learnFromCompletion(completion(session, new SourceStreamEnd(7, Duration.ofMillis(84_021))));

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count each end it fails to store and let the completion finish when the database"
          + " refuses the write")
  void shouldCountEachEndItFailsToStoreAndLetTheCompletionFinishWhenTheDatabaseRefusesTheWrite() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    var withoutDatabase =
        new LearnedStreamEndListener(
            runtimeRegistry, new UnavailableLearnedStreamEndRepository(), meterRegistry);

    assertThatCode(
            () -> {
              withoutDatabase.onJobAttemptCompleted(completion(session, AUDIO_END));
              withoutDatabase.close();
            })
        .doesNotThrowAnyException();
    assertThat(meterRegistry.get("streamarr.streaming.learned_stream_end_store_failures").counter())
        .satisfies(failures -> assertThat(failures.count()).isEqualTo(1));
  }

  @Test
  @DisplayName(
      "Should return to the worker's report before the end is stored when the database holds the"
          + " write")
  void shouldReturnToTheWorkersReportBeforeTheEndIsStoredWhenTheDatabaseHoldsTheWrite() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    var writeReleased = new CountDownLatch(1);
    var holding =
        new LearnedStreamEndListener(
            runtimeRegistry,
            new HeldLearnedStreamEndRepository(learnedStreamEnds, writeReleased),
            meterRegistry);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> holding.onJobAttemptCompleted(completion(session, AUDIO_END)));
    writeReleased.countDown();
    holding.close();

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should learn nothing and let the report finish when a completion arrives after close")
  void shouldLearnNothingAndLetTheReportFinishWhenACompletionArrivesAfterClose() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    listener.close();

    assertThatCode(() -> listener.onJobAttemptCompleted(completion(session, AUDIO_END)))
        .doesNotThrowAnyException();
    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should store the reported ends when another listener of the completion ends the stream"
          + " session first")
  void shouldStoreTheReportedEndsWhenAnotherListenerOfTheCompletionEndsTheStreamSessionFirst() {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          SessionEndingListener.class, () -> new SessionEndingListener(runtimeRegistry));
      context.registerBean(LearnedStreamEndListener.class, () -> listener);
      context.refresh();

      context.publishEvent(completion(session, AUDIO_END));
    }

    assertThat(runtimeRegistry.findById(session.getSessionId())).isEmpty();
    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).hasSize(1);
  }

  // Ends the stream session on the same completion, as short-copy recovery may.
  private record SessionEndingListener(FakeRuntimeStreamSessionRegistry runtimeRegistry) {

    @EventListener
    public void onJobAttemptCompleted(JobAttemptCompletedEvent event) {
      runtimeRegistry.removeById(event.streamSessionId());
    }
  }

  private void learnFromCompletion(JobAttemptCompletedEvent completion) {
    listener.onJobAttemptCompleted(completion);
    listener.close();
  }

  // Holds each write until the test releases it, as a database lock would.
  private record HeldLearnedStreamEndRepository(
      LearnedStreamEndRepository delegate, CountDownLatch writeReleased)
      implements LearnedStreamEndRepository {

    @Override
    public boolean store(LearnedStreamEnd learned) {
      try {
        writeReleased.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return false;
      }

      return delegate.store(learned);
    }

    @Override
    public List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot) {
      return delegate.findFor(mediaFileId, snapshot);
    }
  }

  private static final class UnavailableLearnedStreamEndRepository
      implements LearnedStreamEndRepository {

    @Override
    public boolean store(LearnedStreamEnd learned) {
      throw new DataAccessResourceFailureException("The database refused the connection.");
    }

    @Override
    public List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot) {
      return List.of();
    }
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
