package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One report call that a test drives against the service without a socket. As gRPC does, it hands
 * the service a message only after the service asked for one.
 */
final class ReportCall {

  private final Responses responses = new Responses();
  private final StreamObserver<ReportProbeAttemptRequest> requests;

  private ReportCall(WorkerSessionGrpcService service, UUID authenticatedWorkerId)
      throws Exception {
    var context = Context.current();
    if (authenticatedWorkerId != null) {
      context =
          context.withValue(
              WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, authenticatedWorkerId);
    }

    requests = context.call(() -> service.reportProbeAttempt(responses));
  }

  static ReportCall open(WorkerSessionGrpcService service, UUID authenticatedWorkerId)
      throws Exception {
    return new ReportCall(service, authenticatedWorkerId);
  }

  static ReportCall openWithoutWorkerIdentity(WorkerSessionGrpcService service) throws Exception {
    return new ReportCall(service, null);
  }

  /**
   * Sends the whole probe report: its metadata, its bytes in chunks, and the half-close. Sends
   * nothing further once the server has ended the call.
   */
  ReportCall report(ProbeAttemptReportMetadata.Builder metadata, ProbeAttemptReport report)
      throws InterruptedException {
    var bytes = report.toByteString();
    sendMetadata(metadata.setReportLengthBytes(bytes.size()));
    var chunkBytes = Math.max(1, bytes.size() / 3);
    for (var sent = 0; sent < bytes.size() && isOpen(); sent += chunkBytes) {
      sendData(bytes.substring(sent, Math.min(bytes.size(), sent + chunkBytes)));
    }

    if (isOpen()) {
      halfClose();
    }

    return this;
  }

  void sendMetadata(ProbeAttemptReportMetadata.Builder metadata) throws InterruptedException {
    send(ReportProbeAttemptRequest.newBuilder().setMetadata(metadata).build());
  }

  void sendData(ByteString data) throws InterruptedException {
    send(ReportProbeAttemptRequest.newBuilder().setData(data).build());
  }

  void send(ReportProbeAttemptRequest message) throws InterruptedException {
    assertThat(responses.demand.tryAcquire(5, TimeUnit.SECONDS))
        .as("The server must ask for a message before the call delivers one")
        .isTrue();
    requests.onNext(message);
  }

  void halfClose() {
    requests.onCompleted();
  }

  /** Ends the call as gRPC does when the worker cancels it or its connection is lost. */
  void cancel() {
    requests.onError(Status.CANCELLED.withDescription("client cancelled").asRuntimeException());
  }

  /** The messages that the server asked for and the call has not delivered yet. */
  int unreadDemand() {
    return responses.demand.availablePermits();
  }

  /** How many messages the server has asked for since the call opened. */
  int requestedMessages() {
    return responses.requested.get();
  }

  boolean readsOneMessageAtATime() {
    return responses.autoRequestDisabled;
  }

  /** Completes with the server's answer, or exceptionally with the status that ended the call. */
  CompletableFuture<ReportProbeAttemptResponse> answer() {
    return responses.answer;
  }

  boolean isOpen() {
    return !responses.answer.isDone();
  }

  /** The status that ended the call without an answer. */
  Status.Code refusal() {
    assertThat(responses.answer).isCompletedExceptionally();
    return Status.fromThrowable(responses.answer.exceptionNow()).getCode();
  }

  /** Waits for the status that ends the call without an answer. */
  Status.Code awaitRefusal() throws Exception {
    return responses.refusal.get(5, TimeUnit.SECONDS).getCode();
  }

  private static final class Responses
      extends ServerCallStreamObserver<ReportProbeAttemptResponse> {

    private final Semaphore demand = new Semaphore(0);
    private final AtomicInteger requested = new AtomicInteger();
    private final CompletableFuture<ReportProbeAttemptResponse> answer = new CompletableFuture<>();
    private final CompletableFuture<Status> refusal = new CompletableFuture<>();
    private volatile boolean autoRequestDisabled;
    private volatile ReportProbeAttemptResponse answered;

    @Override
    public void request(int count) {
      requested.addAndGet(count);
      demand.release(count);
    }

    @Override
    public void disableAutoRequest() {
      autoRequestDisabled = true;
    }

    @Override
    public void onNext(ReportProbeAttemptResponse response) {
      assertThat(answered).as("The server answers a report call once").isNull();
      answered = response;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(answer).as("The server ends a report call once").isNotDone();
      answer.completeExceptionally(failure);
      refusal.complete(Status.fromThrowable(failure));
    }

    @Override
    public void onCompleted() {
      assertThat(answer).as("The server ends a report call once").isNotDone();
      answer.complete(answered);
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
      // The service reads a cancellation from the request observer.
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
      // The service sends one answer and never waits for readiness.
    }

    @Override
    public void setCompression(String compression) {
      // The call is not compressed.
    }

    @Override
    public void setMessageCompression(boolean enable) {
      // The call is not compressed.
    }

    @Override
    public void disableAutoInboundFlowControl() {
      autoRequestDisabled = true;
    }
  }
}
