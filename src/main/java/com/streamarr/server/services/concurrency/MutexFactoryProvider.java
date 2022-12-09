package com.streamarr.server.services.concurrency;

import org.springframework.stereotype.Component;

@Component
public class MutexFactoryProvider {

    public <K> MutexFactory<K> getMutexFactory() {
        return new MutexFactory<>();
    }
}
