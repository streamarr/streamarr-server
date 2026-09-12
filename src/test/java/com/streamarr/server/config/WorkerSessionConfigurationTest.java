package com.streamarr.server.config;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsResource;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Worker Session Configuration Tests")
class WorkerSessionConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(WorkerSessionConfiguration.class)
          .withBean(SegmentStore.class, FakeSegmentStore::new);

  @Test
  @DisplayName("Should refuse startup when neither worker session listener is enabled")
  void shouldRefuseStartupWhenNeitherWorkerSessionListenerIsEnabled() {
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("At least one worker session listener must be enabled");
        });
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 65536})
  @DisplayName("Should reject an invalid port when the loopback listener is enabled")
  void shouldRejectInvalidPortWhenLoopbackListenerIsEnabled(int port) {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.loopback.enabled=true",
            "streaming.worker-session.loopback.port=" + port)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Loopback worker session port must be between 0 and 65535");
            });
  }

  @Test
  @DisplayName("Should require certificate configuration when mutual TLS is enabled alone")
  void shouldRequireCertificateConfigurationWhenMutualTlsIsEnabledAlone() {
    contextRunner
        .withPropertyValues("streaming.worker-session.mutual-tls.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Mutual TLS certificate is required");
            });
  }

  @Test
  @DisplayName("Should start loopback when its listener is enabled alone")
  void shouldStartLoopbackWhenItsListenerIsEnabledAlone() {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.loopback.enabled=true",
            "streaming.worker-session.loopback.port=0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WorkerSessionServer.class).loopbackPort()).isPositive();
              assertThat(context).doesNotHaveBean(TranscodeExecutor.class);
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should start mutual TLS independently when its listener is enabled")
  void shouldStartMutualTlsIndependentlyWhenItsListenerIsEnabled(boolean loopbackEnabled)
      throws Exception {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.loopback.enabled=" + loopbackEnabled,
            "streaming.worker-session.loopback.port=0",
            "streaming.worker-session.mutual-tls.enabled=true",
            "streaming.worker-session.mutual-tls.port=0",
            "streaming.worker-session.mutual-tls.trust-domain=streamarr.test",
            "streaming.worker-session.mutual-tls.certificate=" + tlsResource("server-cert.pem"),
            "streaming.worker-session.mutual-tls.private-key=" + tlsResource("server-key.fixture"),
            "streaming.worker-session.mutual-tls.trust-bundle=" + tlsResource("ca-cert.pem"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var server = context.getBean(WorkerSessionServer.class);
              assertThat(server.port()).isPositive();
              if (loopbackEnabled) {
                assertThat(server.loopbackPort()).isPositive().isNotEqualTo(server.port());
              }
            });
  }

  @ParameterizedTest
  @CsvSource({"certificate,certificate", "private-key,private key", "trust-bundle,trust bundle"})
  @DisplayName("Should explain missing TLS material when mutual TLS is enabled")
  void shouldExplainMissingTlsMaterialWhenMutualTlsIsEnabled(String property, String description)
      throws Exception {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.mutual-tls.enabled=true",
            "streaming.worker-session.mutual-tls.port=0",
            "streaming.worker-session.mutual-tls.trust-domain=streamarr.test",
            "streaming.worker-session.mutual-tls.certificate=" + tlsResource("server-cert.pem"),
            "streaming.worker-session.mutual-tls.private-key=" + tlsResource("server-key.fixture"),
            "streaming.worker-session.mutual-tls.trust-bundle=" + tlsResource("ca-cert.pem"))
        .withPropertyValues("streaming.worker-session.mutual-tls." + property + "= ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Mutual TLS " + description + " is required");
            });
  }

  @Test
  @DisplayName("Should select remote execution without TLS when loopback is enabled")
  void shouldSelectRemoteExecutionWithoutTlsWhenLoopbackIsEnabled() {
    contextRunner
        .withUserConfiguration(RemoteTranscodeConfiguration.class)
        .withPropertyValues(
            "streaming.worker-session.loopback.enabled=true",
            "streaming.worker-session.loopback.port=0",
            "streaming.remote.source-namespace-id=cccccccc-cccc-cccc-cccc-cccccccccccc",
            "streaming.remote.source-root=/media")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WorkerSessionServer.class);
              assertThat(context.getBean(TranscodeExecutor.class))
                  .isInstanceOf(RemoteTranscodeExecutor.class);
              assertThat(context.getBean(WorkerSessionServer.class).loopbackPort()).isPositive();
            });
  }
}
