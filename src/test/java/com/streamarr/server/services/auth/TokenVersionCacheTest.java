package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 7L);
    var cache = new TokenVersionCache(reader);

    // After a reconnect clear and read-through refill, a queued pre-clear notification can
    // still arrive; it must never regress the counter.
    assertThat(cache.sessionVersion(sessionId)).contains(7L);
    cache.update(CounterKind.SESSION, sessionId.toString(), 6L);

    assertThat(cache.sessionVersion(sessionId)).contains(7L);
  }

  @Test
  @DisplayName("Should read authoritative version when stale update arrives after cache clear")
  void shouldReadAuthoritativeVersionWhenStaleUpdateArrivesAfterCacheClear() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 7L);
    var cache = new TokenVersionCache(reader);

    assertThat(cache.sessionVersion(sessionId)).contains(7L);
    cache.clearAll();
    cache.update(CounterKind.SESSION, sessionId.toString(), 6L);

    assertThat(cache.sessionVersion(sessionId)).contains(7L);
  }

  @Test
  @DisplayName("Should read through every lookup while caching is suspended")
  void shouldReadThroughEveryLookupWhileCachingSuspended() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 1L);
    var cache = new TokenVersionCache(reader);

    assertThat(cache.sessionVersion(sessionId)).contains(1L);
    cache.suspendCaching();

    reader.sessionVersions.put(sessionId, 2L);
    assertThat(cache.sessionVersion(sessionId)).contains(2L);
    reader.sessionVersions.put(sessionId, 3L);
    assertThat(cache.sessionVersion(sessionId)).contains(3L);
  }

  @Test
  @DisplayName("Should not seed a suspended cache from a counter update")
  void shouldNotSeedSuspendedCacheFromCounterUpdate() {
    var sessionId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 7L);
    var cache = new TokenVersionCache(reader);

    cache.suspendCaching();
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
      reader.awaitPausedReads();

      reader.version.set(2);
      cache.clearAll();
      reader.releasePausedReads();
      assertThat(firstLookup.get(10, TimeUnit.SECONDS)).contains(2L);

      assertThat(cache.sessionVersion(sessionId)).contains(2L);
    }
  }

  @Test
  @DisplayName("Should fence in-flight read when caching is suspended")
  void shouldFenceInFlightReadWhenCachingSuspended() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader();
    var cache = new TokenVersionCache(reader);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitPausedReads();

      reader.version.set(2L);
      cache.suspendCaching();
      reader.releasePausedReads();

      assertThat(lookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(reader.readCount).hasValue(2);

      reader.version.set(3L);
      assertThat(cache.sessionVersion(sessionId)).contains(3L);
      assertThat(reader.readCount).hasValue(3);
    } finally {
      reader.releasePausedReads();
    }
  }

  @Test
  @DisplayName("Should retry read when counter update arrives during cache miss")
  void shouldRetryReadWhenCounterUpdateArrivesDuringCacheMiss() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader();
    var cache = new TokenVersionCache(reader);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitPausedReads();

      reader.version.set(2);
      cache.update(CounterKind.SESSION, sessionId.toString(), 2L);
      reader.releasePausedReads();

      assertThat(firstLookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(reader.readCount).hasValue(2);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);
    }
  }

  @Test
  @DisplayName("Should retry suspended read when counter update arrives during cache miss")
  void shouldRetrySuspendedReadWhenCounterUpdateArrivesDuringCacheMiss() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader();
    var cache = new TokenVersionCache(reader);
    cache.suspendCaching();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitPausedReads();

      reader.version.set(2L);
      cache.update(CounterKind.SESSION, sessionId.toString(), 2L);
      reader.releasePausedReads();

      assertThat(lookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(reader.readCount).hasValue(2);

      reader.version.set(3L);
      assertThat(cache.sessionVersion(sessionId)).contains(3L);
      assertThat(reader.readCount).hasValue(3);
    } finally {
      reader.releasePausedReads();
    }
  }

  @Test
  @DisplayName("Should fence suspended read and restore warm updates when caching resumes")
  void shouldFenceSuspendedReadAndRestoreWarmUpdatesWhenCachingResumes() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader();
    var cache = new TokenVersionCache(reader);
    cache.suspendCaching();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitPausedReads();

      reader.version.set(2L);
      cache.resumeCaching();
      reader.releasePausedReads();

      assertThat(lookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(reader.readCount).hasValue(2);

      reader.version.set(3L);
      cache.update(CounterKind.SESSION, sessionId.toString(), 3L);
      assertThat(cache.sessionVersion(sessionId)).contains(3L);
      assertThat(reader.readCount).hasValue(2);
    } finally {
      reader.releasePausedReads();
    }
  }

  @Test
  @DisplayName("Should retry all concurrent misses when counter update arrives")
  void shouldRetryAllConcurrentMissesWhenCounterUpdateArrives() throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new PausingVersionCounterReader(2);
    var cache = new TokenVersionCache(reader);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      var secondLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitPausedReads();

      reader.version.set(2);
      cache.update(CounterKind.SESSION, sessionId.toString(), 2L);
      reader.releasePausedReads();

      assertThat(firstLookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(secondLookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);
    } finally {
      reader.releasePausedReads();
    }
  }

  @Test
  @DisplayName(
      "Should retain newer authoritative version when concurrent misses complete oldest first")
  void shouldRetainNewerAuthoritativeVersionWhenConcurrentMissesCompleteOldestFirst()
      throws Exception {
    var sessionId = UUID.randomUUID();
    var reader = new OrderedVersionCounterReader();
    var cache = new TokenVersionCache(reader);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var olderLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitOlderRead();
      var newerLookup = executor.submit(() -> cache.sessionVersion(sessionId));
      reader.awaitNewerRead();

      reader.releaseOlderRead();
      assertThat(olderLookup.get(10, TimeUnit.SECONDS)).contains(1L);
      reader.releaseNewerRead();

      assertThat(newerLookup.get(10, TimeUnit.SECONDS)).contains(2L);
      assertThat(cache.sessionVersion(sessionId)).contains(2L);
    } finally {
      reader.releaseAll();
    }
  }

  private static final class PausingVersionCounterReader implements VersionCounterReader {

    private final AtomicLong version = new AtomicLong(1);
    private final AtomicInteger readCount = new AtomicInteger();
    private final AtomicInteger readsToPause;
    private final CountDownLatch readsCaptured;
    private final CountDownLatch releaseReads = new CountDownLatch(1);

    private PausingVersionCounterReader() {
      this(1);
    }

    private PausingVersionCounterReader(int readsToPause) {
      this.readsToPause = new AtomicInteger(readsToPause);
      this.readsCaptured = new CountDownLatch(readsToPause);
    }

    @Override
    public Optional<Long> sessionVersion(UUID sessionId) {
      readCount.incrementAndGet();
      var captured = version.get();
      if (readsToPause.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0) {
        readsCaptured.countDown();
        await(releaseReads);
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

    private void awaitPausedReads() {
      await(readsCaptured);
    }

    private void releasePausedReads() {
      releaseReads.countDown();
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

  private static final class OrderedVersionCounterReader implements VersionCounterReader {

    private final AtomicInteger readCount = new AtomicInteger();
    private final CountDownLatch olderRead = new CountDownLatch(1);
    private final CountDownLatch newerRead = new CountDownLatch(1);
    private final CountDownLatch releaseOlder = new CountDownLatch(1);
    private final CountDownLatch releaseNewer = new CountDownLatch(1);

    @Override
    public Optional<Long> sessionVersion(UUID sessionId) {
      var invocation = readCount.getAndIncrement();
      if (invocation == 0) {
        olderRead.countDown();
        await(releaseOlder);
        return Optional.of(1L);
      }

      newerRead.countDown();
      await(releaseNewer);
      return Optional.of(2L);
    }

    @Override
    public Optional<Long> membershipVersion(UUID accountId, UUID householdId) {
      return Optional.empty();
    }

    @Override
    public Optional<Long> profilePolicyVersion(UUID profileId) {
      return Optional.empty();
    }

    private void awaitOlderRead() {
      await(olderRead);
    }

    private void awaitNewerRead() {
      await(newerRead);
    }

    private void releaseOlderRead() {
      releaseOlder.countDown();
    }

    private void releaseNewerRead() {
      releaseNewer.countDown();
    }

    private void releaseAll() {
      releaseOlderRead();
      releaseNewerRead();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("ordered cache reads did not reach the expected boundary");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
