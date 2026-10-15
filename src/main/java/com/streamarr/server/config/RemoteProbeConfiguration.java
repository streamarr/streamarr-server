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

  // A method reference, so the bean is only a KeyframePacketScanner. Spring matches a singleton by
  // its instance type, so returning the RemoteFfprobeService would make this bean a second
  // FfprobeService candidate, and every FfprobeService injection and @TestBean would find two.
  // For the same reason, RemoteFfprobeService does not implement KeyframePacketScanner.
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
