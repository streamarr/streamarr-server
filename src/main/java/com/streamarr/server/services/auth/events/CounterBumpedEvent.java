package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.CounterKind;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CounterBumpedEvent {

  CounterKind kind;
  String key;
  long version;

  public static CounterBumpedEvent session(UUID sessionId, long version) {
    return new CounterBumpedEvent(CounterKind.SESSION, sessionId.toString(), version);
  }

  public static CounterBumpedEvent membership(UUID accountId, UUID householdId, long version) {
    return new CounterBumpedEvent(
        CounterKind.MEMBERSHIP, membershipKey(accountId, householdId), version);
  }

  public static CounterBumpedEvent profile(UUID profileId, long version) {
    return new CounterBumpedEvent(CounterKind.PROFILE, profileId.toString(), version);
  }

  public static String membershipKey(UUID accountId, UUID householdId) {
    return accountId + ":" + householdId;
  }
}
