package com.streamarr.server.fixtures;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.time.Clock;
import java.util.UUID;
import lombok.Builder;

public final class StreamingRigFixture {

  private StreamingRigFixture() {}

  @Builder(builderMethodName = "streamingRigBuilder")
  public static StreamingRig streamingRig(
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry runtimeRegistry,
      Clock clock,
      HeldSegmentRequests heldRequests,
      ServerSettingsService serverSettings,
      MutexFactory<UUID> sessionMutex) {
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(sessionMutex == null ? new MutexFactory<>() : sessionMutex)
            .clock(clock == null ? Clock.systemUTC() : clock)
            .serverSettings(
                serverSettings == null
                    ? ServerSettingsFixture.defaultServerSettings()
                    : serverSettings)
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle)
            .heldRequests(heldRequests == null ? new HeldSegmentRequests() : heldRequests)
            .build();
    return new StreamingRig(lifecycle, coordinator);
  }

  public record StreamingRig(
      ProducerLifecycleService lifecycle, SegmentDeliveryCoordinator coordinator) {}
}
