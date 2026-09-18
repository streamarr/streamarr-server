package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.ProbeRequest;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Session Server Tests")
class WorkerSessionServerTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  @DisplayName("Should reject listener configuration when its address is missing")
  void shouldRejectListenerConfigurationWhenItsAddressIsMissing(String address) {
    var defaults = WorkerSessionServerConfiguration.builder().port(0).build();
    var probeTimeout = defaults.probeTimeout();
    var cancellationTimeout = defaults.probeCancellationTimeout();

    assertThatThrownBy(
            () ->
                new WorkerSessionServerConfiguration(address, 0, probeTimeout, cancellationTimeout))
        .hasMessageContaining("address");
  }

  @Test
  @DisplayName("Should reject a probe dispatch when the worker session server has not started")
  void shouldRejectProbeDispatchWhenWorkerSessionServerHasNotStarted() {
    var server = unstartedServer();
    var request = ProbeRequest.getDefaultInstance();

    assertNotStarted(() -> server.dispatchProbe(request));
  }

  private WorkerSessionServer unstartedServer() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    return new WorkerSessionServer(configuration, new FakeSegmentStore());
  }

  @Test
  @DisplayName("Should fail fast for every capability when the server has not been started")
  void shouldFailFastForEveryCapabilityWhenTheServerHasNotBeenStarted() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();

    assertNotStarted(() -> server.eligibleWorkers(sourceNamespaceId));
    assertNotStarted(() -> server.hasConnectedWorker(sourceNamespaceId));
    assertNotStarted(() -> server.stopVariant(UUID.randomUUID(), "720p"));
  }

  @Test
  @DisplayName("Should require a started server when dispatch capability is demanded")
  void shouldRequireAStartedServerWhenDispatchCapabilityIsDemanded() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();

    assertThatThrownBy(() -> server.availableSlots(sourceNamespaceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not started");
  }

  private static void assertNotStarted(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not started");
  }

  @Test
  @DisplayName("Should close idempotently when the server was never started")
  void shouldCloseIdempotentlyWhenTheServerWasNeverStarted() {
    var server = unstartedServer();

    assertThatNoException()
        .isThrownBy(
            () -> {
              server.close();
              server.close();
            });
  }
}
