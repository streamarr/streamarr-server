package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.scheduler.Scheduler;

/**
 * Turns playback session changes into activeStreamsChanged payloads. Each change reads the stream
 * once, for every subscriber, and not at all while nobody subscribes. A change inside a transaction
 * is read after commit; a change without one is read at once. The read runs on a thread of {@code
 * publicationScheduler}, so the transaction that published the change neither waits for the read
 * nor holds its connection while the read runs.
 *
 * <p>Publication never fails the playback that triggered it: a failure is counted on {@value
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
  private final Scheduler publicationScheduler;
  private final ConcurrentHashMap<UUID, Publication> publications = new ConcurrentHashMap<>();

  // One reader at a time per playback session, so broadcasts follow read order. A change that
  // arrives during a read asks for one more read, so the last broadcast carries the latest state,
  // even while nobody subscribes: the read in flight may still broadcast to a subscriber that
  // registers later. Only a new read waits for a subscriber, checked in the same map update that
  // a finishing reader's removal contends on.
  private enum Publication {
    READING,
    READ_AGAIN
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onPlaybackSessionChanged(PlaybackSessionChangedEvent event) {
    var playbackSessionId = event.playbackSessionId();
    // Only a newly started reader maps to READING here; a reader in flight maps to READ_AGAIN.
    var publication =
        publications.compute(playbackSessionId, (_, current) -> onChange(current));
    if (publication != Publication.READING) {
      return;
    }

    try {
      publicationScheduler.schedule(() -> publishUntilCurrent(playbackSessionId));
    } catch (RejectedExecutionException e) {
      publications.remove(playbackSessionId);
      countFailure(e);
    }
  }

  private Publication onChange(Publication current) {
    if (current != null) {
      return Publication.READ_AGAIN;
    }

    if (!broadcaster.getChanges().hasRegistrations()) {
      return null;
    }

    return Publication.READING;
  }

  private void publishUntilCurrent(UUID playbackSessionId) {
    do {
      publish(playbackSessionId);
    } while (publications.compute(playbackSessionId, (_, publication) -> afterRead(publication))
        != null);
  }

  // Removing the entry ends the reader; a change that arrived during the read keeps it reading.
  private static Publication afterRead(Publication publication) {
    if (publication == Publication.READ_AGAIN) {
      return Publication.READING;
    }

    return null;
  }

  private void publish(UUID playbackSessionId) {
    var changes = broadcaster.getChanges();
    try {
      var stream = activeStreams.activeStream(playbackSessionId).map(ActiveStreamDto::from);
      changes.broadcast(
          new ActiveStreamDto.ChangeDto(playbackSessionId.toString(), stream.orElse(null)));
    } catch (RuntimeException e) {
      countFailure(e);
    }
  }

  private void countFailure(RuntimeException failure) {
    var field = broadcaster.getChanges().field();
    meterRegistry
        .counter(LibraryEventListener.PUBLICATION_FAILURES_METRIC, "field", field)
        .increment();
    log.warn("Could not publish a {} subscription payload.", field, failure);
  }
}
