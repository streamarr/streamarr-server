package com.streamarr.server.config;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "streaming.remote", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RemoteTranscodeProperties.class)
public class RemoteTranscodeConfiguration {

  @Bean(initMethod = "start", destroyMethod = "close")
  public WorkerSessionServer workerSessionServer(
      RemoteTranscodeProperties properties, SegmentStore segmentStore) {
    var tlsIdentity =
        PemTlsIdentity.builder()
            .certificate(Path.of(properties.certificate()))
            .privateKey(Path.of(properties.privateKey()))
            .trustBundle(Path.of(properties.trustBundle()))
            .build();
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(properties.port())
            .trustDomain(properties.trustDomain())
            .tlsIdentity(tlsIdentity)
            .build();
    return new WorkerSessionServer(configuration, segmentStore);
  }

  @Bean
  public TranscodeExecutor remoteTranscodeExecutor(
      WorkerSessionServer workerSessionServer, RemoteTranscodeProperties properties) {
    return new RemoteTranscodeExecutor(
        workerSessionServer, properties.sourceNamespaceId(), Path.of(properties.sourceRoot()));
  }
}
