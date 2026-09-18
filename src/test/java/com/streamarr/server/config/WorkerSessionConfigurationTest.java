package com.streamarr.server.config;

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

  @ParameterizedTest
  @CsvSource({
    "probe-timeout,0s",
    "probe-timeout,-1s",
    "probe-cancellation-timeout,0s",
    "probe-cancellation-timeout,-1s"
  })
  @DisplayName("Should reject a nonpositive probe deadline when starting the listener")
  void shouldRejectNonpositiveProbeDeadlineWhenStartingListener(String property, String value) {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.port=0", "streaming.worker-session." + property + "=" + value)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("must be positive");
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 65536})
  @DisplayName("Should reject an invalid port when starting the worker listener")
  void shouldRejectInvalidPortWhenStartingWorkerListener(int port) {
    contextRunner
        .withPropertyValues("streaming.worker-session.port=" + port)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Worker session port must be between 0 and 65535");
            });
  }

  @Test
  @DisplayName("Should select remote execution when no TLS configuration is supplied")
  void shouldSelectRemoteExecutionWhenNoTlsConfigurationIsSupplied() {
    contextRunner
        .withUserConfiguration(RemoteTranscodeConfiguration.class)
        .withPropertyValues(
            "streaming.worker-session.port=0",
            "streaming.remote.source-namespace-id=cccccccc-cccc-cccc-cccc-cccccccccccc",
            "streaming.remote.source-root=/media")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WorkerSessionServer.class);
              assertThat(context.getBean(TranscodeExecutor.class))
                  .isInstanceOf(RemoteTranscodeExecutor.class);
              assertThat(context.getBean(WorkerSessionServer.class).port()).isPositive();
            });
  }
}
