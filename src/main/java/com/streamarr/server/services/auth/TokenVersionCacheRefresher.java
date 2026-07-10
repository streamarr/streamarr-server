package com.streamarr.server.services.auth;

import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TokenVersionCacheRefresher {

  private final TokenVersionCache cache;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onCounterBumped(CounterBumpedEvent event) {
    cache.update(event.kind(), event.key(), event.version());
  }
}
