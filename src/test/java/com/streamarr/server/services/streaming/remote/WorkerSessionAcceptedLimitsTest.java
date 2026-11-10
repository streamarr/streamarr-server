package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Session Accepted Limits Tests")
class WorkerSessionAcceptedLimitsTest {

  @Test
  @DisplayName(
      "Should state its default probe report limits when it accepts a worker session and no limit"
          + " is configured")
  void shouldStateItsDefaultProbeReportLimitsWhenItAcceptsWorkerSessionAndNoLimitIsConfigured()
      throws Exception {
    var accepted = acceptedBy(WorkerSessionServerConfiguration.builder().build());

    assertThat(accepted.getProbeReportLengthLimitBytes()).isEqualTo(16L * 1024 * 1024);
    assertThat(accepted.getKeyframeLimit()).isEqualTo(150_000);
    assertThat(accepted.getMeasuredKeyframeIntervalLimit()).isEqualTo(1_000_000);
  }

  @Test
  @DisplayName(
      "Should state the probe report limits that it enforces when a ServerAdmin configures them")
  void shouldStateProbeReportLimitsThatItEnforcesWhenServerAdminConfiguresThem() throws Exception {
    var limits =
        ProbeReportLimits.builder()
            .keyframeLimit(7)
            .measuredKeyframeIntervalLimit(9)
            .lengthLimitBytes(262_144)
            .build();

    var accepted =
        acceptedBy(WorkerSessionServerConfiguration.builder().probeReportLimits(limits).build());

    assertThat(accepted.getKeyframeLimit()).isEqualTo(7);
    assertThat(accepted.getMeasuredKeyframeIntervalLimit()).isEqualTo(9);
    assertThat(accepted.getProbeReportLengthLimitBytes()).isEqualTo(262_144);
  }

  @Test
  @DisplayName(
      "Should leave one connection's calls to the worker session, the segment uploads and the"
          + " report calls when it states their limits")
  void
      shouldLeaveOneConnectionsCallsToWorkerSessionSegmentUploadsAndReportCallsWhenItStatesTheirLimits()
          throws Exception {
    var callsOnOneConnection = 33;

    var accepted = acceptedBy(WorkerSessionServerConfiguration.builder().build());

    assertThat(accepted.getProbeReportLimit()).isEqualTo(24);
    assertThat(1 + accepted.getSegmentUploadLimit() + accepted.getProbeReportLimit())
        .isEqualTo(callsOnOneConnection);
  }

  private static WorkerSessionAccepted acceptedBy(WorkerSessionServerConfiguration configuration)
      throws Exception {
    var workerId = UUID.randomUUID();
    var service =
        new WorkerSessionGrpcService(
            LiveWorkerConnectionRegistryFixture.registryWith(configuration),
            new FakeSegmentStore());
    var responses = new ArrayList<EstablishWorkerSessionResponse>();
    var session =
        Context.current()
            .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
            .call(() -> service.establishWorkerSession(new CollectingResponses(responses)));

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration(workerId)).build());

    return responses.getFirst().getSessionAccepted();
  }

  private static WorkerRegistration registration(UUID workerId) {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(workerId))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(UUID.randomUUID())))
        .setAvailableSlots(1)
        .build();
  }

  private record CollectingResponses(List<EstablishWorkerSessionResponse> responses)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      responses.add(response);
    }

    @Override
    public void onError(Throwable failure) {
      throw new AssertionError("The worker session should stay open", failure);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("The worker session should stay open");
    }
  }
}
