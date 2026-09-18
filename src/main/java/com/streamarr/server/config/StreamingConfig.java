package com.streamarr.server.config;

import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.PlaybackProbeService;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StreamingConfig {

  @Bean
  public LocalSegmentStore segmentStore(StreamingProperties properties) {
    return new LocalSegmentStore(Path.of(properties.segmentBasePath()));
  }

  @Bean
  public RuntimeStreamSessionRegistry runtimeStreamSessionRegistry() {
    return new InMemoryStreamSessionRegistry();
  }

  @Bean
  public ProducerLifecycleService producerLifecycleService(
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry runtimeRegistry,
      MutexFactoryProvider mutexFactoryProvider) {
    return ProducerLifecycleService.builder()
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .sessionMutex(mutexFactoryProvider.getMutexFactory())
        .build();
  }

  @Bean
  public SegmentDeliveryCoordinator segmentDeliveryCoordinator(
      SegmentStore segmentStore, ProducerLifecycleService producerLifecycleService) {
    return SegmentDeliveryCoordinator.builder()
        .segmentStore(segmentStore)
        .producerLifecycle(producerLifecycleService)
        .build();
  }

  @Bean
  public StreamingService streamingService(
      MediaFileRepository mediaFileRepository,
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      PlaybackProbeService playbackProbeService,
      TranscodeDecisionService transcodeDecisionService,
      QualityLadderService qualityLadderService,
      StreamingProperties properties,
      PlaybackAuthorityGate authorityGate,
      RuntimeStreamSessionRegistry runtimeRegistry,
      ProducerLifecycleService producerLifecycleService,
      SegmentDeliveryCoordinator segmentDeliveryCoordinator) {
    return HlsStreamingService.builder()
        .mediaFileRepository(mediaFileRepository)
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .playbackProbeService(playbackProbeService)
        .transcodeDecisionService(transcodeDecisionService)
        .qualityLadderService(qualityLadderService)
        .properties(properties)
        .authorityGate(authorityGate)
        .runtimeRegistry(runtimeRegistry)
        .producerLifecycle(producerLifecycleService)
        .deliveryCoordinator(segmentDeliveryCoordinator)
        .build();
  }
}
