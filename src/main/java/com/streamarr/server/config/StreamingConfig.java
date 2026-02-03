package com.streamarr.server.config;

import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfprobeService;
import com.streamarr.server.services.streaming.ffmpeg.LocalTranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class StreamingConfig {

  @Bean
  public SegmentStore segmentStore() {
    var baseDir = Path.of(System.getProperty("java.io.tmpdir"), "streamarr-segments");
    return new LocalSegmentStore(baseDir);
  }

  @Bean
  public TranscodeCapabilityService transcodeCapabilityService() {
    var service =
        new TranscodeCapabilityService(
            command -> new ProcessBuilder(command).redirectErrorStream(false).start());
    service.detectCapabilities();
    return service;
  }

  @Bean
  public FfprobeService ffprobeService(ObjectMapper objectMapper) {
    return new LocalFfprobeService(
        objectMapper,
        filepath -> {
          try {
            return new ProcessBuilder(
                    "ffprobe",
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
      SegmentStore segmentStore,
      TranscodeCapabilityService capabilityService) {
    return new LocalTranscodeExecutor(commandBuilder, processManager, segmentStore,
        capabilityService);
  }

  @Bean
  public StreamingService streamingService(
      MediaFileRepository mediaFileRepository,
      TranscodeExecutor transcodeExecutor,
      SegmentStore segmentStore,
      FfprobeService ffprobeService,
      TranscodeDecisionService transcodeDecisionService,
      QualityLadderService qualityLadderService,
      StreamingProperties properties) {
    return new HlsStreamingService(
        mediaFileRepository,
        transcodeExecutor,
        segmentStore,
        ffprobeService,
        transcodeDecisionService,
        qualityLadderService,
        properties);
  }
}
