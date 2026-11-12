package com.streamarr.server.config;

import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.concurrency.NanoClock;
import com.streamarr.server.services.library.MediaFolders;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.HardwareEncoderFailureListener;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.JobAttemptProgressListener;
import com.streamarr.server.services.streaming.LearnedStreamEndListener;
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
import com.streamarr.server.services.streaming.local.SegmentFolderLeftovers;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.NoOwnedFolder;
import com.streamarr.server.services.streaming.local.SegmentFolderLock;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class StreamingConfig {

  static final String LEFTOVER_DELETES_METRIC =
      "streamarr.streaming.segment_folder_leftover_deletes";
  static final String LEFTOVERS_METRIC = "streamarr.streaming.segment_folder_leftovers";

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

  /**
   * Deletes what earlier server processes left in a segment folder that only this server uses, and
   * keeps what the delete did for the health indicator. It runs as system work while the server
   * starts, before a stream session can exist.
   */
  @Bean
  public SegmentFolderLeftoversOutcome segmentFolderLeftovers(
      StreamingProperties properties,
      MediaFolders mediaFolders,
      SegmentFolderLock lock,
      MeterRegistry meterRegistry) {
    var deletes = new AtomicReference<SegmentFolderLeftoversOutcome>();
    SystemWork.runAsSystemActor(
        () -> deletes.set(deleteSegmentFolderLeftovers(properties, mediaFolders, lock)));
    var outcome = deletes.get();
    meterRegistry.counter(LEFTOVER_DELETES_METRIC, "outcome", outcome.metricTag()).increment();
    if (outcome instanceof Deleted(var deleted, var remaining)) {
      meterRegistry.counter(LEFTOVERS_METRIC, "result", "deleted").increment(deleted);
      meterRegistry.counter(LEFTOVERS_METRIC, "result", "remaining").increment(remaining);
    }

    return outcome;
  }

  private static SegmentFolderLeftoversOutcome deleteSegmentFolderLeftovers(
      StreamingProperties properties, MediaFolders mediaFolders, SegmentFolderLock lock) {
    var segmentFolder = properties.ownedSegmentFolder();
    if (segmentFolder.isEmpty()) {
      return new NoOwnedFolder();
    }

    return SegmentFolderLeftovers.builder()
        .segmentFolder(segmentFolder.get())
        .homeFolder(Path.of(System.getProperty("user.home")))
        .mediaFolders(mediaFolders::all)
        .lock(lock)
        .build()
        .delete();
  }

  // Closed when the server stops, after the segment store has deleted its stream sessions.
  @Bean
  public SegmentFolderLock segmentFolderLock() {
    return new SegmentFolderLock();
  }

  // After the delete, so the folder holds nothing of a running store while the delete runs.
  @Bean
  @DependsOn("segmentFolderLeftovers")
  public LocalSegmentStore segmentStore(StreamingProperties properties, SegmentFolderLock lock) {
    return new LocalSegmentStore(Path.of(properties.segmentBasePath()), lock);
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
  public LearnedStreamEndListener learnedStreamEndListener(
      RuntimeStreamSessionRegistry runtimeRegistry,
      LearnedStreamEndRepository learnedStreamEnds,
      MeterRegistry meterRegistry) {
    return new LearnedStreamEndListener(runtimeRegistry, learnedStreamEnds, meterRegistry);
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
