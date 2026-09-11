package com.streamarr.server.config;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionListeners;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerSessionProperties.class)
public class WorkerSessionConfiguration {

  @Bean(initMethod = "start", destroyMethod = "close")
  public WorkerSessionServer workerSessionServer(
      WorkerSessionProperties properties, SegmentStore segmentStore) {
    var loopback = properties.loopback();
    var listeners =
        WorkerSessionListeners.builder()
            .loopbackPort(
                loopback.enabled() ? OptionalInt.of(loopback.port()) : OptionalInt.empty())
            .mutualTls(mutualTls(properties.mutualTls()))
            .build();
    return WorkerSessionServer.forListeners(listeners, segmentStore);
  }

  private Optional<WorkerSessionServerConfiguration> mutualTls(
      WorkerSessionProperties.MutualTls properties) {
    if (!properties.enabled()) {
      return Optional.empty();
    }

    var identity =
        PemTlsIdentity.builder()
            .certificate(requiredPath(properties.certificate(), "certificate"))
            .privateKey(requiredPath(properties.privateKey(), "private key"))
            .trustBundle(requiredPath(properties.trustBundle(), "trust bundle"))
            .build();
    return Optional.of(
        WorkerSessionServerConfiguration.builder()
            .port(properties.port())
            .trustDomain(properties.trustDomain())
            .tlsIdentity(identity)
            .build());
  }

  private Path requiredPath(String value, String description) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Mutual TLS " + description + " is required");
    }

    return Path.of(value);
  }
}
