package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.util.ArrayDeque;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Shutdown Hook Tests")
class StreamingShutdownHookTest {

  @Test
  @DisplayName("Should finish child cleanup when closing logical playback throws")
  void shouldFinishChildCleanupWhenClosingLogicalPlaybackThrows() {
    var streams = new FakeCreatingStreamingService();
    var playback = playbackServiceBuilder(streams).build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    streams.addSession(StreamSessionFixture.buildActiveSession());
    streams.failNextDestroy(initial.currentFormatAttempt().streamSessionId());
    var hook = new StreamingShutdownHook(streams, playback);

    assertThatNoException().isThrownBy(hook::onShutdown);

    assertThat(playback.access(playbackRequest(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard queued replacement when shutdown finds no child stream")
  void shouldDiscardQueuedReplacementWhenShutdownFindsNoChildStream() {
    var streams = new FakeCreatingStreamingService();
    var work = new ArrayDeque<Runnable>();
    var playback = playbackServiceBuilder(streams).executor(work::add).build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    var childId = initial.currentFormatAttempt().streamSessionId();
    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(childId, UUID.randomUUID(), "default"));
    streams.destroySession(childId);
    assertThat(
            playback.access(playbackRequest(initial, command)).orElseThrow().currentFormatAttempt())
        .isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(streams.getAllSessions()).isEmpty();

    new StreamingShutdownHook(streams, playback).onShutdown();
    work.forEach(Runnable::run);

    assertThat(playback.access(playbackRequest(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should destroy all active sessions when shutdown hook fires")
  void shouldDestroyAllActiveSessionsWhenShutdownHookFires() {
    var service = new FakeStreamingService();
    var session1 = StreamSessionFixture.buildActiveSession();
    var session2 = StreamSessionFixture.buildActiveSession();
    service.addSession(session1);
    service.addSession(session2);

    var hook = new StreamingShutdownHook(service, playbackServiceBuilder(service).build());
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds())
        .containsExactlyInAnyOrder(session1.getSessionId(), session2.getSessionId());
  }

  @Test
  @DisplayName("Should not throw when no active sessions exist during shutdown")
  void shouldNotThrowWhenNoActiveSessionsExistDuringShutdown() {
    var service = new FakeStreamingService();

    var hook = new StreamingShutdownHook(service, playbackServiceBuilder(service).build());
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds()).isEmpty();
  }
}
