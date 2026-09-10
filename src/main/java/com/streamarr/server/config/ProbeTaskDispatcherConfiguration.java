package com.streamarr.server.config;

import com.streamarr.server.services.library.FileStabilityChecker;
import com.streamarr.server.services.library.ProbeTaskDispatcher;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import java.nio.file.FileSystem;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "task.probe", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ProbeTaskDispatcherProperties.class)
public class ProbeTaskDispatcherConfiguration {

  private final FileProcessingTaskCoordinator coordinator;
  private final FfprobeService producer;
  private final FileSystem fileSystem;
  private final FileStabilityChecker stabilityChecker;
  private final PersistedProbeReader reader;
  private final ProbeTaskDispatcherProperties properties;

  @Bean
  ProbeTaskDispatcher probeTaskDispatcher() {
    return ProbeTaskDispatcher.builder()
        .coordinator(coordinator)
        .producer(producer)
        .fileSystem(fileSystem)
        .stabilityChecker(stabilityChecker)
        .reader(reader)
        .maxConcurrent(properties.maxConcurrent())
        .build();
  }
}
