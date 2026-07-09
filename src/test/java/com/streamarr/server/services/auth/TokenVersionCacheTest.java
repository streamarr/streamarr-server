package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Token Version Cache Tests")
class TokenVersionCacheTest {

  @Test
  @DisplayName("Should keep newer version when stale notification arrives late")
  void shouldKeepNewerVersionWhenStaleNotificationArrivesLate() {
    var sessionId = UUID.randomUUID();
    var cache = new TokenVersionCache(new FakeVersionCounterReader());

    // After a reconnect clear and read-through refill, a queued pre-clear notification can
    // still arrive; it must never regress the counter.
    cache.update(CounterKind.SESSION, sessionId.toString(), 7L);
    cache.update(CounterKind.SESSION, sessionId.toString(), 6L);

    assertThat(cache.sessionVersion(sessionId)).contains(7L);
  }
}
