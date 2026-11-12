package com.streamarr.server.services.concurrency;

import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.NonNull;

public class MutexFactory<K> {

  // The map refers weakly to each mutex itself. A map that refers softly or weakly to its entries
  // can drop the entry of a held mutex, and the key then gets a second one.
  private final ConcurrentMap<K, ReentrantLock> map = new MapMaker().weakValues().makeMap();

  /**
   * The key's mutex. It stays the key's only mutex while a caller references it, so a caller keeps
   * the returned mutex until it has unlocked it.
   */
  public ReentrantLock getMutex(@NonNull K key) {
    return map.computeIfAbsent(key, _ -> new ReentrantLock());
  }

  /** Whether the current thread holds one of this factory's mutexes. */
  public boolean isAnyMutexHeldByCurrentThread() {
    return map.values().stream().anyMatch(ReentrantLock::isHeldByCurrentThread);
  }
}
