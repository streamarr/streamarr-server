package com.streamarr.server.services.library;

import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemAudience;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes a library's counts to subscribers at most once per interval while the library's items
 * change. The first change after a quiet interval pushes at once, and the changes that commit
 * within an interval push once when the interval ends. Subscribers read the counts when a push
 * reaches them, so each push carries every change that committed before it.
 */
@Component
@RequiredArgsConstructor
public class LibraryCountsChangedPublisher {

  private static final Duration PUSH_INTERVAL = Duration.ofSeconds(2);

  private final ApplicationEventPublisher events;
  private final TaskScheduler scheduler;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, Push> pushes = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onItemChanged(LibraryItemChangedEvent event) {
    if (event instanceof LibraryItemUpdatedEvent updated
        && updated.audience() instanceof LibraryItemAudience.OneProfile) {
      return;
    }

    pushes.compute(event.libraryId(), this::schedulePush);
  }

  private Push schedulePush(UUID libraryId, Push previous) {
    if (previous != null && previous.scheduled()) {
      return previous;
    }

    var pushAt = clock.instant();
    if (previous != null && previous.nextAllowedAt().isAfter(pushAt)) {
      pushAt = previous.nextAllowedAt();
    }

    scheduler.schedule(() -> pushCounts(libraryId), pushAt);
    return new Push(pushAt, true);
  }

  private void pushCounts(UUID libraryId) {
    pushes.put(libraryId, new Push(clock.instant().plus(PUSH_INTERVAL), false));
    events.publishEvent(new LibraryCountsChangedEvent(libraryId));
  }

  private record Push(Instant nextAllowedAt, boolean scheduled) {}
}
