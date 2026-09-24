package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Upload Observer Tests")
class SegmentUploadObserverTest {

  @Test
  @DisplayName("Should release the upload slot when the upload stream errors")
  void shouldReleaseUploadSlotWhenUploadStreamErrors() {
    var admission = new SegmentUploadAdmission(1, 1024, 8);
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(UUID.randomUUID())
            .responseObserver(noOpResponseObserver())
            .uploadTicket(admission.tryAdmit(UUID.randomUUID()).orElseThrow())
            .build();
    assertThat(admission.tryAdmit(UUID.randomUUID())).isEmpty();

    observer.onError(new RuntimeException("worker connection reset mid-upload"));

    assertThat(admission.tryAdmit(UUID.randomUUID())).isPresent();
  }

  @Test
  @DisplayName("Should terminate the stream when its admission ticket was reclaimed")
  void shouldTerminateStreamWhenItsAdmissionTicketWasReclaimed() {
    var error = new AtomicReference<Throwable>();
    var ticket = new SegmentUploadAdmission(1, 1024, 8).tryAdmit(UUID.randomUUID()).orElseThrow();
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(UUID.randomUUID())
            .workerConnections(LiveWorkerConnectionRegistryFixture.defaultRegistry())
            .responseObserver(errorCapturingResponseObserver(error))
            .uploadTicket(ticket)
            .build();

    // The admission reclaimed this upload's capacity after it exceeded the maximum upload age.
    ticket.close();
    observer.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(SegmentUploadMetadata.getDefaultInstance())
            .build());

    // The stream must end rather than keep buffering bytes the byte budget no longer accounts for.
    assertThat(error.get()).isNotNull();
    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
  }

  private static StreamObserver<UploadSegmentResponse> errorCapturingResponseObserver(
      AtomicReference<Throwable> error) {
    return new StreamObserver<>() {
      @Override
      public void onNext(UploadSegmentResponse value) {
        // This observer records only terminal errors.
      }

      @Override
      public void onError(Throwable throwable) {
        error.set(throwable);
      }

      @Override
      public void onCompleted() {
        // This observer records only terminal errors.
      }
    };
  }

  private static StreamObserver<UploadSegmentResponse> noOpResponseObserver() {
    return new StreamObserver<>() {
      @Override
      public void onNext(UploadSegmentResponse value) {
        // This observer ignores responses.
      }

      @Override
      public void onError(Throwable throwable) {
        // This observer ignores responses.
      }

      @Override
      public void onCompleted() {
        // This observer ignores responses.
      }
    };
  }
}
