package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "streaming.worker-session")
public record WorkerSessionProperties(
    @DefaultValue("127.0.0.1") String address,
    @DefaultValue("9090") int port,
    @DefaultValue("1m") Duration probeTimeout,
    @DefaultValue("5s") Duration probeCancellationTimeout) {}
