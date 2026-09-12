package com.streamarr.server.config;

import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RemoteTranscodeProperties.class)
public class RemoteProbeConfiguration {

  @Bean
  public FfprobeService ffprobeService(
      WorkerSessionServer workerSessionServer, RemoteTranscodeProperties properties) {
    return new RemoteFfprobeService(
        workerSessionServer, properties.sourceNamespaceId(), Path.of(properties.sourceRoot()));
  }
}
