package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
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
          .withUserConfiguration(RemoteTranscodeConfiguration.class, SegmentStoreConfiguration.class);

  @Test
  @DisplayName("Should leave remote transcoding inactive by default")
  void shouldLeaveRemoteTranscodingInactiveByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(WorkerSessionServer.class);
          assertThat(context).doesNotHaveBean(RemoteTranscodeExecutor.class);
        });
  }

  @Test
  @DisplayName("Should fail startup when remote transcoding has no identity configuration")
  void shouldFailStartupWhenRemoteTranscodingHasNoIdentityConfiguration() {
    contextRunner
        .withPropertyValues("streaming.remote.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Should start the outbound worker listener when explicitly configured")
  void shouldStartOutboundWorkerListenerWhenExplicitlyConfigured() throws URISyntaxException {
    contextRunner
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

  private String[] remoteProperties() throws URISyntaxException {
    var certificate = resource("server-cert.pem");
    return new String[] {
      "streaming.remote.enabled=true",
      "streaming.remote.port=0",
      "streaming.remote.trust-domain=streamarr.test",
      "streaming.remote.source-namespace-id=" + SOURCE_NAMESPACE_ID,
      "streaming.remote.source-root=" + certificate.getParent(),
      "streaming.remote.certificate=" + certificate,
      "streaming.remote.private-key=" + resource("server-key.fixture"),
      "streaming.remote.trust-bundle=" + resource("ca-cert.pem")
    };
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  @Configuration(proxyBeanMethods = false)
  static class SegmentStoreConfiguration {

    @Bean
    SegmentStore segmentStore() {
      return new FakeSegmentStore();
    }
  }
}
