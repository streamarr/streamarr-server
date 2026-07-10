package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.MembershipVersionChange;
import com.streamarr.server.repositories.auth.CounterChangePublisher;
import com.streamarr.server.repositories.auth.CounterNotificationPayload;
import com.streamarr.server.repositories.auth.CounterNotificationWriter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CounterChangeEventPublisher implements CounterChangePublisher {

  private final ApplicationEventPublisher eventPublisher;
  private final CounterNotificationWriter notificationWriter;

  @Override
  public void publishSession(UUID sessionId, long version) {
    publish(CounterBumpedEvent.session(sessionId, version));
  }

  @Override
  public void publishMembership(MembershipVersionChange versionChange) {
    publish(
        CounterBumpedEvent.membership(
            versionChange.accountId(), versionChange.householdId(), versionChange.version()));
  }

  private void publish(CounterBumpedEvent event) {
    eventPublisher.publishEvent(event);
    notificationWriter.write(
        new CounterNotificationPayload(event.kind(), event.key(), event.version()));
  }
}
