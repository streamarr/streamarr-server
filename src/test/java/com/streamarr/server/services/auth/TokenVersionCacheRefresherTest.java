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

    reader.sessionVersions.put(sessionId, 2L);
    reader.membershipVersions.put(accountId + ":" + householdId, 3L);
    reader.profilePolicyVersions.put(profileId, 4L);
    assertThat(cache.sessionVersion(sessionId)).contains(2L);
    assertThat(cache.membershipVersion(accountId, householdId)).contains(3L);
    assertThat(cache.profilePolicyVersion(profileId)).contains(4L);
    reader.failWith(new IllegalStateException("reader should not be called for warm entries"));

    refresher.onCounterBumped(CounterBumpedEvent.session(sessionId, 3));
    refresher.onCounterBumped(CounterBumpedEvent.membership(accountId, householdId, 4));
    refresher.onCounterBumped(CounterBumpedEvent.profile(profileId, 5));

    assertThat(cache.sessionVersion(sessionId)).contains(3L);
    assertThat(cache.membershipVersion(accountId, householdId)).contains(4L);
    assertThat(cache.profilePolicyVersion(profileId)).contains(5L);
  }

  @Test
  @DisplayName("Should fall back to reader when cache cleared")
  void shouldFallBackToReaderWhenCacheCleared() {
    var sessionId = UUID.randomUUID();
    reader.sessionVersions.put(sessionId, 7L);
    assertThat(cache.sessionVersion(sessionId)).contains(7L);

    cache.clearAll();

    reader.sessionVersions.remove(sessionId);
    assertThat(cache.sessionVersion(sessionId)).isEmpty();

    reader.sessionVersions.put(sessionId, 8L);
    assertThat(cache.sessionVersion(sessionId)).contains(8L);
  }

  @Test
  @DisplayName("Should retain newer version when older event arrives")
  void shouldRetainNewerVersionWhenOlderEventArrives() {
    var sessionId = UUID.randomUUID();
    reader.sessionVersions.put(sessionId, 8L);
    assertThat(cache.sessionVersion(sessionId)).contains(8L);

    refresher.onCounterBumped(CounterBumpedEvent.session(sessionId, 7));

    assertThat(cache.sessionVersion(sessionId)).contains(8L);
  }

  @Test
  @DisplayName("Should memoize present version read from repository")
  void shouldMemoizePresentVersionReadFromRepository() {
    var sessionId = UUID.randomUUID();
    reader.sessionVersions.put(sessionId, 8L);

    assertThat(cache.sessionVersion(sessionId)).contains(8L);

    reader.sessionVersions.put(sessionId, 9L);
    assertThat(cache.sessionVersion(sessionId)).contains(8L);
  }
}
