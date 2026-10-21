package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.services.events.watchprogress.WatchStateChangedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Passes each committed watch-state change to the operations of the change's Profile. It reads
 * nothing: each subscriber resolves the change's items on a delivery thread, after the writer has
 * released its database connection. A write inside a transaction is delivered only after commit; a
 * write without one is delivered at once.
 */
@Component
@RequiredArgsConstructor
public class WatchStateEventListener {

  private final KeyedSubscriptionBroadcaster<UUID, WatchStateChange> watchStateChangeBroadcaster;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onWatchStateChanged(WatchStateChangedEvent event) {
    watchStateChangeBroadcaster.broadcast(event.profileId(), new WatchStateChange(event.itemIds()));
  }
}
