package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CounterKind;
import com.streamarr.server.repositories.auth.VersionCounterReader;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
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
 * cached (a just-created session must not be rejected forever). Local bumps arrive via
 * CounterBumpedEvent; a clear falls back to lazy refill (ADR 0016 cold start).
 */
@Component
@RequiredArgsConstructor
public class TokenVersionCache {

  private final VersionCounterReader reader;

  private final ConcurrentHashMap<CacheKey, Long> cache = new ConcurrentHashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private final ReentrantReadWriteLock invalidationLock = new ReentrantReadWriteLock();

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
    var lock = invalidationLock.readLock();
    lock.lock();
    try {
      cache.merge(new CacheKey(kind, key), version, Math::max);
    } finally {
      lock.unlock();
    }
  }

  public void clearAll() {
    var lock = invalidationLock.writeLock();
    lock.lock();
    try {
      generation.incrementAndGet();
      cache.clear();
    } finally {
      lock.unlock();
    }
  }

  private Optional<Long> lookup(
      CounterKind kind, String key, Supplier<Optional<Long>> readThrough) {
    var cacheKey = new CacheKey(kind, key);

    var cached = cache.get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }

    while (true) {
      var readGeneration = generation.get();
      var read = readThrough.get();
      var lock = invalidationLock.readLock();
      lock.lock();
      try {
        if (generation.get() != readGeneration) {
          continue;
        }

        read.ifPresent(version -> cache.putIfAbsent(cacheKey, version));
        return Optional.ofNullable(cache.get(cacheKey));
      } finally {
        lock.unlock();
      }
    }
  }

  private record CacheKey(CounterKind kind, String key) {}
}
