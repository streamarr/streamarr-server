package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.ServerSettingsFixture.defaultServerSettings;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Unserved Segment Counting Tests")
class UnservedSegmentCountingTest {

  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(60);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock();
  private int transcodeLimit = 3;
  private FakeTranscodeExecutor transcodeExecutor = new FakeTranscodeExecutor();
  private FakeSegmentStore segmentStore = new FakeSegmentStore();
  private FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private StreamingProperties properties;
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;
  private UnservedSegments unservedSegments;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    startServer();
  }

  // Builds the server from the fakes that the fields hold, so a test replaces one and starts again.
  private void startServer() {
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(transcodeLimit)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(SESSION_TIMEOUT)
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .heldRequests(transcodeExecutor.heldSegmentRequests())
            .meterRegistry(meters)
            .build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    unservedSegments = rig.unservedSegments();
    var probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultProbe(defaultProbeBuilder().videoCodec("hevc").build());
    service =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(registry)
            .producerLifecycle(lifecycle)
            .deliveryCoordinator(coordinator)
            .unservedSegments(unservedSegments)
            .meterRegistry(meters)
            .build();
  }

  @Test
  @DisplayName(
      "Should count one unserved segment with the cause session_ended when a stream session with"
          + " two stored segments, one of them served, ends")
  void
      shouldCountOneUnservedSegmentAsSessionEndedWhenASessionWithOneOfTwoStoredSegmentsServedEnds() {
    var session = startStreamSession();
    store(session, 0);
    store(session, 1);
    serve(session, 0);

    service.destroySession(session.getSessionId());

    assertThat(unservedSegments("session_ended")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count a stream session's unserved segments when the session ends although stopping"
          + " its job attempts failed")
  void shouldCountAStreamSessionsUnservedSegmentsWhenItEndsAlthoughStoppingItsJobAttemptsFailed() {
    var session = startStreamSession();
    var sessionId = session.getSessionId();
    store(session, 0);
    transcodeExecutor.failOnStop(sessionId);

    assertThatThrownBy(() -> service.destroySession(sessionId))
        .isInstanceOf(TranscodeException.class);

    assertThat(unservedSegments("session_ended")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count no unserved segment when a request was answered with a segment before the"
          + " segment was announced as stored")
  void shouldCountNoUnservedSegmentWhenARequestWasAnsweredBeforeItsSegmentWasAnnouncedAsStored() {
    var session = startStreamSession();
    var name = storeWithoutAnnouncing(session, 0);
    serve(session, 0);
    announceStored(session, name);

    service.destroySession(session.getSessionId());

    assertThat(unservedSegments("session_ended")).isZero();
  }

  @Test
  @DisplayName(
      "Should count no unserved segment when a job attempt stores a served segment again under"
          + " its name")
  void shouldCountNoUnservedSegmentWhenAJobAttemptStoresAServedSegmentAgainUnderItsName() {
    var session = startStreamSession();
    var name = store(session, 0);
    serve(session, 0);
    announceStored(session, name);

    service.destroySession(session.getSessionId());

    assertThat(unservedSegments("session_ended")).isZero();
  }

  @Test
  @DisplayName(
      "Should count an unserved segment once when job attempts stored it twice under its name")
  void shouldCountAnUnservedSegmentOnceWhenJobAttemptsStoredItTwiceUnderItsName() {
    var session = startStreamSession();
    var name = store(session, 0);
    announceStored(session, name);

    service.destroySession(session.getSessionId());

    assertThat(unservedSegments("session_ended")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count unserved segments with the cause disk_pressure_eviction when the reaper evicts"
          + " an idle stream session from a nearly full segment folder")
  void shouldCountUnservedSegmentsAsDiskPressureEvictionWhenTheReaperEvictsAnIdleStreamSession() {
    var session = startStreamSession();
    store(session, 0);
    store(session, 1);
    serve(session, 0);
    session.setLastAccessedAt(clock.instant().minus(Duration.ofMinutes(5)));

    reaperOfANearlyFullSegmentFolder().reapSessions();

    assertThat(unservedSegments("disk_pressure_eviction")).isEqualTo(1);
    assertThat(unservedSegments("session_ended")).isZero();
  }

  @Test
  @DisplayName(
      "Should count unserved segments with the cause session_start_failed when a stream session's"
          + " start fails after its job attempt stored a segment")
  void shouldCountUnservedSegmentsAsSessionStartFailedWhenAStartFailsAfterAJobAttemptStoredOne() {
    transcodeExecutor = new StoringThenFailingExecutor();
    startServer();
    var command = createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID());

    assertThatThrownBy(() -> service.createSession(command.build()))
        .isInstanceOf(TranscodeException.class);

    assertThat(unservedSegments("session_start_failed")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count unserved segments with the cause server_shutdown when the server stops while a"
          + " stream session still stores segments")
  void shouldCountUnservedSegmentsAsServerShutdownWhenTheServerStopsWhileASessionStoresSegments() {
    var session = startStreamSession();
    store(session, 0);
    store(session, 1);
    serve(session, 0);

    unservedSegments.serverShutsDown();

    assertThat(unservedSegments("server_shutdown")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count a stream session's unserved segments once when the session ends after the"
          + " server's shutdown counted them")
  void shouldCountAStreamSessionsUnservedSegmentsOnceWhenTheSessionEndsAfterShutdownCountedThem() {
    var session = startStreamSession();
    store(session, 0);
    unservedSegments.serverShutsDown();

    service.destroySession(session.getSessionId());

    assertThat(unservedSegments("server_shutdown")).isEqualTo(1);
    assertThat(unservedSegments("session_ended")).isZero();
  }

  @Test
  @DisplayName(
      "Should count nothing when a media segment is announced after its stream session ended")
  void shouldCountNothingWhenAMediaSegmentIsAnnouncedAfterItsStreamSessionEnded() {
    var session = startStreamSession();
    service.destroySession(session.getSessionId());

    announceStored(session, storedName(session, 0));
    unservedSegments.serverShutsDown();

    assertThat(unservedSegments("session_ended")).isZero();
    assertThat(unservedSegments("server_shutdown")).isZero();
  }

  @Test
  @DisplayName(
      "Should count nothing when a media segment is announced for a stream session whose start"
          + " the transcode limit refused")
  void shouldCountNothingWhenASegmentIsAnnouncedForASessionWhoseStartTheTranscodeLimitRefused() {
    var startOnNextSave = new AtomicReference<Runnable>();
    transcodeLimit = 1;
    registry =
        new FakeRuntimeStreamSessionRegistry() {
          @Override
          public void save(StreamSession session) {
            super.save(session);
            Optional.ofNullable(startOnNextSave.getAndSet(null)).ifPresent(Runnable::run);
          }
        };
    startServer();
    var refused =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID()).build();
    // Another stream session takes the only place under the limit after this one's admission.
    startOnNextSave.set(this::startStreamSession);

    var outcome = service.createSession(refused);

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(new CreateStreamSessionRejection.TranscodeCapacityUnavailable(1)));
    unservedSegments.onMediaSegmentStored(
        new MediaSegmentStoredEvent(refused.streamSessionId(), "1080p/segment0.m4s"));
    unservedSegments.serverShutsDown();
    assertThat(unservedSegments("server_shutdown")).isZero();
  }

  @Test
  @DisplayName(
      "Should answer that the session ended and count the segment once as unserved when its"
          + " stream session ends after a request opened the segment and before the answer")
  void shouldAnswerSessionEndedAndCountTheSegmentOnceWhenItsSessionEndsAfterARequestOpenedIt()
      throws Exception {
    var parkingStore = new OpenParkingSegmentStore();
    segmentStore = parkingStore;
    startServer();
    var session = startStreamSession();
    var sessionId = session.getSessionId();
    var name = store(session, 0);
    parkingStore.parkNextOpenedSegment();

    try (var request =
        BoundedTask.start(() -> coordinator.deliver(sessionId, firstVariant(session), name))) {
      parkingStore.awaitParkedOpen();
      service.destroySession(sessionId);
      parkingStore.release();

      assertThat(request.await(BOUND)).isEqualTo(new SegmentDelivery.SessionEnded());
    }

    assertThat(unservedSegments("session_ended")).isEqualTo(1);
    assertThat(parkingStore.openSegmentCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should count no unserved segment when a request is answered with a segment while its stream"
          + " session's segments are about to be deleted")
  void shouldCountNoUnservedSegmentWhenARequestIsAnsweredWhileItsSessionsSegmentsAreAboutToGo()
      throws Exception {
    var parkingStore = new DeletionParkingSegmentStore();
    segmentStore = parkingStore;
    startServer();
    var session = startStreamSession();
    var sessionId = session.getSessionId();
    var name = store(session, 0);

    try (var destroy = BoundedTask.start(() -> service.destroySession(sessionId))) {
      parkingStore.awaitParkedDeletion();
      var delivery = coordinator.deliver(sessionId, firstVariant(session), name);
      parkingStore.release();
      destroy.await(BOUND);

      assertThat(StoredSegments.bytes(delivery)).containsExactly(1);
    }

    assertThat(unservedSegments("session_ended")).isZero();
  }

  private double unservedSegments(String cause) {
    return meters.counter("streamarr.streaming.unserved_segments", "cause", cause).count();
  }

  // An encode with a ladder, whose first-listed variant's job attempt runs.
  private StreamSession startStreamSession() {
    var command =
        createStreamSessionCommandBuilder(seedMediaFile().getId(), UUID.randomUUID()).build();
    return accepted(service.createSession(command));
  }

  private MediaFile seedMediaFile() {
    return mediaFiles.save(
        MediaFile.builder()
            .filepathUri("file:///media/movies/test.mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .build());
  }

  private static String firstVariant(StreamSession session) {
    return session.getVariants().getFirst().label();
  }

  // The name under which the segment store keeps the first-listed variant's media segment.
  private static String storedName(StreamSession session, int segmentIndex) {
    return SegmentNames.storedSegmentName(
        firstVariant(session), SegmentNames.mediaSegmentName(segmentIndex));
  }

  // Stores the media segment and announces it, as a worker's completed upload does.
  private String store(StreamSession session, int segmentIndex) {
    var name = storeWithoutAnnouncing(session, segmentIndex);
    announceStored(session, name);
    return name;
  }

  private String storeWithoutAnnouncing(StreamSession session, int segmentIndex) {
    var name = storedName(session, segmentIndex);
    segmentStore.addSegment(session.getSessionId(), name, new byte[] {1});
    return name;
  }

  private void announceStored(StreamSession session, String storedSegmentName) {
    unservedSegments.onMediaSegmentStored(
        new MediaSegmentStoredEvent(session.getSessionId(), storedSegmentName));
  }

  // Answers a viewer's request for the media segment and reads the answer to its end.
  private void serve(StreamSession session, int segmentIndex) {
    var delivery =
        coordinator.deliver(
            session.getSessionId(), firstVariant(session), storedName(session, segmentIndex));
    assertThat(StoredSegments.bytes(delivery)).containsExactly(1);
  }

  private SessionReaper reaperOfANearlyFullSegmentFolder() {
    return SessionReaper.builder()
        .streamingService(service)
        .properties(properties)
        .producerLifecycle(lifecycle)
        .playbackSessionService(playbackServiceBuilder(service).clock(clock).build())
        .serverSettings(defaultServerSettings())
        .segmentFolderSpace(() -> 0.05)
        .meterRegistry(meters)
        .containedFailures(new ContainedFailureMetrics(meters))
        .clock(clock)
        .build();
  }

  private static void awaitWithinBound(CountDownLatch latch, String waitedFor) {
    try {
      if (!latch.await(BOUND.toSeconds(), TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for " + waitedFor);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for " + waitedFor);
    }
  }

  // Stores a media segment of the job attempt's variant and announces it, as the upload of a
  // worker that started the job attempt does, then fails the start.
  private final class StoringThenFailingExecutor extends FakeTranscodeExecutor {

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      var name =
          SegmentNames.storedSegmentName(request.variantLabel(), SegmentNames.mediaSegmentName(0));
      segmentStore.addSegment(request.sessionId(), name, new byte[] {1});
      unservedSegments.onMediaSegmentStored(new MediaSegmentStoredEvent(request.sessionId(), name));
      throw new TranscodeException("The worker disconnected during the start");
    }
  }

  // Parks the next request that opens a segment after the open, before the server answers.
  private static final class OpenParkingSegmentStore extends FakeSegmentStore {

    private final AtomicBoolean parkingNextOpenedSegment = new AtomicBoolean();
    private final CountDownLatch parked = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
      var opened = super.openSegment(sessionId, segmentName);
      if (opened.isPresent() && parkingNextOpenedSegment.compareAndSet(true, false)) {
        parked.countDown();
        awaitWithinBound(released, "the release of the parked request");
      }

      return opened;
    }

    private void parkNextOpenedSegment() {
      parkingNextOpenedSegment.set(true);
    }

    private void awaitParkedOpen() {
      awaitWithinBound(parked, "a request to open the segment");
    }

    private void release() {
      released.countDown();
    }
  }

  // Parks a stream session's deletion before it deletes any segment.
  private static final class DeletionParkingSegmentStore extends FakeSegmentStore {

    private final CountDownLatch parked = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public void deleteSession(UUID sessionId) {
      parked.countDown();
      awaitWithinBound(released, "the release of the parked deletion");
      super.deleteSession(sessionId);
    }

    private void awaitParkedDeletion() {
      awaitWithinBound(parked, "the deletion of the stream session's segments");
    }

    private void release() {
      released.countDown();
    }
  }
}
