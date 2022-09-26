package com.streamarr.server.services.library;

import org.springframework.stereotype.Component;
import org.springframework.util.ConcurrentReferenceHashMap;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class MutexFactory<K> {

    private final ConcurrentReferenceHashMap<K, ReentrantLock> map;

    public MutexFactory() {
        this.map = new ConcurrentReferenceHashMap<>();
    }

    public ReentrantLock getMutex(K key) {
        return this.map.compute(key, (k, v) -> v == null ? new ReentrantLock() : v);
    }
}
