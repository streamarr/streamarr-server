package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.streaming.MediaTimeRange;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.streaming.ActiveStream;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.services.streaming.events.PlaybackSessionSegmentStoredEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
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
 * <p>A stored media segment changes nothing but the stream's produced range, so it reads the stream
 * only when that range differs from the one that the last broadcast carried, and checks the range
 * at most once every {@link #PRODUCED_RANGE_CHECK_INTERVAL} on {@code taskScheduler}. Segments
 * stored while a check waits join that check, so an encode that runs ahead of the player cannot
 * flood subscribers.
 *
 * <p>Publication never fails the playback that triggered it: a failure is counted on {@value
 * LibraryEventListener#PUBLICATION_FAILURES_METRIC} instead, and subscribers recover current state
 * through the activeStreams query.
 */
@Slf4j
@Component
@Builder
@RequiredArgsConstructor
public class ActiveStreamEventListener {

  static final Duration PRODUCED_RANGE_CHECK_INTERVAL = Duration.ofSeconds(5);

  private final ActiveStreamService activeStreams;
  private final ActiveStreamBroadcaster broadcaster;
  private final MeterRegistry meterRegistry;
  private final Scheduler publicationScheduler;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, Publication> publications = new ConcurrentHashMap<>();
  // The produced range that the last broadcast of each stream carried; absent when it carried none.
  private final ConcurrentHashMap<UUID, MediaTimeRange> deliveredRanges = new ConcurrentHashMap<>();
  // When the last produced-range check of each stream started; the next starts an interval later.
  private final ConcurrentHashMap<UUID, Instant> rangeChecksStartedAt = new ConcurrentHashMap<>();
  private final Set<UUID> scheduledRangeChecks = ConcurrentHashMap.newKeySet();

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
    read(event.playbackSessionId());
  }

  @EventListener
  public void onPlaybackSessionSegmentStored(PlaybackSessionSegmentStoredEvent event) {
    var playbackSessionId = event.playbackSessionId();
    if (!broadcaster.getChanges().hasRegistrations()
        || !scheduledRangeChecks.add(playbackSessionId)) {
      return;
    }

    var lastCheckStartedAt = rangeChecksStartedAt.getOrDefault(playbackSessionId, Instant.EPOCH);
    try {
      taskScheduler.schedule(
          () -> checkProducedRange(playbackSessionId),
          lastCheckStartedAt.plus(PRODUCED_RANGE_CHECK_INTERVAL));
    } catch (RuntimeException e) {
      scheduledRangeChecks.remove(playbackSessionId);
      countFailure(e);
    }
  }

  // The check leaves the scheduled set only after it stamps its start, so a segment stored from
  // then on schedules the next check an interval later, and one stored before joins this check,
  // whose read follows it.
  private void checkProducedRange(UUID playbackSessionId) {
    rangeChecksStartedAt.put(playbackSessionId, clock.instant());
    scheduledRangeChecks.remove(playbackSessionId);
    try {
      var range = activeStreams.producedRange(playbackSessionId);
      var delivered = Optional.ofNullable(deliveredRanges.get(playbackSessionId));
      if (range.isEmpty() && delivered.isEmpty()) {
        // No broadcast waits on this stream's range, which also keeps an ended stream's entry from
        // outliving it.
        rangeChecksStartedAt.remove(playbackSessionId);
        return;
      }

      if (!range.equals(delivered)) {
        read(playbackSessionId);
      }
    } catch (RuntimeException e) {
      countFailure(e);
    }
  }

  private void read(UUID playbackSessionId) {
    // Only a newly started reader maps to READING here; a reader in flight maps to READ_AGAIN.
    var publication = publications.compute(playbackSessionId, (_, current) -> onChange(current));
    if (publication == null) {
      // Nobody subscribes, so no subscriber holds a produced range to compare with.
      forgetProducedRange(playbackSessionId);
      return;
    }

    if (publication == Publication.READ_AGAIN) {
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
      var stream = activeStreams.activeStream(playbackSessionId);
      changes.broadcast(
          new ActiveStreamDto.ChangeDto(
              playbackSessionId.toString(), stream.map(ActiveStreamDto::from).orElse(null)));
      stream.ifPresentOrElse(
          delivered -> rememberDeliveredRange(playbackSessionId, delivered),
          () -> forgetProducedRange(playbackSessionId));
    } catch (RuntimeException e) {
      countFailure(e);
    }
  }

  private void rememberDeliveredRange(UUID playbackSessionId, ActiveStream delivered) {
    delivered
        .producedRange()
        .ifPresentOrElse(
            range -> deliveredRanges.put(playbackSessionId, range),
            () -> deliveredRanges.remove(playbackSessionId));
  }

  private void forgetProducedRange(UUID playbackSessionId) {
    deliveredRanges.remove(playbackSessionId);
    rangeChecksStartedAt.remove(playbackSessionId);
  }

  private void countFailure(RuntimeException failure) {
    var field = broadcaster.getChanges().field();
    meterRegistry
        .counter(LibraryEventListener.PUBLICATION_FAILURES_METRIC, "field", field)
        .increment();
    log.warn("Could not publish a {} subscription payload.", field, failure);
  }
}
