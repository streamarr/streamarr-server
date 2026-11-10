package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads one report call: the metadata that names the probe attempt, then the bytes of the encoded
 * probe report, one message at a time. At the half-close it checks the report, completes the probe
 * attempt and answers once.
 *
 * <p>The monitor orders gRPC's callbacks with the end of the worker session, the grant of room in
 * the memory budget and the idle timeout, which arrive on other threads. A method that holds the
 * monitor never takes a monitor of the worker connections.
 */
@Slf4j
final class ProbeReportObserver implements StreamObserver<ReportProbeAttemptRequest> {

  private final UUID authenticatedWorkerId;
  private final LiveWorkerConnectionRegistry workerConnections;
  private final ProbeReportLimits limits;
  private final ProbeReportMemoryBudget memoryBudget;
  private final ServerCallStreamObserver<ReportProbeAttemptResponse> call;
  private final Runnable whenClosed;

  // Unset until the first message names an attempt that this call may report.
  private ReportedProbeAttempt attempt;
  private long reportLengthBytes;
  private ByteString received = ByteString.EMPTY;
  // Unset until the metadata states a length that the server may read.
  private ProbeReportMemoryBudget.Reservation room;
  // Runs while the server has asked for a message that has not arrived. Unset otherwise, so the
  // time that the report waits for room never counts.
  private CompletableFuture<Void> idleTimer;
  private boolean closed;

  @Builder
  private ProbeReportObserver(
      @NonNull UUID authenticatedWorkerId,
      @NonNull LiveWorkerConnectionRegistry workerConnections,
      @NonNull ProbeReportLimits limits,
      @NonNull ProbeReportMemoryBudget memoryBudget,
      @NonNull ServerCallStreamObserver<ReportProbeAttemptResponse> call,
      @NonNull Runnable whenClosed) {
    this.authenticatedWorkerId = authenticatedWorkerId;
    this.workerConnections = workerConnections;
    this.limits = limits;
    this.memoryBudget = memoryBudget;
    this.call = call;
    this.whenClosed = whenClosed;
  }

  /** Asks for the first message. gRPC requires this before the service method returns. */
  synchronized void start() {
    call.disableAutoRequest();
    requestMessage();
  }

  private void requestMessage() {
    var timer =
        new CompletableFuture<Void>()
            .orTimeout(limits.idleTimeout().toNanos(), TimeUnit.NANOSECONDS);
    idleTimer = timer;
    // Never run transport callbacks on CompletableFuture's shared timeout thread.
    timer.exceptionally(
        _ -> {
          Thread.ofVirtual().name("probe-report-idle").start(() -> endIfStillIdle(timer));
          return null;
        });
    call.request(1);
  }

  private void stopIdleTimer() {
    if (idleTimer == null) {
      return;
    }

    idleTimer.complete(null);
    idleTimer = null;
  }

  // A message, the half-close or the end of the call may have arrived while this waited for the
  // monitor.
  private synchronized void endIfStillIdle(CompletableFuture<Void> expired) {
    if (closed || idleTimer != expired) {
      return;
    }

    failAttemptForRetry("Worker's probe report call sent no message within the idle timeout");
    end(
        Status.DEADLINE_EXCEEDED.withDescription(
            "Probe report call sent no message within the idle timeout"));
  }

  @Override
  public synchronized void onNext(ReportProbeAttemptRequest request) {
    if (closed) {
      return;
    }

    stopIdleTimer();
    if (attempt == null) {
      admit(request);
      return;
    }

    read(request.getData());
  }

  private void admit(ReportProbeAttemptRequest request) {
    if (!request.hasMetadata()) {
      end(Status.INVALID_ARGUMENT.withDescription("Probe report metadata must come first"));
      return;
    }

    var metadata = request.getMetadata();
    var admission =
        workerConnections.admitProbeReport(
            authenticatedWorkerId, metadata, this::endForEndedWorkerSession);
    switch (admission) {
      case ProbeReportAdmission.NotOwned _ ->
          end(
              Status.PERMISSION_DENIED.withDescription(
                  "Probe report names no probe attempt of the worker's live session"));
      case ProbeReportAdmission.AlreadyReported _ ->
          end(
              Status.ALREADY_EXISTS.withDescription(
                  "Another report call already reports the probe attempt"));
      case ProbeReportAdmission.Admitted(var admitted) -> startReading(metadata, admitted);
    }
  }

  private void startReading(ProbeAttemptReportMetadata metadata, ReportedProbeAttempt admitted) {
    attempt = admitted;
    // The contract's length is unsigned.
    var statedLength = metadata.getReportLengthBytes();
    if (statedLength == 0 || Long.compareUnsigned(statedLength, limits.lengthLimitBytes()) > 0) {
      breakContract(
          "Worker probe report states a length of %s bytes, outside the length limit of %d"
              .formatted(Long.toUnsignedString(statedLength), limits.lengthLimitBytes()));
      return;
    }

    reportLengthBytes = statedLength;
    // The server reads no byte of the report before the whole report has room.
    room = memoryBudget.reserve(statedLength, this::readOnceRoomIsFree);
    if (room.isGrantedAtOnce()) {
      requestMessage();
    }
  }

  private synchronized void readOnceRoomIsFree() {
    if (closed) {
      return;
    }

    requestMessage();
  }

  // A message that is not data holds no bytes either.
  private void read(ByteString data) {
    if (data.isEmpty()) {
      breakContract("Worker probe report message holds no bytes of the report");
      return;
    }

    if ((long) received.size() + data.size() > reportLengthBytes) {
      breakContract("Worker probe report holds more bytes than it stated");
      return;
    }

    received = received.concat(data);
    // Also after the last byte: a further message breaks the contract, and the idle timeout then
    // bounds the wait for the half-close, which needs no request.
    requestMessage();
  }

  @Override
  public synchronized void onCompleted() {
    if (closed) {
      return;
    }

    stopIdleTimer();
    if (attempt == null) {
      end(Status.INVALID_ARGUMENT.withDescription("Probe report metadata is required"));
      return;
    }

    if (received.size() < reportLengthBytes) {
      breakContract("Worker probe report holds fewer bytes than it stated");
      return;
    }

    decode().ifPresent(this::finish);
  }

  private Optional<ProbeAttemptReport> decode() {
    try {
      var report = ProbeAttemptReport.parseFrom(received);
      received = ByteString.EMPTY;
      return Optional.of(report);
    } catch (InvalidProtocolBufferException _) {
      breakContract("Worker probe report does not decode as a probe report");
      return Optional.empty();
    }
  }

  private void finish(ProbeAttemptReport report) {
    switch (ProbeReportContract.check(report, attempt.request(), limits)) {
      case ProbeReportContract.Broken(var rule) -> breakContract(rule);
      case ProbeReportContract.AnotherProbeVersion _ -> {
        attempt.failForAnotherVersion(report.getResult());
        end(
            Status.FAILED_PRECONDITION.withDescription(
                "Probe report names another probe version than the server asked for"));
      }
      case ProbeReportContract.Kept _ -> {
        // The caller of the dispatch maps and stores the outcome on its own thread, after the
        // answer.
        attempt.complete(report);
        answer();
      }
    }
  }

  private void answer() {
    close();
    call.onNext(
        ReportProbeAttemptResponse.newBuilder()
            .setProbeAttemptId(attempt.request().getProbeAttemptId())
            .setAcceptedLengthBytes(reportLengthBytes)
            .build());
    call.onCompleted();
  }

  // The worker cancelled the call, its connection was lost, or it sent a message above the limit
  // of one message. gRPC reports all three alike.
  @Override
  public synchronized void onError(Throwable failure) {
    if (closed) {
      return;
    }

    log.warn("Probe report call from worker {} ended early", authenticatedWorkerId, failure);
    failAttemptForRetry("Worker's probe report call ended early");
    close();
  }

  private void failAttemptForRetry(String reason) {
    if (attempt != null) {
      attempt.failForRetry(reason);
    }
  }

  // The worker connections have already failed the attempt.
  private synchronized void endForEndedWorkerSession() {
    if (closed) {
      return;
    }

    end(Status.PERMISSION_DENIED.withDescription("Worker session ended"));
  }

  private void breakContract(String rule) {
    attempt.failForContractViolation(rule);
    end(Status.INVALID_ARGUMENT.withDescription(rule));
  }

  private void end(Status status) {
    log.warn(
        "Ending probe report call from worker {}: {}",
        authenticatedWorkerId,
        status.getDescription());
    close();
    call.onError(status.asRuntimeException());
  }

  private void close() {
    closed = true;
    stopIdleTimer();
    received = ByteString.EMPTY;
    if (room != null) {
      room.close();
    }

    whenClosed.run();
  }
}
