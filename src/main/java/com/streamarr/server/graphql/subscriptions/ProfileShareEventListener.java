package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Passes each committed share change to every profileShareChanged operation. It reads nothing: each
 * subscriber decides on its delivery thread whether it may read the share, then reads what it may,
 * after the writer has released its database connection. A write inside a transaction is delivered
 * only after commit; a write without one is delivered at once.
 */
@Component
@RequiredArgsConstructor
public class ProfileShareEventListener {

  private final SubscriptionBroadcaster<ProfileShareChangedEvent> profileShareChangeBroadcaster;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onProfileShareChanged(ProfileShareChangedEvent event) {
    profileShareChangeBroadcaster.broadcast(event);
  }
}
