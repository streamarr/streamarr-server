package com.streamarr.server.config;

import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionAccessService;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionCreationService;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionTerminationService;
import com.streamarr.server.services.streaming.DefaultPlaybackTranscodeJobService;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackSessionAccessService;
import com.streamarr.server.services.streaming.PlaybackSessionCreationService;
import com.streamarr.server.services.streaming.PlaybackSessionTerminationService;
import com.streamarr.server.services.streaming.PlaybackTranscodeJobService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamSessionCleanup;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import com.streamarr.server.services.streaming.StreamSessionTransactionRetry;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeCapacityTracker;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfprobeService;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.server.services.streaming.worker.local.LocalTranscodeWorkerAdapter;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.LocalFfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.job.LocalTranscodeEngine;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class StreamingConfig {

  /** Reserved stable identity for the in-process worker across application boots. */
  static final UUID LOCAL_WORKER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Bean(destroyMethod = "")
  public LocalSegmentStorage localSegmentStorage(StreamingProperties properties) {
    return new LocalSegmentStorage(Path.of(properties.segmentBasePath()));
  }

  @Bean(destroyMethod = "")
  public LocalSegmentStore segmentStore(LocalSegmentStorage storage) {
    return new LocalSegmentStore(storage);
  }

  @Bean
  public FfmpegPaths ffmpegPaths(StreamingProperties properties) {
    return FfmpegPaths.resolve(properties.ffmpegPath(), properties.ffprobePath());
  }

  @Bean
  public FfmpegCommandBuilder ffmpegCommandBuilder(FfmpegPaths ffmpegPaths) {
    return new FfmpegCommandBuilder(ffmpegPaths.ffmpeg());
  }

  @Bean
  public LocalFfmpegProcessManager localFfmpegProcessManager() {
    return new LocalFfmpegProcessManager();
  }

  @Bean
  public TranscodeCapabilityService transcodeCapabilityService(FfmpegPaths ffmpegPaths) {
    var service =
        new TranscodeCapabilityService(
            ffmpegPaths.ffmpeg(),
            command -> new ProcessBuilder(command).redirectErrorStream(false).start());
    service.detectCapabilities();

    return service;
  }

  @Bean
  public FfprobeService ffprobeService(ObjectMapper objectMapper, FfmpegPaths ffmpegPaths) {
    return new LocalFfprobeService(
        objectMapper,
        filepath -> {
          try {
            return new ProcessBuilder(
                    ffmpegPaths.ffprobe(),
                    "-v",
                    "quiet",
                    "-print_format",
                    "json",
                    "-show_streams",
                    "-show_format",
                    filepath.toString())
                .start();
          } catch (IOException e) {
            throw new UncheckedIOException("Failed to start ffprobe", e);
          }
        });
  }

  @Bean(destroyMethod = "shutdown")
  public LocalTranscodeEngine localTranscodeEngine(
      FfmpegCommandBuilder commandBuilder,
      FfmpegProcessManager processManager,
      LocalSegmentStorage segmentStorage,
      TranscodeCapabilityService capabilityService) {
    return LocalTranscodeEngine.builder()
        .commandBuilder(commandBuilder)
        .processManager(processManager)
        .segmentStorage(segmentStorage)
        .capabilityService(capabilityService)
        .build();
  }

  @Bean
  public WorkerTarget localWorkerTarget() {
    return new WorkerTarget(LOCAL_WORKER_ID, UUID.randomUUID());
  }

  @Bean
  public TranscodeWorkerPort transcodeWorkerPort(
      WorkerTarget localWorkerTarget,
      MediaSourceCatalog mediaSourceCatalog,
      LocalTranscodeEngine localTranscodeEngine) {
    return new LocalTranscodeWorkerAdapter(
        localWorkerTarget, mediaSourceCatalog, localTranscodeEngine);
  }

  @Bean
  public RuntimeStreamSessionRegistry streamSessionRepository() {
    return new InMemoryStreamSessionRepository();
  }

  @Bean
  public MutexFactory<UUID> streamingLifecycleMutexFactory() {
    return new MutexFactory<>();
  }

  @Bean
  public TranscodeCapacityTracker transcodeCapacityTracker() {
    return new TranscodeCapacityTracker();
  }

  @Bean
  public PlaybackTranscodeJobService playbackTranscodeJobService(
      TranscodeWorkerPort worker,
      WorkerTarget workerTarget,
      RuntimeStreamSessionRegistry runtimeRegistry,
      MutexFactory<UUID> streamingLifecycleMutexFactory) {
    return DefaultPlaybackTranscodeJobService.builder()
        .worker(worker)
        .workerTarget(workerTarget)
        .runtimeRegistry(runtimeRegistry)
        .sessionMutexes(streamingLifecycleMutexFactory)
        .build();
  }

  @Bean
  public StreamingService streamingService(
      MediaFileRepository mediaFileRepository,
      SegmentStore segmentStore,
      FfprobeService ffprobeService,
      TranscodeDecisionService transcodeDecisionService,
      QualityLadderService qualityLadderService,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry streamSessionRepository,
      MutexFactory<UUID> streamingLifecycleMutexFactory,
      PlaybackTranscodeJobService playbackTranscodeJobService,
      MediaSourceCatalog mediaSourceCatalog,
      TranscodeCapacityTracker transcodeCapacityTracker) {
    return new HlsStreamingService(
        mediaFileRepository,
        segmentStore,
        ffprobeService,
        transcodeDecisionService,
        qualityLadderService,
        properties,
        streamSessionRepository,
        streamingLifecycleMutexFactory,
        playbackTranscodeJobService,
        mediaSourceCatalog,
        transcodeCapacityTracker);
  }

  @Bean
  public PlaybackSessionCreationService playbackSessionCreationService(
      StreamingService streamingService,
      PlaybackTokenIssuer playbackTokenIssuer,
      StreamingProperties streamingProperties,
      StreamSessionLifecycleTransactions lifecycleTransactions,
      RuntimeStreamSessionRegistry runtimeRegistry,
      StreamSessionCleanup cleanup,
      StreamSessionTransactionRetry transactionRetry,
      Clock clock) {
    return new DefaultPlaybackSessionCreationService(
        streamingService,
        playbackTokenIssuer,
        streamingProperties,
        lifecycleTransactions,
        runtimeRegistry,
        cleanup,
        transactionRetry,
        clock);
  }

  @Bean
  public PlaybackSessionAccessService playbackSessionAccessService(
      RuntimeStreamSessionRegistry runtimeRegistry,
      StreamSessionLifecycleTransactions lifecycleTransactions,
      StreamSessionTransactionRetry transactionRetry) {
    return new DefaultPlaybackSessionAccessService(
        runtimeRegistry, lifecycleTransactions, transactionRetry);
  }

  @Bean
  public PlaybackSessionTerminationService playbackSessionTerminationService(
      RuntimeStreamSessionRegistry runtimeRegistry,
      StreamSessionLifecycleTransactions lifecycleTransactions,
      StreamSessionCleanup cleanup,
      StreamSessionTransactionRetry transactionRetry,
      Clock clock) {
    return new DefaultPlaybackSessionTerminationService(
        runtimeRegistry, lifecycleTransactions, cleanup, transactionRetry, clock);
  }
}
