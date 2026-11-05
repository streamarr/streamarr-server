package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.streaming.MediaTimeRange;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.services.streaming.ActiveStream;
import com.streamarr.server.services.streaming.ActiveStreamService;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.services.streaming.events.ProducedRangeMayHaveMovedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>A stored media segment or a switch of the requested variant changes nothing but the stream's
 * produced range, so it reads the stream only when that range differs from the one that the last
 * broadcast carried. That check runs on {@code taskScheduler} no sooner than {@link
 * #PRODUCED_RANGE_INTERVAL} after a broadcast last moved the range, and never while a read of the
 * stream is in flight. One check runs at a time per stream, and segments stored while it waits join
 * it, so an encode that runs ahead of the player cannot flood subscribers.
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

  static final Duration PRODUCED_RANGE_INTERVAL = Duration.ofSeconds(5);

  private final ActiveStreamService activeStreams;
  private final ActiveStreamBroadcaster broadcaster;
  private final MeterRegistry meterRegistry;
  private final Scheduler publicationScheduler;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, Publication> publications = new ConcurrentHashMap<>();
  // The produced range that the last broadcast of each stream carried; absent when it carried none.
  private final ConcurrentHashMap<UUID, MediaTimeRange> deliveredRanges = new ConcurrentHashMap<>();
  // When a broadcast of each stream last carried another produced range than the broadcast before.
  private final ConcurrentHashMap<UUID, Instant> rangeBroadcastsAt = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, RangeCheck> rangeChecks = new ConcurrentHashMap<>();

  // One reader at a time per playback session, so broadcasts follow read order. A change that
  // arrives during a read asks for one more read, so the last broadcast carries the latest state,
  // even while nobody subscribes: the read in flight may still broadcast to a subscriber that
  // registers later. Only a new read waits for a subscriber, checked in the same map update that
  // a finishing reader's removal contends on.
  private enum Publication {
    READING,
    READ_AGAIN
  }

  // One produced range check at a time per playback session. A segment stored while the check
  // waits joins it; one stored while it reads the range asks for one more check after it.
  private enum RangeCheck {
    WAITING,
    CHECKING,
    CHECK_AGAIN;

    private RangeCheck withStoredSegment() {
      if (this == CHECKING) {
        return CHECK_AGAIN;
      }

      return this;
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onPlaybackSessionChanged(PlaybackSessionChangedEvent event) {
    read(event.playbackSessionId());
  }

  @EventListener
  public void onProducedRangeMayHaveMoved(ProducedRangeMayHaveMovedEvent event) {
    var playbackSessionId = event.playbackSessionId();
    // A read in flight may still broadcast to a subscriber that registers later, so its stream
    // keeps its check while nobody subscribes.
    if (!broadcaster.getChanges().hasRegistrations()
        && !publications.containsKey(playbackSessionId)) {
      return;
    }

    var started = new AtomicBoolean();
    rangeChecks.compute(
        playbackSessionId,
        (_, current) -> {
          if (current == null) {
            started.set(true);
            return RangeCheck.WAITING;
          }

          return current.withStoredSegment();
        });
    if (started.get()) {
      scheduleRangeCheck(playbackSessionId, nextRangeCheckAt(playbackSessionId));
    }
  }

  private Instant nextRangeCheckAt(UUID playbackSessionId) {
    return rangeBroadcastsAt
        .getOrDefault(playbackSessionId, Instant.EPOCH)
        .plus(PRODUCED_RANGE_INTERVAL);
  }

  private void scheduleRangeCheck(UUID playbackSessionId, Instant at) {
    try {
      taskScheduler.schedule(() -> checkProducedRange(playbackSessionId), at);
    } catch (RuntimeException e) {
      rangeChecks.remove(playbackSessionId);
      countFailure(e);
    }
  }

  // The range read follows the move to CHECKING, so it sees every segment that joined the check.
  private void checkProducedRange(UUID playbackSessionId) {
    rangeChecks.replace(playbackSessionId, RangeCheck.WAITING, RangeCheck.CHECKING);
    var nextCheckAt = compareProducedRange(playbackSessionId, clock.instant());
    var next =
        rangeChecks.compute(
            playbackSessionId,
            (_, current) -> {
              if (nextCheckAt.isPresent() || current == RangeCheck.CHECK_AGAIN) {
                return RangeCheck.WAITING;
              }

              return null;
            });
    if (next != null) {
      scheduleRangeCheck(
          playbackSessionId, nextCheckAt.orElseGet(() -> nextRangeCheckAt(playbackSessionId)));
    }
  }

  // Reads the stream when its range moved, or answers when to check again if it may not compare
  // the range yet.
  private Optional<Instant> compareProducedRange(UUID playbackSessionId, Instant now) {
    if (publications.containsKey(playbackSessionId)) {
      // The read in flight may broadcast a new range, which restarts the interval after it.
      return Optional.of(now.plus(PRODUCED_RANGE_INTERVAL));
    }

    var due = nextRangeCheckAt(playbackSessionId);
    if (now.isBefore(due)) {
      return Optional.of(due);
    }

    try {
      var range = activeStreams.producedRange(playbackSessionId);
      if (!range.equals(Optional.ofNullable(deliveredRanges.get(playbackSessionId)))) {
        read(playbackSessionId);
      }
    } catch (RuntimeException e) {
      countFailure(e);
    }

    return Optional.empty();
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

  // Only the stream's one reader writes its delivered range.
  private void rememberDeliveredRange(UUID playbackSessionId, ActiveStream delivered) {
    var range = delivered.producedRange();
    if (!range.equals(Optional.ofNullable(deliveredRanges.get(playbackSessionId)))) {
      rangeBroadcastsAt.put(playbackSessionId, clock.instant());
    }

    range.ifPresentOrElse(
        produced -> deliveredRanges.put(playbackSessionId, produced),
        () -> deliveredRanges.remove(playbackSessionId));
  }

  private void forgetProducedRange(UUID playbackSessionId) {
    deliveredRanges.remove(playbackSessionId);
    rangeBroadcastsAt.remove(playbackSessionId);
  }

  private void countFailure(RuntimeException failure) {
    var field = broadcaster.getChanges().field();
    meterRegistry
        .counter(LibraryEventListener.PUBLICATION_FAILURES_METRIC, "field", field)
        .increment();
    log.warn("Could not publish a {} subscription payload.", field, failure);
  }
}
