package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Segment Upload Observer Tests")
class SegmentUploadObserverTest {

  @Test
  @DisplayName("Should log the transport failure reason when the upload stream errors")
  void shouldLogFailureReasonWhenUploadStreamErrors() {
    var logger = (Logger) LoggerFactory.getLogger(SegmentUploadObserver.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      var observer =
          SegmentUploadObserver.builder()
              .authenticatedWorkerId(UUID.randomUUID())
              .responseObserver(noOpResponseObserver())
              .uploadTicket(
                  new SegmentUploadAdmission(1, 1024, 8).tryAdmit(UUID.randomUUID()).orElseThrow())
              .build();

      observer.onError(new RuntimeException("worker connection reset mid-upload"));

      assertThat(appender.list)
          .as("onError must log the transport failure reason")
          .anySatisfy(event -> assertThat(event.getThrowableProxy()).isNotNull());
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("Should terminate the stream when its admission ticket was reclaimed")
  void shouldTerminateStreamWhenItsAdmissionTicketWasReclaimed() {
    var error = new AtomicReference<Throwable>();
    var ticket = new SegmentUploadAdmission(1, 1024, 8).tryAdmit(UUID.randomUUID()).orElseThrow();
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(UUID.randomUUID())
            .workerConnections(new LiveWorkerConnectionRegistry())
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
        // No response is expected in this logging test.
      }

      @Override
      public void onError(Throwable throwable) {
        // No response is expected in this logging test.
      }

      @Override
      public void onCompleted() {
        // No response is expected in this logging test.
      }
    };
  }
}
