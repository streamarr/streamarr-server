package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns playback session changes into activeStreamsChanged payloads. Each change reads the stream
 * once, for every subscriber, and not at all while nobody subscribes. Publication never fails the
 * playback that triggered it: a failure is counted on {@value
 * LibraryEventListener#PUBLICATION_FAILURES_METRIC} instead, and subscribers recover current state
 * through the activeStreams query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveStreamEventListener {

  private final ActiveStreamService activeStreams;
  private final ActiveStreamBroadcaster broadcaster;
  private final MeterRegistry meterRegistry;
  private final MutexFactory<UUID> publicationLocks = new MutexFactory<>();

  // Changes of one playback session can come from several threads at once. Reading and
  // broadcasting under the playback session's lock keeps broadcasts in read order, so the last
  // broadcast carries the latest state.
  @EventListener
  public void onPlaybackSessionChanged(PlaybackSessionChangedEvent event) {
    var changes = broadcaster.getChanges();
    if (!changes.hasRegistrations()) {
      return;
    }

    var lock = publicationLocks.getMutex(event.playbackSessionId());
    lock.lock();
    try {
      var stream = activeStreams.activeStream(event.playbackSessionId()).map(ActiveStreamDto::from);
      changes.broadcast(
          new ActiveStreamDto.ChangeDto(event.playbackSessionId().toString(), stream.orElse(null)));
    } catch (RuntimeException e) {
      meterRegistry
          .counter(LibraryEventListener.PUBLICATION_FAILURES_METRIC, "field", changes.field())
          .increment();
      log.warn("Could not publish a {} subscription payload.", changes.field(), e);
    } finally {
      lock.unlock();
    }
  }
}
