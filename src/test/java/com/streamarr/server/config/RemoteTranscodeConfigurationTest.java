package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Remote Transcode Configuration Tests")
class RemoteTranscodeConfigurationTest {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(
              RemoteTranscodeConfiguration.class, SegmentStoreConfiguration.class)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  @DisplayName("Should reject missing source mapping when a worker listener is enabled")
  void shouldRejectMissingSourceMappingWhenAWorkerListenerIsEnabled() {
    contextRunner
        .withUserConfiguration(WorkerSessionConfiguration.class)
        .withPropertyValues("streaming.worker-session.port=0")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Remote source namespace ID is required");
            });
  }

  @Test
  @DisplayName("Should require a source root when configuring worker execution")
  void shouldRequireASourceRootWhenConfiguringWorkerExecution() {
    assertThatThrownBy(() -> new RemoteTranscodeProperties(SOURCE_NAMESPACE_ID, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Remote source root is required");
  }

  @Test
  @DisplayName("Should start the outbound worker listener when explicitly configured")
  void shouldStartOutboundWorkerListenerWhenExplicitlyConfigured() {
    contextRunner
        .withUserConfiguration(WorkerSessionConfiguration.class)
        .withPropertyValues(remoteProperties())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WorkerSessionServer.class);
              assertThat(context).hasSingleBean(TranscodeExecutor.class);
              assertThat(context.getBean(TranscodeExecutor.class))
                  .isInstanceOf(RemoteTranscodeExecutor.class);
              assertThat(context.getBean(WorkerSessionServer.class).port()).isPositive();
            });
  }

  private String[] remoteProperties() {
    return new String[] {
      "streaming.worker-session.port=0",
      "streaming.remote.source-namespace-id=" + SOURCE_NAMESPACE_ID,
      "streaming.remote.source-root=/media"
    };
  }

  @Configuration(proxyBeanMethods = false)
  static class SegmentStoreConfiguration {

    @Bean
    SegmentStore segmentStore() {
      return new FakeSegmentStore();
    }
  }
}
