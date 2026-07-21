package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
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
              .uploadTicket(new SegmentUploadAdmission(1, 1024).tryAdmit().orElseThrow())
              .build();

      observer.onError(new RuntimeException("worker connection reset mid-upload"));

      assertThat(appender.list)
          .as("onError must log the transport failure reason")
          .anySatisfy(event -> assertThat(event.getThrowableProxy()).isNotNull());
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static StreamObserver<UploadSegmentResponse> noOpResponseObserver() {
    return new StreamObserver<>() {
      @Override
      public void onNext(UploadSegmentResponse value) {
        // no-op for test fake
      }

      @Override
      public void onError(Throwable throwable) {
        // no-op for test fake
      }

      @Override
      public void onCompleted() {
        // no-op for test fake
      }
    };
  }
}
