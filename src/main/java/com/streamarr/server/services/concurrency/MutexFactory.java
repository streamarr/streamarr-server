package com.streamarr.server.services.concurrency;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.concurrent.locks.ReentrantLock;

public class MutexFactory<K> {

  // The cache refers weakly to the lock itself, so a key keeps its mutex for as long as any caller
  // references it, and an unused one is collected. A map that referred softly or weakly to its
  // entries could drop the entry of a held lock and hand the key a second one.
  private final LoadingCache<K, ReentrantLock> mutexes =
      CacheBuilder.newBuilder().weakValues().build(CacheLoader.from(_ -> new ReentrantLock()));

  public ReentrantLock getMutex(K key) {
    return mutexes.getUnchecked(key);
  }

  /** Whether the current thread holds one of this factory's mutexes. */
  public boolean isAnyMutexHeldByCurrentThread() {
    return mutexes.asMap().values().stream().anyMatch(ReentrantLock::isHeldByCurrentThread);
  }
}
