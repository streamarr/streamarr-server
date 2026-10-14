package com.streamarr.server.config;

import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
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
    return remoteFfprobeService(workerSessionServer, properties);
  }

  // A bean that is only a scanner keeps one FfprobeService bean, which tests can replace.
  @Bean
  public KeyframePacketScanner keyframePacketScanner(
      WorkerSessionServer workerSessionServer, RemoteTranscodeProperties properties) {
    return remoteFfprobeService(workerSessionServer, properties)::scanKeyframePackets;
  }

  private static RemoteFfprobeService remoteFfprobeService(
      WorkerSessionServer workerSessionServer, RemoteTranscodeProperties properties) {
    return new RemoteFfprobeService(
        workerSessionServer, properties.sourceNamespaceId(), Path.of(properties.sourceRoot()));
  }
}
