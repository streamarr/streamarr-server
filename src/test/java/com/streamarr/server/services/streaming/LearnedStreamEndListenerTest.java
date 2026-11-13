package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
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
import com.streamarr.server.support.BoundedTask;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  // A close that waited for a held store would never return.
  private static final Duration CLOSE_RETURN_BOUND = Duration.ofSeconds(10);
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
    var held = new HeldLearnedStreamEndRepository(learnedStreamEnds);
    var holding = new LearnedStreamEndListener(runtimeRegistry, held, meterRegistry);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> holding.onJobAttemptCompleted(completion(session, AUDIO_END)));
    held.release();
    holding.close();

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should cancel the store and close while the database still holds the write when the server"
          + " shuts down")
  void shouldCancelTheStoreAndCloseWhileTheDatabaseStillHoldsTheWriteWhenTheServerShutsDown()
      throws Exception {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    var held = new HeldLearnedStreamEndRepository(learnedStreamEnds);
    var holding = new LearnedStreamEndListener(runtimeRegistry, held, meterRegistry);
    holding.onJobAttemptCompleted(completion(session, AUDIO_END));
    awaitLatch(held.storing);

    BoundedTask.runWithin(CLOSE_RETURN_BOUND, holding::close);
    held.awaitTheStoringThreadsEnd();

    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
    assertThat(storeFailures()).isZero();
  }

  @Test
  @DisplayName(
      "Should store the end that a later completion reports when a shutdown cancelled the store"
          + " of an earlier completion")
  void shouldStoreTheEndThatALaterCompletionReportsWhenAShutdownCancelledTheStoreOfAnEarlierOne()
      throws Exception {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    var held = new HeldLearnedStreamEndRepository(learnedStreamEnds);
    var stoppedServer = new LearnedStreamEndListener(runtimeRegistry, held, meterRegistry);
    stoppedServer.onJobAttemptCompleted(completion(session, AUDIO_END));
    awaitLatch(held.storing);
    BoundedTask.runWithin(CLOSE_RETURN_BOUND, stoppedServer::close);
    held.awaitTheStoringThreadsEnd();
    var storedByTheStoppedServer =
        learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot);

    learnFromCompletion(completion(session, AUDIO_END));

    assertThat(storedByTheStoppedServer).isEmpty();
    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot))
        .extracting(LearnedStreamEnd::endTime)
        .containsExactly(AUDIO_END.endTime());
  }

  @Test
  @DisplayName(
      "Should store no further end of the completion when a shutdown cancelled the store of its"
          + " first end")
  void shouldStoreNoFurtherEndOfTheCompletionWhenAShutdownCancelledTheStoreOfItsFirstEnd()
      throws Exception {
    var session = savedSession(probeWithTwoStreamsThatStateNoEnd());
    var held = new HeldLearnedStreamEndRepository(learnedStreamEnds);
    var holding = new LearnedStreamEndListener(runtimeRegistry, held, meterRegistry);
    holding.onJobAttemptCompleted(
        completion(session, List.of(AUDIO_END, new SourceStreamEnd(2, Duration.ofSeconds(80)))));
    awaitLatch(held.storing);

    BoundedTask.runWithin(CLOSE_RETURN_BOUND, holding::close);
    held.awaitTheStoringThreadsEnd();

    assertThat(held.storeCalls()).isOne();
  }

  @Test
  @DisplayName(
      "Should cancel the store at once and keep the interrupt when its own close is interrupted")
  void shouldCancelTheStoreAtOnceAndKeepTheInterruptWhenItsOwnCloseIsInterrupted()
      throws Exception {
    var session = savedSession(probeOf(Optional.of(probedSnapshot)));
    var held = new HeldLearnedStreamEndRepository(learnedStreamEnds);
    var holding = new LearnedStreamEndListener(runtimeRegistry, held, meterRegistry);
    holding.onJobAttemptCompleted(completion(session, AUDIO_END));
    awaitLatch(held.storing);
    boolean closingThreadKeptItsInterrupt;

    try (var shutdown =
        BoundedTask.start(
            () -> {
              Thread.currentThread().interrupt();
              holding.close();
              return Thread.currentThread().isInterrupted();
            })) {
      closingThreadKeptItsInterrupt = shutdown.await(CLOSE_RETURN_BOUND);
    }

    held.awaitTheStoringThreadsEnd();
    assertThat(closingThreadKeptItsInterrupt).isTrue();
    assertThat(learnedStreamEnds.findFor(session.getMediaFileId(), probedSnapshot)).isEmpty();
    assertThat(storeFailures()).isZero();
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

  /**
   * Holds each write until the test releases it, as a database lock does. A write that is
   * interrupted fails as a JDBC statement does when its thread is interrupted.
   */
  private static final class HeldLearnedStreamEndRepository implements LearnedStreamEndRepository {

    private final LearnedStreamEndRepository delegate;
    private final CountDownLatch storing = new CountDownLatch(1);
    private final CountDownLatch writeReleased = new CountDownLatch(1);
    private final AtomicReference<Thread> storingThread = new AtomicReference<>();
    private final AtomicInteger storeCalls = new AtomicInteger();

    private HeldLearnedStreamEndRepository(LearnedStreamEndRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean store(LearnedStreamEnd learned) {
      storeCalls.incrementAndGet();
      storingThread.set(Thread.currentThread());
      storing.countDown();
      try {
        writeReleased.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new DataAccessResourceFailureException(
            "The connection closed at the interrupt.", interrupted);
      }

      return delegate.store(learned);
    }

    @Override
    public List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot) {
      return delegate.findFor(mediaFileId, snapshot);
    }

    private void release() {
      writeReleased.countDown();
    }

    private int storeCalls() {
      return storeCalls.get();
    }

    // The listener decides on the storing thread whether the write failed, after the write
    // returns, so the thread's end is the only signal that it has decided.
    private void awaitTheStoringThreadsEnd() throws InterruptedException {
      assertThat(storingThread.get().join(CLOSE_RETURN_BOUND)).isTrue();
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

  // A video stream whose header states its end, and two audio streams whose headers state none.
  private MediaProbe probeWithTwoStreamsThatStateNoEnd() {
    return defaultProbeBuilder()
        .sourceSnapshot(Optional.of(probedSnapshot))
        .streams(
            List.of(
                StreamInfo.builder()
                    .index(0)
                    .codecType("video")
                    .endTime(Optional.of(HEADER_VIDEO_END))
                    .build(),
                StreamInfo.builder().index(1).codecType("audio").build(),
                StreamInfo.builder().index(2).codecType("audio").build()))
        .build();
  }

  private double storeFailures() {
    return meterRegistry.counter("streamarr.streaming.learned_stream_end_store_failures").count();
  }

  private static SourceFileSnapshot snapshotOf(Path file) throws IOException {
    return SourceFileSnapshot.of(Files.readAttributes(file, BasicFileAttributes.class));
  }

  private static JobAttemptCompletedEvent completion(
      StreamSession session, SourceStreamEnd streamEnd) {
    return completion(session, List.of(streamEnd));
  }

  private static JobAttemptCompletedEvent completion(
      StreamSession session, List<SourceStreamEnd> streamEnds) {
    return JobAttemptCompletedEvent.builder()
        .streamSessionId(session.getSessionId())
        .jobAttemptId(UUID.randomUUID())
        .variantLabel(StreamSession.defaultVariant())
        .streamEnds(streamEnds)
        .build();
  }
}
