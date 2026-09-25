package com.streamarr.server.services.streaming.remote;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

final class LiveWorkerConnectionRegistryFixture {

  private LiveWorkerConnectionRegistryFixture() {}

  static LiveWorkerConnectionRegistry defaultRegistry() {
    return new LiveWorkerConnectionRegistry(
        WorkerSessionServerConfiguration.builder().build(), new SimpleMeterRegistry());
  }
}
