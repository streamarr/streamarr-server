package com.streamarr.server.config;

import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.concurrency.NanoClock;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.HardwareEncoderFailureListener;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.JobAttemptProgressListener;
import com.streamarr.server.services.streaming.MediaSegmentPublicationListener;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.services.streaming.PlaybackLanguagePreferences;
import com.streamarr.server.services.streaming.PlaybackProbeService;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SeekPriority;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentFolderSpace;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.ShortCopyListener;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.FileStoreSegmentFolderSpace;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StreamingConfig {

  @Bean
  public PlaybackSessionService playbackSessionService(
      StreamingService streamingService,
      PlaybackAuthorityGate authorityGate,
      StreamingProperties properties,
      ApplicationEventPublisher eventPublisher) {
    return PlaybackSessionService.builder()
        .streamingService(streamingService)
        .eventPublisher(eventPublisher)
        .authorityGate(authorityGate)
        .maximumFormatAttempts(properties.maxFormatAttempts())
        .build();
  }

  @Bean
  public LocalSegmentStore segmentStore(StreamingProperties properties) {
    return new LocalSegmentStore(Path.of(properties.segmentBasePath()));
  }

  @Bean
  public SegmentFolderSpace segmentFolderSpace(StreamingProperties properties) {
    return new FileStoreSegmentFolderSpace(Path.of(properties.segmentBasePath()));
  }

  @Bean
  public NanoClock nanoClock() {
    return System::nanoTime;
  }

  @Bean
  public RuntimeStreamSessionRegistry runtimeStreamSessionRegistry() {
    return new InMemoryStreamSessionRegistry();
  }

  @Bean
  public SeekPriority seekPriority(
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry runtimeRegistry,
      HeldSegmentRequests heldSegmentRequests,
      MeterRegistry meterRegistry) {
    return SeekPriority.builder()
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .heldRequests(heldSegmentRequests)
        .meterRegistry(meterRegistry)
        .build();
  }

  @Bean
  public ProducerLifecycleService producerLifecycleService(
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry runtimeRegistry,
      MutexFactoryProvider mutexFactoryProvider,
      ServerSettingsService serverSettingsService,
      ApplicationEventPublisher eventPublisher,
      SeekPriority seekPriority) {
    return ProducerLifecycleService.builder()
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .sessionMutex(mutexFactoryProvider.getMutexFactory())
        .serverSettings(serverSettingsService)
        .eventPublisher(eventPublisher)
        .seekPriority(seekPriority)
        .build();
  }

  @Bean
  public SegmentDeliveryCoordinator segmentDeliveryCoordinator(
      SegmentStore segmentStore,
      ProducerLifecycleService producerLifecycleService,
      HeldSegmentRequests heldSegmentRequests,
      ApplicationEventPublisher eventPublisher) {
    return SegmentDeliveryCoordinator.builder()
        .segmentStore(segmentStore)
        .producerLifecycle(producerLifecycleService)
        .heldRequests(heldSegmentRequests)
        .eventPublisher(eventPublisher)
        .build();
  }

  @Bean
  public JobAttemptProgressListener jobAttemptProgressListener(
      ProducerLifecycleService producerLifecycleService) {
    return new JobAttemptProgressListener(producerLifecycleService);
  }

  @Bean
  public MediaSegmentPublicationListener mediaSegmentPublicationListener(
      ProducerLifecycleService producerLifecycleService) {
    return new MediaSegmentPublicationListener(producerLifecycleService);
  }

  @Bean
  public ShortCopyListener shortCopyListener(
      ProducerLifecycleService producerLifecycleService, ApplicationEventPublisher eventPublisher) {
    return new ShortCopyListener(producerLifecycleService, eventPublisher);
  }

  @Bean
  public HardwareEncoderFailureListener hardwareEncoderFailureListener(
      ProducerLifecycleService producerLifecycleService, ApplicationEventPublisher eventPublisher) {
    return new HardwareEncoderFailureListener(producerLifecycleService, eventPublisher);
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
      SegmentDeliveryCoordinator segmentDeliveryCoordinator,
      MeterRegistry meterRegistry,
      PlaybackLanguagePreferences languagePreferences) {
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
        .meterRegistry(meterRegistry)
        .languagePreferences(languagePreferences)
        .build();
  }
}
