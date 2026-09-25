package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Plaintext Worker Session Configuration Tests")
class WorkerSessionPlaintextConfigurationTest {

  @Test
  @DisplayName(
      "Should start a worker listener when no transport mode or certificates are configured")
  void shouldStartWorkerListenerWhenNoTransportModeOrCertificatesAreConfigured() {
    new ApplicationContextRunner()
        .withUserConfiguration(WorkerSessionConfiguration.class)
        .withBean(SegmentStore.class, FakeSegmentStore::new)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues("streaming.worker-session.port=0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WorkerSessionServer.class).port()).isPositive();
            });
  }
}
