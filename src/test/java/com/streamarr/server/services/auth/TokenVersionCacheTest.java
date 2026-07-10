package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    cache.update(CounterKind.SESSION, sessionId.toString(), 7L);
    cache.update(CounterKind.SESSION, sessionId.toString(), 6L);

    assertThat(cache.sessionVersion(sessionId)).contains(7L);
  }

  @Test
  @DisplayName("Should retry read when cache cleared during lookup")
  void shouldRetryReadWhenCacheClearedDuringLookup() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader();
    var cache = new TokenVersionCache(reader);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitFirstRead();

      reader.version.set(2);
      cache.clearAll();
      reader.releaseFirstRead();
      assertThat(firstLookup.get(10, TimeUnit.SECONDS)).contains(2L);

      assertThat(cache.sessionVersion(sessionId)).contains(2L);
    }
  }

  private static final class PausingVersionCounterReader implements VersionCounterReader {

    private final AtomicLong version = new AtomicLong(1);
    private final AtomicBoolean firstRead = new AtomicBoolean(true);
    private final CountDownLatch firstReadCaptured = new CountDownLatch(1);
    private final CountDownLatch releaseFirstRead = new CountDownLatch(1);

    @Override
    public Optional<Long> sessionVersion(UUID sessionId) {
      var captured = version.get();
      if (firstRead.compareAndSet(true, false)) {
        firstReadCaptured.countDown();
        await(releaseFirstRead);
      }
      return Optional.of(captured);
    }

    @Override
    public Optional<Long> membershipVersion(UUID accountId, UUID householdId) {
      return Optional.empty();
    }

    @Override
    public Optional<Long> profilePolicyVersion(UUID profileId) {
      return Optional.empty();
    }

    private void awaitFirstRead() {
      await(firstReadCaptured);
    }

    private void releaseFirstRead() {
      releaseFirstRead.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("cache race did not reach the expected boundary");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
