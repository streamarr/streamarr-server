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

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Active Stream Event Listener Tests")
class ActiveStreamEventListenerTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final FakeCreatingStreamingService streams = new FakeCreatingStreamingService();
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
            .transcodeExecutor(new FakeTranscodeExecutor())
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
