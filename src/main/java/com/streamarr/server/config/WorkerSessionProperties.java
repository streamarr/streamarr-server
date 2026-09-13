package com.streamarr.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "streaming.worker-session")
public record WorkerSessionProperties(
    @DefaultValue Localhost localhost, @DefaultValue MutualTls mutualTls) {

  public record Localhost(@DefaultValue("false") boolean enabled, @DefaultValue("9090") int port) {}

  public record MutualTls(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("9090") int port,
      String trustDomain,
      String certificate,
      String privateKey,
      String trustBundle) {}
}
