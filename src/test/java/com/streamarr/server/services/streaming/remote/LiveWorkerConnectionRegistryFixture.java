package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

final class LiveWorkerConnectionRegistryFixture {

  private LiveWorkerConnectionRegistryFixture() {}

  static LiveWorkerConnectionRegistry defaultRegistry() {
    return registryWaking(new HeldSegmentRequests(new SimpleMeterRegistry()));
  }

  static LiveWorkerConnectionRegistry registryWaking(HeldSegmentRequests heldRequests) {
    return registryBuilder().heldRequests(heldRequests).build();
  }

  static LiveWorkerConnectionRegistry registryWith(WorkerSessionServerConfiguration configuration) {
    return registryBuilder()
        .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
        .configuration(configuration)
        .build();
  }

  static LiveWorkerConnectionRegistry registryMeteredBy(MeterRegistry meterRegistry) {
    return registryBuilder()
        .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
        .meterRegistry(meterRegistry)
        .build();
  }

  private static LiveWorkerConnectionRegistry.LiveWorkerConnectionRegistryBuilder
      registryBuilder() {
    return LiveWorkerConnectionRegistry.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(WorkerSessionServerConfiguration.builder().build())
        .meterRegistry(new SimpleMeterRegistry());
  }
}
