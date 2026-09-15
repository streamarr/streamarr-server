package com.streamarr.server.config;

import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RemoteTranscodeProperties.class)
public class RemoteTranscodeConfiguration {

  @Bean
  public TranscodeExecutor remoteTranscodeExecutor(
      WorkerSessionServer workerSessionServer, RemoteTranscodeProperties properties) {
    return new RemoteTranscodeExecutor(
        workerSessionServer, properties.sourceNamespaceId(), Path.of(properties.sourceRoot()));
  }
}
