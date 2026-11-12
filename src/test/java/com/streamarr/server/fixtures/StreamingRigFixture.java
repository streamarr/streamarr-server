package com.streamarr.server.fixtures;

import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SeekPriority;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import lombok.Builder;
import org.springframework.context.ApplicationEventPublisher;

public final class StreamingRigFixture {

  private StreamingRigFixture() {}

  @Builder(builderMethodName = "streamingRigBuilder")
  public static StreamingRig streamingRig(
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry runtimeRegistry,
      Clock clock,
      Clock seekPriorityClock,
      HeldSegmentRequests heldRequests,
      ServerSettingsService serverSettings,
      MutexFactory<UUID> sessionMutex,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry) {
    var meters = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    var held = heldRequests == null ? new HeldSegmentRequests(meters) : heldRequests;
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .eventPublisher(eventPublisher == null ? new CapturingEventPublisher() : eventPublisher)
            .sessionMutex(sessionMutex == null ? new MutexFactory<>() : sessionMutex)
            .clock(clock == null ? Clock.systemUTC() : clock)
            .serverSettings(
                serverSettings == null
                    ? ServerSettingsFixture.defaultServerSettings()
                    : serverSettings)
            .seekPriority(
                SeekPriority.builder()
                    .transcodeExecutor(transcodeExecutor)
                    .segmentStore(segmentStore)
                    .runtimeRegistry(runtimeRegistry)
                    .properties(properties)
                    .heldRequests(held)
                    .meterRegistry(meters)
                    .clock(seekPriorityClock == null ? Clock.systemUTC() : seekPriorityClock)
                    .build())
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle)
            .heldRequests(held)
            .eventPublisher(eventPublisher == null ? new CapturingEventPublisher() : eventPublisher)
            .build();
    return new StreamingRig(lifecycle, coordinator);
  }

  public record StreamingRig(
      ProducerLifecycleService lifecycle, SegmentDeliveryCoordinator coordinator) {}
}
