package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerIdentity;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.NonNull;

/**
 * Sends the probe reports of one worker session as a worker does: each in a report call of its own,
 * on the channel that carries the worker session.
 */
@Builder
public final class WorkerProbeReporter {

  private static final int LARGEST_REPORT_MESSAGE_BYTES = 64 * 1024;

  @NonNull private final Channel channel;
  @NonNull private final Uuid workerSessionId;
  @NonNull private final WorkerIdentity worker;

  /** Sends a probe report that holds the result alone. */
  public CompletableFuture<ReportProbeAttemptResponse> report(ProbeAttemptResult result) {
    return report(ProbeAttemptReport.newBuilder().setResult(result).build());
  }

  /**
   * Sends the probe report in messages of at most 64 KiB of the report. The future completes with
   * the server's answer, or exceptionally with the status that ended the report call.
   */
  public CompletableFuture<ReportProbeAttemptResponse> report(ProbeAttemptReport report) {
    return report(report, LARGEST_REPORT_MESSAGE_BYTES);
  }

  /** As {@link #report(ProbeAttemptReport)}, in messages of at most this many bytes of it. */
  public CompletableFuture<ReportProbeAttemptResponse> report(
      ProbeAttemptReport report, int largestMessageBytes) {
    assertThat(largestMessageBytes).as("The report message size").isPositive();
    var answer = new CompletableFuture<ReportProbeAttemptResponse>();
    var call =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .reportProbeAttempt(
                new StreamObserver<>() {
                  @Override
                  public void onNext(ReportProbeAttemptResponse response) {
                    answer.complete(response);
                  }

                  @Override
                  public void onError(Throwable failure) {
                    answer.completeExceptionally(failure);
                  }

                  @Override
                  public void onCompleted() {
                    // The answer has already completed the future.
                  }
                });
    var bytes = report.toByteString();
    call.onNext(
        ReportProbeAttemptRequest.newBuilder()
            .setMetadata(
                ProbeAttemptReportMetadata.newBuilder()
                    .setWorkerSessionId(workerSessionId)
                    .setWorker(worker)
                    .setProbeAttemptId(report.getResult().getProbeAttemptId())
                    .setReportLengthBytes(bytes.size()))
            .build());
    for (var sent = 0; sent < bytes.size(); sent += largestMessageBytes) {
      var end = Math.min(bytes.size(), sent + largestMessageBytes);
      call.onNext(
          ReportProbeAttemptRequest.newBuilder().setData(bytes.substring(sent, end)).build());
    }

    call.onCompleted();
    return answer;
  }
}
