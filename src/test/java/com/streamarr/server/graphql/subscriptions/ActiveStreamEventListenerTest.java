package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.graphql.dto.MediaTimeRangeDto;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.services.streaming.events.ProducedRangeMayHaveMovedEvent;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.util.unit.DataSize;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Active Stream Event Listener Tests")
class ActiveStreamEventListenerTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final FakeRuntimeStreamSessionRegistry registry = new FakeRuntimeStreamSessionRegistry();
  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService(registry);
  private final FakeTranscodeExecutor executor = new FakeTranscodeExecutor();
  private final ControllableSegmentStore segments = new ControllableSegmentStore();
  private final MutableClock clock = new MutableClock();
  private final RejectingTaskScheduler taskScheduler = new RejectingTaskScheduler();
  private final ProducerLifecycleService producerLifecycle =
      StreamingRigFixture.streamingRigBuilder()
          .transcodeExecutor(executor)
          .segmentStore(segments)
          .properties(StreamingProperties.builder().build())
          .runtimeRegistry(registry)
          .build()
          .lifecycle();
  private final PlaybackSessionService playbackSessions = playbackServiceBuilder(streams).build();
  private final ActiveStreamBroadcaster broadcaster =
      new ActiveStreamBroadcaster(
          meterRegistry,
          Schedulers.immediate(),
          new SubscriptionProperties(64, 32, DataSize.ofMegabytes(1), Duration.ofSeconds(10)));

  @Test
  @DisplayName(
      "Should broadcast the stream while its playback session is open and only its id once it"
          + " ends")
  void shouldBroadcastTheStreamWhileItsPlaybackSessionIsOpenAndOnlyItsIdOnceItEnds() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var command = startPlaybackBuilder().build();
    var playback = acceptedPlayback(playbackSessions.start(command));
    var event = new PlaybackSessionChangedEvent(playback.playbackSessionId());

    listener.onPlaybackSessionChanged(event);
    playbackSessions.close(playbackRequest(playback, command));
    listener.onPlaybackSessionChanged(event);

    var playbackId = playback.playbackSessionId().toString();
    assertThat(delivered).hasSize(2);
    assertThat(delivered.getFirst().playbackSessionId()).isEqualTo(playbackId);
    assertThat(delivered.getFirst().activeStream().playbackSessionId()).isEqualTo(playbackId);
    assertThat(delivered.getLast()).isEqualTo(new ActiveStreamDto.ChangeDto(playbackId, null));
  }

  @Test
  @DisplayName("Should count a publication failure without throwing when the stream cannot be read")
  void shouldCountAPublicationFailureWithoutThrowingWhenTheStreamCannotBeRead() {
    var listener = listenerReading(new UnavailableMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    assertThatCode(
            () ->
                listener.onPlaybackSessionChanged(
                    new PlaybackSessionChangedEvent(playback.playbackSessionId())))
        .doesNotThrowAnyException();

    assertThat(delivered).isEmpty();
    assertThat(publicationFailures()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should read no stream when nobody subscribes")
  void shouldReadNoStreamWhenNobodySubscribes() {
    var listener = listenerReading(new UnavailableMediaFileRepository());
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    listener.onPlaybackSessionChanged(
        new PlaybackSessionChangedEvent(playback.playbackSessionId()));

    assertThat(publicationFailures()).isZero();
  }

  @Test
  @DisplayName(
      "Should return before the stream is read and broadcast the latest state last when the stream"
          + " changes during a read")
  void shouldReturnBeforeTheStreamIsReadAndBroadcastTheLatestStateLastWhenItChangesDuringARead()
      throws Exception {
    var mediaFiles = new ParkingMediaFileRepository();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var listener = listenerReading(mediaFiles, Schedulers.fromExecutorService(executor));
      var delivered = new CopyOnWriteArrayList<ActiveStreamDto.ChangeDto>();
      var ended = new CountDownLatch(1);
      broadcaster
          .getChanges()
          .register()
          .subscribe(
              change -> {
                delivered.add(change);
                if (change.activeStream() == null) {
                  ended.countDown();
                }
              });
      var command = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(command));
      var event = new PlaybackSessionChangedEvent(playback.playbackSessionId());

      BoundedTask.runWithin(BOUND, () -> listener.onPlaybackSessionChanged(event));
      awaitLatch(mediaFiles.reading);
      playbackSessions.close(playbackRequest(playback, command));
      BoundedTask.runWithin(BOUND, () -> listener.onPlaybackSessionChanged(event));
      mediaFiles.release.countDown();

      awaitLatch(ended);

      var playbackId = playback.playbackSessionId().toString();
      assertThat(delivered).hasSize(2);
      assertThat(delivered.getFirst().activeStream().playbackSessionId()).isEqualTo(playbackId);
      assertThat(delivered.getLast()).isEqualTo(new ActiveStreamDto.ChangeDto(playbackId, null));
    }
  }

  @Test
  @DisplayName(
      "Should broadcast the stream's end to a new subscriber when the last subscriber left while"
          + " a read was in flight")
  void shouldBroadcastTheStreamsEndToANewSubscriberWhenTheLastSubscriberLeftDuringARead()
      throws Exception {
    var mediaFiles = new ParkingMediaFileRepository();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var listener = listenerReading(mediaFiles, Schedulers.fromExecutorService(executor));
      var leaving = broadcaster.getChanges().register().subscribe();
      var command = startPlaybackBuilder().build();
      var playback = acceptedPlayback(playbackSessions.start(command));
      var event = new PlaybackSessionChangedEvent(playback.playbackSessionId());

      BoundedTask.runWithin(BOUND, () -> listener.onPlaybackSessionChanged(event));
      awaitLatch(mediaFiles.reading);
      leaving.dispose();
      playbackSessions.close(playbackRequest(playback, command));
      listener.onPlaybackSessionChanged(event);
      var ended = new CountDownLatch(1);
      var delivered = new CopyOnWriteArrayList<ActiveStreamDto.ChangeDto>();
      broadcaster
          .getChanges()
          .register()
          .subscribe(
              change -> {
                delivered.add(change);
                if (change.activeStream() == null) {
                  ended.countDown();
                }
              });
      mediaFiles.release.countDown();
      awaitLatch(ended);

      assertThat(delivered.getLast())
          .isEqualTo(new ActiveStreamDto.ChangeDto(playback.playbackSessionId().toString(), null));
    }
  }

  @Test
  @DisplayName("Should count a publication failure without throwing when no thread can read")
  void shouldCountAPublicationFailureWithoutThrowingWhenNoThreadCanRead() {
    var scheduler = Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    scheduler.dispose();
    var listener = listenerReading(new FakeMediaFileRepository(), scheduler);
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var event = new PlaybackSessionChangedEvent(playback.playbackSessionId());

    assertThatCode(() -> listener.onPlaybackSessionChanged(event)).doesNotThrowAnyException();
    assertThatCode(() -> listener.onPlaybackSessionChanged(event)).doesNotThrowAnyException();

    assertThat(delivered).isEmpty();
    assertThat(publicationFailures()).isEqualTo(2);
  }

  // The default probe's keyframes open every 6 s slot, so media segment N covers [6N, 6N + 6) s.
  @Test
  @DisplayName(
      "Should deliver the stream at once when a stored segment moves its produced range and no"
          + " range change fired in the last 5 seconds")
  void shouldDeliverTheStreamAtOnceWhenAStoredSegmentMovesItsProducedRange() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    storeSegment(listener, playback, 0);
    runTasksAt(clock.instant());

    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 6));
  }

  @Test
  @DisplayName(
      "Should coalesce the stored segments of 5 seconds into one delivery of the latest produced"
          + " range when the range changed less than 5 seconds ago")
  void shouldCoalesceTheStoredSegmentsOf5SecondsIntoOneDeliveryOfTheLatestProducedRange() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var firstFiredAt = clock.instant();
    storeSegment(listener, playback, 0);
    runTasksAt(firstFiredAt);

    clock.advance(Duration.ofSeconds(1));
    storeSegment(listener, playback, 1);
    clock.advance(Duration.ofSeconds(1));
    storeSegment(listener, playback, 2);
    var waiting = taskScheduler.pendingTaskCount();
    runTasksAt(firstFiredAt.plus(Duration.ofMillis(4_999)));
    var withinTheInterval = delivered.size();
    runTasksAt(firstFiredAt.plus(Duration.ofSeconds(5)));

    assertThat(waiting).isEqualTo(1);
    assertThat(withinTheInterval).isEqualTo(1);
    assertThat(delivered)
        .extracting(this::producedRangeOf)
        .containsExactly(rangeOf(0, 6), rangeOf(0, 18));
  }

  @Test
  @DisplayName(
      "Should deliver nothing for a stored segment when the last delivered change already carries"
          + " the produced range")
  void shouldDeliverNothingForAStoredSegmentWhenTheLastDeliveredChangeCarriesTheProducedRange() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    store(playback, 0);
    listener.onPlaybackSessionChanged(
        new PlaybackSessionChangedEvent(playback.playbackSessionId()));

    storeSegment(listener, playback, 5);
    runTasksAt(clock.instant().plus(Duration.ofSeconds(5)));

    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 6));
  }

  @Test
  @DisplayName(
      "Should hold a waiting range check until 5 seconds after a change that moved the produced"
          + " range")
  void shouldHoldAWaitingRangeCheckUntil5SecondsAfterAChangeThatMovedTheProducedRange() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    storeSegment(listener, playback, 0);
    listener.onPlaybackSessionChanged(
        new PlaybackSessionChangedEvent(playback.playbackSessionId()));
    var changedAt = clock.instant();

    storeSegment(listener, playback, 1);
    runTasksAt(changedAt.plus(Duration.ofMillis(4_999)));
    var withinTheInterval = delivered.size();
    runTasksAt(changedAt.plus(Duration.ofSeconds(5)));

    assertThat(withinTheInterval).isEqualTo(1);
    assertThat(delivered)
        .extracting(this::producedRangeOf)
        .containsExactly(rangeOf(0, 6), rangeOf(0, 12));
  }

  @Test
  @DisplayName(
      "Should deliver nothing for a stored segment when no produced segment holds the stream's"
          + " position")
  void shouldDeliverNothingForAStoredSegmentWhenNoProducedSegmentHoldsTheStreamsPosition() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    storeSegment(listener, playback, 5);
    runTasksAt(clock.instant());

    assertThat(delivered).isEmpty();
    assertThat(publicationFailures()).isZero();
  }

  @Test
  @DisplayName(
      "Should count a publication failure without throwing when the produced range cannot be read")
  void shouldCountAPublicationFailureWithoutThrowingWhenTheProducedRangeCannotBeRead() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    storeSegment(listener, playback, 0);
    segments.failLookups();

    assertThatCode(() -> runTasksAt(clock.instant())).doesNotThrowAnyException();

    assertThat(delivered).isEmpty();
    assertThat(publicationFailures()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count a publication failure without throwing and check again on the next segment"
          + " when no thread can check the produced range")
  void shouldCountAPublicationFailureAndCheckAgainOnTheNextSegmentWhenNoThreadCanCheck() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    taskScheduler.rejectNextTask();

    assertThatCode(() -> storeSegment(listener, playback, 0)).doesNotThrowAnyException();
    storeSegment(listener, playback, 1);
    runTasksAt(clock.instant());

    assertThat(publicationFailures()).isEqualTo(1);
    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 12));
  }

  @Test
  @DisplayName(
      "Should check the produced range again an interval later instead of reading again when a"
          + " read of the stream is in flight")
  void shouldCheckTheProducedRangeAgainLaterInsteadOfReadingAgainWhenAReadIsInFlight()
      throws Exception {
    var mediaFiles = new ParkingMediaFileRepository();
    var delivered = new CopyOnWriteArrayList<ActiveStreamDto.ChangeDto>();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    long waitingAfterTheCheck;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var listener = listenerReading(mediaFiles, Schedulers.fromExecutorService(executor));
      broadcaster.getChanges().register().subscribe(delivered::add);
      store(playback, 0);
      BoundedTask.runWithin(
          BOUND,
          () ->
              listener.onPlaybackSessionChanged(
                  new PlaybackSessionChangedEvent(playback.playbackSessionId())));
      awaitLatch(mediaFiles.reading);

      storeSegment(listener, playback, 1);
      runTasksAt(clock.instant());
      waitingAfterTheCheck = taskScheduler.pendingTaskCount();
      mediaFiles.release.countDown();
    }

    assertThat(waitingAfterTheCheck).isEqualTo(1);
    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 12));
  }

  @Test
  @DisplayName(
      "Should keep a stream's produced range check when its last subscriber leaves while a read is"
          + " in flight")
  void shouldKeepAStreamsProducedRangeCheckWhenItsLastSubscriberLeavesWhileAReadIsInFlight()
      throws Exception {
    var mediaFiles = new ParkingMediaFileRepository();
    var delivered = new CopyOnWriteArrayList<ActiveStreamDto.ChangeDto>();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    long waitingWithoutSubscribers;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var listener = listenerReading(mediaFiles, Schedulers.fromExecutorService(executor));
      var leaving = broadcaster.getChanges().register().subscribe();
      store(playback, 0);
      BoundedTask.runWithin(
          BOUND,
          () ->
              listener.onPlaybackSessionChanged(
                  new PlaybackSessionChangedEvent(playback.playbackSessionId())));
      awaitLatch(mediaFiles.reading);
      leaving.dispose();

      storeSegment(listener, playback, 1);
      waitingWithoutSubscribers = taskScheduler.pendingTaskCount();
      broadcaster.getChanges().register().subscribe(delivered::add);
      mediaFiles.release.countDown();
    }

    assertThat(waitingWithoutSubscribers).isEqualTo(1);
    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 12));
  }

  // The first check parks after it found segment 1 missing, so it holds the range [0, 6) while
  // segment 1 is stored and a second check could run beside it.
  @Test
  @DisplayName(
      "Should deliver one change in 5 seconds when a segment is stored while a produced range check"
          + " reads the range")
  void shouldDeliverOneChangeIn5SecondsWhenASegmentIsStoredWhileARangeCheckReadsTheRange()
      throws Exception {
    var listener = listenerReading(new FakeMediaFileRepository());
    var delivered = subscribe();
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));
    var checkedAt = clock.instant();
    storeSegment(listener, playback, 0);
    var lookup = segments.parkAfterLookupOf(SegmentNames.mediaSegmentName(1));

    try (var firstCheck = BoundedTask.start(() -> taskScheduler.runTasksDueAt(checkedAt))) {
      awaitLatch(lookup.parked());
      storeSegment(listener, playback, 1);
      taskScheduler.runTasksDueAt(checkedAt);
      lookup.release().countDown();
      firstCheck.await(BOUND);
    }

    var withinTheInterval = List.copyOf(delivered);
    runTasksAt(checkedAt.plus(Duration.ofSeconds(5)));

    assertThat(withinTheInterval).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 12));
    assertThat(delivered).extracting(this::producedRangeOf).containsExactly(rangeOf(0, 12));
  }

  @Test
  @DisplayName("Should wait for no produced range change when nobody subscribes")
  void shouldWaitForNoProducedRangeChangeWhenNobodySubscribes() {
    var listener = listenerReading(new FakeMediaFileRepository());
    var playback = acceptedPlayback(playbackSessions.start(startPlaybackBuilder().build()));

    storeSegment(listener, playback, 0);

    assertThat(taskScheduler.pendingTaskCount()).isZero();
  }

  // The task scheduler and the listener's clock share one time.
  private void runTasksAt(Instant instant) {
    clock.advance(Duration.between(clock.instant(), instant));
    taskScheduler.runTasksDueAt(instant);
  }

  private void storeSegment(
      ActiveStreamEventListener listener, PlaybackSessionSnapshot playback, int index) {
    store(playback, index);
    listener.onProducedRangeMayHaveMoved(
        new ProducedRangeMayHaveMovedEvent(playback.playbackSessionId()));
  }

  private void store(PlaybackSessionSnapshot playback, int index) {
    segments.storeSegment(
        playback.currentFormatAttempt().streamSessionId(),
        SegmentNames.mediaSegmentName(index),
        new byte[] {1});
  }

  private MediaTimeRangeDto producedRangeOf(ActiveStreamDto.ChangeDto change) {
    return change.activeStream().producedRange();
  }

  private static MediaTimeRangeDto rangeOf(double startSeconds, double endSeconds) {
    return new MediaTimeRangeDto(startSeconds, endSeconds);
  }

  private ActiveStreamEventListener listenerReading(FakeMediaFileRepository mediaFiles) {
    return listenerReading(mediaFiles, Schedulers.immediate());
  }

  private ActiveStreamEventListener listenerReading(
      FakeMediaFileRepository mediaFiles, Scheduler publicationScheduler) {
    var service =
        ActiveStreamService.builder()
            .authorizationService(
                new FakeAuthorizationService(
                    identityFor(defaultPlaybackAuthorityBuilder().build())))
            .playbackSessions(playbackSessions)
            .transcodeExecutor(executor)
            .producerLifecycle(producerLifecycle)
            .profileRepository(new FakeProfileRepository())
            .authSessionRepository(new FakeAuthSessionRepository())
            .mediaFileRepository(mediaFiles)
            .movieRepository(new FakeMovieRepository())
            .episodeRepository(new FakeEpisodeRepository())
            .build();
    return ActiveStreamEventListener.builder()
        .activeStreams(service)
        .broadcaster(broadcaster)
        .meterRegistry(meterRegistry)
        .publicationScheduler(publicationScheduler)
        .taskScheduler(taskScheduler)
        .clock(clock)
        .build();
  }

  private List<ActiveStreamDto.ChangeDto> subscribe() {
    var delivered = new CopyOnWriteArrayList<ActiveStreamDto.ChangeDto>();
    broadcaster.getChanges().register().subscribe(delivered::add);
    return delivered;
  }

  private double publicationFailures() {
    var counter =
        meterRegistry
            .find(LibraryEventListener.PUBLICATION_FAILURES_METRIC)
            .tag("field", "activeStreamsChanged")
            .counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  private static final class ControllableSegmentStore extends FakeSegmentStore {

    private volatile boolean failing;

    private volatile ParkedLookup parking;

    record ParkedLookup(String segmentName, CountDownLatch parked, CountDownLatch release) {}

    void failLookups() {
      failing = true;
    }

    // The next lookup of the segment parks once it has its answer, until the test releases it.
    ParkedLookup parkAfterLookupOf(String segmentName) {
      parking = new ParkedLookup(segmentName, new CountDownLatch(1), new CountDownLatch(1));
      return parking;
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      if (failing) {
        throw new IllegalStateException("The segment store is unavailable.");
      }

      var exists = super.segmentExists(sessionId, segmentName);
      var lookup = parking;
      if (lookup != null && segmentName.equals(lookup.segmentName())) {
        parking = null;
        lookup.parked().countDown();
        awaitLatch(lookup.release());
      }

      return exists;
    }
  }

  private static final class RejectingTaskScheduler extends FakeTaskScheduler {

    private boolean rejectNext;

    void rejectNextTask() {
      rejectNext = true;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      if (rejectNext) {
        rejectNext = false;
        throw new TaskRejectedException("The task scheduler is shut down.");
      }

      return super.schedule(task, startTime);
    }
  }

  // The first media file read parks until the test releases it.
  private static final class ParkingMediaFileRepository extends FakeMediaFileRepository {

    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public List<MediaFile> findAllById(Iterable<UUID> mediaFileIds) {
      if (reading.getCount() > 0) {
        reading.countDown();
        awaitLatch(release);
      }

      return super.findAllById(mediaFileIds);
    }
  }

  private static final class UnavailableMediaFileRepository extends FakeMediaFileRepository {

    @Override
    public List<MediaFile> findAllById(Iterable<UUID> mediaFileIds) {
      throw new IllegalStateException("The media files are unavailable.");
    }
  }
}
