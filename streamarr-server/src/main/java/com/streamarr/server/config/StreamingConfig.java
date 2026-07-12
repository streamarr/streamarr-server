package com.streamarr.server.config;

import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionAccessService;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionCreationService;
import com.streamarr.server.services.streaming.DefaultPlaybackSessionTerminationService;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackSessionAccessService;
import com.streamarr.server.services.streaming.PlaybackSessionCreationService;
import com.streamarr.server.services.streaming.PlaybackSessionTerminationService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamSessionCleanup;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import com.streamarr.server.services.streaming.StreamSessionTransactionRetry;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfprobeService;
import com.streamarr.server.services.streaming.ffmpeg.LocalTranscodeExecutor;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.engine.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.LocalFfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class StreamingConfig {

  @Bean
  public LocalSegmentStorage localSegmentStorage(StreamingProperties properties) {
    return new LocalSegmentStorage(Path.of(properties.segmentBasePath()));
  }

  @Bean
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

  @Bean
  public TranscodeExecutor transcodeExecutor(
      FfmpegCommandBuilder commandBuilder,
      FfmpegProcessManager processManager,
      LocalSegmentStore segmentStore,
      TranscodeCapabilityService capabilityService) {
    return new LocalTranscodeExecutor(
        commandBuilder, processManager, segmentStore, capabilityService);
  }

  @Bean
  public RuntimeStreamSessionRegistry streamSessionRepository() {
    return new InMemoryStreamSessionRepository();
  }

  @Bean
  public StreamingService streamingService(
      MediaFileRepository mediaFileRepository,
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      FfprobeService ffprobeService,
      TranscodeDecisionService transcodeDecisionService,
      QualityLadderService qualityLadderService,
      StreamingProperties properties,
      RuntimeStreamSessionRegistry streamSessionRepository,
      MutexFactoryProvider mutexFactoryProvider) {
    return new HlsStreamingService(
        mediaFileRepository,
        transcodeExecutor,
        segmentStore,
        ffprobeService,
        transcodeDecisionService,
        qualityLadderService,
        properties,
        streamSessionRepository,
        mutexFactoryProvider.getMutexFactory());
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
