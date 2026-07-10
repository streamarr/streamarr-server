package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Per-instance memo of version counters. Misses read through to the database; absence is never
 * cached (a just-created session must not be rejected forever). Local bumps advance warm entries
 * and fence in-flight reads, while cold entries always refill from Postgres. Caching is suspended
 * whenever the notification feed is unavailable so every lookup remains authoritative until the
 * feed resumes (ADR 0016 cold start).
 */
@Component
@RequiredArgsConstructor
public class TokenVersionCache {

  private final VersionCounterReader reader;

  private final ConcurrentHashMap<CacheKey, Long> cache = new ConcurrentHashMap<>();
  private final Map<CacheKey, InFlightReads> inFlightReads = new HashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private final ReentrantReadWriteLock invalidationLock = new ReentrantReadWriteLock();
  private volatile boolean cachingEnabled = true;

  public Optional<Long> sessionVersion(UUID sessionId) {
    return lookup(
        CounterKind.SESSION, sessionId.toString(), () -> reader.sessionVersion(sessionId));
  }

  public Optional<Long> membershipVersion(UUID accountId, UUID householdId) {
    return lookup(
        CounterKind.MEMBERSHIP,
        CounterBumpedEvent.membershipKey(accountId, householdId),
        () -> reader.membershipVersion(accountId, householdId));
  }

  public Optional<Long> profilePolicyVersion(UUID profileId) {
    return lookup(
        CounterKind.PROFILE, profileId.toString(), () -> reader.profilePolicyVersion(profileId));
  }

  public void update(CounterKind kind, String key, long version) {
    var cacheKey = new CacheKey(kind, key);
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      var reads = inFlightReads.get(cacheKey);
      if (reads != null) {
        inFlightReads.put(cacheKey, reads.bumpEpoch());
      }
      if (cachingEnabled) {
        cache.computeIfPresent(cacheKey, (_, current) -> Math.max(current, version));
      }
    } finally {
      lock.unlock();
    }
  }

  public void clearAll() {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      resetWhileLocked();
    } finally {
      lock.unlock();
    }
  }

  public void suspendCaching() {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      cachingEnabled = false;
      resetWhileLocked();
    } finally {
      lock.unlock();
    }
  }

  public void resumeCaching() {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      resetWhileLocked();
      cachingEnabled = true;
    } finally {
      lock.unlock();
    }
  }

  private Optional<Long> lookup(
      CounterKind kind, String key, Supplier<Optional<Long>> readThrough) {
    var cacheKey = new CacheKey(kind, key);

    var cached = cachedVersion(cacheKey);
    if (cached.isPresent()) {
      return cached;
    }

    while (true) {
      var attempt = register(cacheKey);
      var registered = true;
      try {
        var read = readThrough.get();
        var completion = complete(cacheKey, attempt, read);
        registered = false;
        if (!completion.retry()) {
          return completion.version();
        }
      } finally {
        if (registered) {
          unregister(cacheKey);
        }
      }
    }
  }

  private LookupAttempt register(CacheKey cacheKey) {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      var reads = inFlightReads.get(cacheKey);
      var registered = reads == null ? InFlightReads.first() : reads.addReader();
      inFlightReads.put(cacheKey, registered);
      return new LookupAttempt(generation.get(), registered.epoch());
    } finally {
      lock.unlock();
    }
  }

  private LookupCompletion complete(CacheKey cacheKey, LookupAttempt attempt, Optional<Long> read) {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      var reads = inFlightReads.get(cacheKey);
      if (reads == null) {
        throw new IllegalStateException("Cache lookup completed without an in-flight read");
      }

      var invalidated =
          generation.get() != attempt.generation() || reads.epoch() != attempt.epoch();
      var resolved = resolve(cacheKey, read, invalidated);
      unregisterWhileLocked(cacheKey, reads);
      return new LookupCompletion(invalidated && resolved.isEmpty(), resolved);
    } finally {
      lock.unlock();
    }
  }

  private void unregister(CacheKey cacheKey) {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      var reads = inFlightReads.get(cacheKey);
      if (reads != null) {
        unregisterWhileLocked(cacheKey, reads);
      }
    } finally {
      lock.unlock();
    }
  }

  private void unregisterWhileLocked(CacheKey cacheKey, InFlightReads reads) {
    if (reads.count() == 1) {
      inFlightReads.remove(cacheKey);
      return;
    }
    inFlightReads.put(cacheKey, reads.removeReader());
  }

  private Optional<Long> resolve(
      CacheKey cacheKey, Optional<Long> authoritativeVersion, boolean invalidated) {
    if (invalidated) {
      return cachingEnabled ? Optional.ofNullable(cache.get(cacheKey)) : Optional.empty();
    }
    if (!cachingEnabled) {
      return authoritativeVersion;
    }
    authoritativeVersion.ifPresent(version -> cache.merge(cacheKey, version, Math::max));
    return Optional.ofNullable(cache.get(cacheKey));
  }

  private Optional<Long> cachedVersion(CacheKey cacheKey) {
    var observedGeneration = generation.get();
    if (!cachingEnabled) {
      return Optional.empty();
    }
    var cached = cache.get(cacheKey);
    if (!cachingEnabled || generation.get() != observedGeneration) {
      return Optional.empty();
    }
    return Optional.ofNullable(cached);
  }

  private void resetWhileLocked() {
    generation.incrementAndGet();
    cache.clear();
  }

  private record CacheKey(CounterKind kind, String key) {}

  private record LookupAttempt(long generation, long epoch) {}

  private record LookupCompletion(boolean retry, Optional<Long> version) {}

  private record InFlightReads(int count, long epoch) {

    private static InFlightReads first() {
      return new InFlightReads(1, 0);
    }

    private InFlightReads addReader() {
      return new InFlightReads(count + 1, epoch);
    }

    private InFlightReads removeReader() {
      return new InFlightReads(count - 1, epoch);
    }

    private InFlightReads bumpEpoch() {
      return new InFlightReads(count, epoch + 1);
    }
  }
}
