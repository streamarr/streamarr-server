package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Worker Session Unknown Result Logging Tests")
class WorkerSessionUnknownResultLoggingTest {

  @Test
  @DisplayName("Should log when a worker reports a terminal result for an unknown job attempt")
  void shouldLogWhenWorkerReportsTerminalResultForUnknownJobAttempt() throws Exception {
    var workerId = UUID.randomUUID();
    var service =
        new WorkerSessionGrpcService(new LiveWorkerConnectionRegistry(), new FakeSegmentStore());
    var session =
        Context.current()
            .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
            .call(() -> service.establishWorkerSession(new IgnoringObserver()));
    session.onNext(registration(workerId));

    var unknownCompletedAttemptId = UUID.randomUUID();
    var unknownStoppedAttemptId = UUID.randomUUID();
    var events = capture();
    try {
      session.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setJobAttemptCompleted(
                  JobAttemptCompleted.newBuilder()
                      .setJobAttemptId(toProto(unknownCompletedAttemptId)))
              .build());
      session.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setJobAttemptStopped(
                  JobAttemptStopped.newBuilder().setJobAttemptId(toProto(unknownStoppedAttemptId)))
              .build());
    } finally {
      serviceLogger().detachAppender(events);
    }

    assertThat(events.list)
        .as("terminal results for job attempts the control plane does not know must be logged")
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(message -> assertThat(message).contains(unknownCompletedAttemptId.toString()))
        .anySatisfy(message -> assertThat(message).contains(unknownStoppedAttemptId.toString()));
  }

  private static EstablishWorkerSessionRequest registration(UUID workerId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setRegistration(
            WorkerRegistration.newBuilder()
                .setWorker(
                    WorkerIdentity.newBuilder()
                        .setWorkerId(toProto(workerId))
                        .setBootId(toProto(UUID.randomUUID())))
                .setCapabilities(
                    WorkerCapabilities.newBuilder()
                        .addSourceNamespaceIds(toProto(UUID.randomUUID())))
                .setAvailableSlots(1))
        .build();
  }

  private static ListAppender<ILoggingEvent> capture() {
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    serviceLogger().addAppender(appender);
    return appender;
  }

  private static Logger serviceLogger() {
    return (Logger) LoggerFactory.getLogger(WorkerSessionGrpcService.class);
  }

  private static final class IgnoringObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onCompleted() {}
  }
}
