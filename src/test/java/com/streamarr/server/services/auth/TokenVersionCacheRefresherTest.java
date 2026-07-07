package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Token Version Cache Refresher Tests")
class TokenVersionCacheRefresherTest {

  private final FakeVersionCounterReader reader = new FakeVersionCounterReader();
  private final TokenVersionCache cache = new TokenVersionCache(reader);
  private final TokenVersionCacheRefresher refresher = new TokenVersionCacheRefresher(cache);

  @Test
  @DisplayName("Should serve bumped versions without reader when events applied")
  void shouldServeBumpedVersionsWithoutReaderWhenEventsApplied() {
    var sessionId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    refresher.onCounterBumped(CounterBumpedEvent.session(sessionId, 3));
    refresher.onCounterBumped(CounterBumpedEvent.membership(accountId, householdId, 4));
    refresher.onCounterBumped(CounterBumpedEvent.profile(profileId, 5));

    // The reader has no rows: every hit below must come from the refreshed cache.
    assertThat(cache.sessionVersion(sessionId)).contains(3L);
    assertThat(cache.membershipVersion(accountId, householdId)).contains(4L);
    assertThat(cache.profilePolicyVersion(profileId)).contains(5L);
  }

  @Test
  @DisplayName("Should fall back to reader when cache cleared")
  void shouldFallBackToReaderWhenCacheCleared() {
    var sessionId = UUID.randomUUID();
    refresher.onCounterBumped(CounterBumpedEvent.session(sessionId, 7));

    cache.clearAll();

    assertThat(cache.sessionVersion(sessionId)).isEmpty();

    reader.sessionVersions.put(sessionId, 8L);
    assertThat(cache.sessionVersion(sessionId)).contains(8L);
  }
}
