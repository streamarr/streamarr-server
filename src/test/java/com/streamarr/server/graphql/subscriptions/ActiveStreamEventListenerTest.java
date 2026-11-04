package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.domain.media.MediaFileTitle;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Active Stream Event Listener Tests")
class ActiveStreamEventListenerTest {

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

  private ActiveStreamEventListener listenerReading(FakeMediaFileRepository mediaFiles) {
    var service =
        new ActiveStreamService(
            new FakeAuthorizationService(identityFor(defaultPlaybackAuthorityBuilder().build())),
            playbackSessions,
            new FakeTranscodeExecutor(),
            new FakeProfileRepository(),
            new FakeAuthSessionRepository(),
            mediaFiles);
    return new ActiveStreamEventListener(service, broadcaster, meterRegistry);
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

  private static final class UnavailableMediaFileRepository extends FakeMediaFileRepository {

    @Override
    public Map<UUID, MediaFileTitle> findTitlesByMediaFileIds(Collection<UUID> mediaFileIds) {
      throw new IllegalStateException("The media file titles are unavailable.");
    }
  }
}
