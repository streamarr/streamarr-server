package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.HeldSegmentRequests;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

final class LiveWorkerConnectionRegistryFixture {

  private LiveWorkerConnectionRegistryFixture() {}

  static LiveWorkerConnectionRegistry defaultRegistry() {
    return registryWaking(new HeldSegmentRequests(new SimpleMeterRegistry()));
  }

  static LiveWorkerConnectionRegistry registryWaking(HeldSegmentRequests heldRequests) {
    return new LiveWorkerConnectionRegistry(
        WorkerSessionServerConfiguration.builder().build(),
        new SimpleMeterRegistry(),
        heldRequests);
  }
}
