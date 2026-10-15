package com.streamarr.server.fixtures;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.time.Clock;
import java.time.Duration;
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
      Duration pollInterval,
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
    var coordinatorBuilder =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle);
    if (pollInterval != null) {
      coordinatorBuilder.pollInterval(pollInterval);
    }
    return new StreamingRig(lifecycle, coordinatorBuilder.build());
  }

  public record StreamingRig(
      ProducerLifecycleService lifecycle, SegmentDeliveryCoordinator coordinator) {}
}
