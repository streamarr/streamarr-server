package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.MembershipVersionChange;
import com.streamarr.server.repositories.auth.CounterChangePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CounterChangeEventPublisher implements CounterChangePublisher {

  private final ApplicationEventPublisher eventPublisher;

  @Override
  public void publishMembership(MembershipVersionChange versionChange) {
    eventPublisher.publishEvent(
        CounterBumpedEvent.membership(
            versionChange.accountId(), versionChange.householdId(), versionChange.version()));
  }
}
