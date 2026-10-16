package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
          .withBean(SegmentStore.class, FakeSegmentStore::new)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @ParameterizedTest
  @CsvSource({
    "probe-timeout,0s",
    "probe-timeout,-1s",
    "keyframe-scan-timeout,0s",
    "keyframe-scan-timeout,-1s",
    "probe-cancellation-timeout,0s",
    "probe-cancellation-timeout,-1s",
    "start-report-timeout,0s",
    "start-report-timeout,-1s",
    "keepalive.timeout,0s",
    "keepalive.timeout,-1s"
  })
  @DisplayName("Should reject a nonpositive worker session deadline when starting the listener")
  void shouldRejectNonpositiveWorkerSessionDeadlineWhenStartingListener(
      String property, String value) {
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
  @ValueSource(strings = {"-1s", "0s", "9999ms"})
  @DisplayName("Should reject a keepalive time shorter than ten seconds when starting the listener")
  void shouldRejectKeepaliveTimeShorterThanTenSecondsWhenStartingListener(String time) {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.port=0", "streaming.worker-session.keepalive.time=" + time)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Worker keepalive time must be at least 10 seconds");
            });
  }

  @Test
  @DisplayName(
      "Should start the listener when the keepalive time is ten seconds and its timeout short")
  void shouldStartListenerWhenKeepaliveTimeIsTenSecondsAndItsTimeoutShort() {
    contextRunner
        .withPropertyValues(
            "streaming.worker-session.port=0",
            "streaming.worker-session.keepalive.time=10s",
            "streaming.worker-session.keepalive.timeout=1s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WorkerSessionServer.class).port()).isPositive();
            });
  }

  @Test
  @DisplayName(
      "Should keep the worker keepalive at thirty seconds with a ten second timeout when none is"
          + " configured")
  void shouldKeepWorkerKeepaliveAtThirtySecondsWithTenSecondTimeoutWhenNoneIsConfigured() {
    contextRunner
        .withPropertyValues("streaming.worker-session.port=0")
        .run(
            context ->
                assertThat(context.getBean(WorkerSessionProperties.class).keepalive())
                    .isEqualTo(
                        new WorkerSessionProperties.Keepalive(
                            Duration.ofSeconds(30), Duration.ofSeconds(10))));
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
