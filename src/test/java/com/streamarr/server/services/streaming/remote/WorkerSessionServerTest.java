package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.VariantJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    var keyframeScanTimeout = defaults.keyframeScanTimeout();
    var cancellationTimeout = defaults.probeCancellationTimeout();
    var startReportTimeout = defaults.startReportTimeout();
    var keepalive = defaults.keepalive();

    assertThatThrownBy(
            () ->
                new WorkerSessionServerConfiguration(
                    address,
                    0,
                    probeTimeout,
                    keyframeScanTimeout,
                    cancellationTimeout,
                    startReportTimeout,
                    keepalive))
        .hasMessageContaining("address");
  }

  @Test
  @DisplayName("Should reject a worker session server when its configuration is missing")
  void shouldRejectWorkerSessionServerWhenItsConfigurationIsMissing() {
    var segmentStore = new FakeSegmentStore();
    var meterRegistry = new SimpleMeterRegistry();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                WorkerSessionServer.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(null)
                    .segmentStore(segmentStore)
                    .meterRegistry(meterRegistry)
                    .build())
        .withMessageContaining("configuration");
  }

  @Test
  @DisplayName("Should reject a worker session server when its segment store is missing")
  void shouldRejectWorkerSessionServerWhenItsSegmentStoreIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    var meterRegistry = new SimpleMeterRegistry();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                WorkerSessionServer.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(configuration)
                    .segmentStore(null)
                    .meterRegistry(meterRegistry)
                    .build())
        .withMessageContaining("segmentStore");
  }

  @Test
  @DisplayName("Should reject a worker session server when its meter registry is missing")
  void shouldRejectWorkerSessionServerWhenItsMeterRegistryIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    var segmentStore = new FakeSegmentStore();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                WorkerSessionServer.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(configuration)
                    .segmentStore(segmentStore)
                    .meterRegistry(null)
                    .build())
        .withMessageContaining("meterRegistry");
  }

  @Test
  @DisplayName("Should reject a probe dispatch when the worker session server has not started")
  void shouldRejectProbeDispatchWhenWorkerSessionServerHasNotStarted() {
    var server = unstartedServer();
    var request = ProbeRequest.getDefaultInstance();

    assertNotStarted(() -> server.dispatchProbe(request));
  }

  @Test
  @DisplayName("Should report no probe capacity when the worker session server has not started")
  void shouldReportNoProbeCapacityWhenTheWorkerSessionServerHasNotStarted() {
    var server = unstartedServer();

    assertThat(server.probeCapacity(UUID.randomUUID(), ProbeVersion.CURRENT))
        .isEqualTo(ProbeCapacity.NONE);
  }

  @Test
  @DisplayName("Should reject a worker session server when its event publisher is missing")
  void shouldRejectWorkerSessionServerWhenItsEventPublisherIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    var segmentStore = new FakeSegmentStore();
    var meters = new SimpleMeterRegistry();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                WorkerSessionServer.builder()
                    .configuration(configuration)
                    .segmentStore(segmentStore)
                    .meterRegistry(meters)
                    .build())
        .withMessageContaining("eventPublisher");
  }

  private WorkerSessionServer unstartedServer() {
    var configuration = WorkerSessionServerConfiguration.builder().port(0).build();
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(configuration)
        .segmentStore(new FakeSegmentStore())
        .meterRegistry(new SimpleMeterRegistry())
        .build();
  }

  @Test
  @DisplayName("Should fail fast for every capability when the server has not been started")
  void shouldFailFastForEveryCapabilityWhenTheServerHasNotBeenStarted() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();
    var job = VariantJob.getDefaultInstance();
    var pinnedEncoder = WorkerVerifiedEncoderFixture.libx264();

    assertNotStarted(() -> server.eligibleWorkers(sourceNamespaceId, streamCopyDecision()));
    assertNotStarted(() -> server.eligibleWorkers(sourceNamespaceId, pinnedEncoder));
    assertNotStarted(() -> server.dispatch(job, pinnedEncoder));
    assertNotStarted(() -> server.hasConnectedWorker(sourceNamespaceId));
    assertNotStarted(() -> server.hardwareEncodableVideoCodecFamilies(sourceNamespaceId));
    assertNotStarted(() -> server.stopVariant(UUID.randomUUID(), "720p"));
    assertNotStarted(() -> server.terminalFailure(UUID.randomUUID()));
    assertNotStarted(() -> server.destroyStreamSession(UUID.randomUUID()));
  }

  @Test
  @DisplayName("Should reject a pinned dispatch when its pinned encoder is missing")
  void shouldRejectPinnedDispatchWhenItsPinnedEncoderIsMissing() {
    var server = unstartedServer();
    var job = VariantJob.getDefaultInstance();

    assertThatNullPointerException()
        .isThrownBy(() -> server.dispatch(job, null))
        .withMessageContaining("pinnedEncoder");
  }

  @Test
  @DisplayName("Should reject a pinned targeted dispatch when its pinned encoder is missing")
  void shouldRejectPinnedTargetedDispatchWhenItsPinnedEncoderIsMissing() {
    var server = unstartedServer();
    var target = new ExecutionTargetId("worker-session");
    var job = VariantJob.getDefaultInstance();

    assertThatNullPointerException()
        .isThrownBy(() -> server.dispatchTo(target, job, null))
        .withMessageContaining("pinnedEncoder");
  }

  @Test
  @DisplayName("Should reject a pinned eligible-worker lookup when its pinned encoder is missing")
  void shouldRejectPinnedEligibleWorkerLookupWhenItsPinnedEncoderIsMissing() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();

    assertThatNullPointerException()
        .isThrownBy(() -> server.eligibleWorkers(sourceNamespaceId, (WorkerVerifiedEncoder) null))
        .withMessageContaining("pinnedEncoder");
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
