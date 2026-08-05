package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Session Registration Identity Tests")
class WorkerSessionRegistrationIdentityTest {

  @Test
  @DisplayName(
      "Should reject registration with UNAUTHENTICATED when no worker identity is in context")
  void shouldRejectRegistrationWithUnauthenticatedWhenNoWorkerIdentityInContext() {
    var service =
        new WorkerSessionGrpcService(new LiveWorkerConnectionRegistry(), new FakeSegmentStore());
    var responses = new RecordingResponseObserver();

    // Deliberately no AUTHENTICATED_WORKER_ID in the current context: the server was wired
    // without the identity interceptor — the exact misconfiguration uploadSegment guards.
    var session = service.establishWorkerSession(responses);
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setWorker(
                        WorkerIdentity.newBuilder()
                            .setWorkerId(toProto(UUID.randomUUID()))
                            .setBootId(toProto(UUID.randomUUID())))
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(toProto(UUID.randomUUID())))
                    .setAvailableSlots(1))
            .build());

    assertThat(responses.error)
        .as("registration without an authenticated identity must fail closed, like uploadSegment")
        .isNotNull();
    assertThat(Status.fromThrowable(responses.error).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  private static final class RecordingResponseObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private Throwable error;

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      // This failure-path test records only the terminal error.
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      // This failure-path test records only the terminal error.
    }
  }
}
