package com.streamarr.server.services.concurrency;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.util.ConcurrentReferenceHashMap;

public class MutexFactory<K> {

  private final ConcurrentReferenceHashMap<K, ReentrantLock> map =
      new ConcurrentReferenceHashMap<>();

  public ReentrantLock getMutex(K key) {
    return map.compute(key, (k, v) -> v == null ? new ReentrantLock() : v);
  }
}
