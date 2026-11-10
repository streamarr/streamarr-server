package com.streamarr.server.fixtures;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.Singular;

/** A gRPC worker session driven by the test: it records server commands and sends replies. */
public final class LoopbackProbeWorker implements AutoCloseable {

  private static final int LARGEST_REPORT_MESSAGE_BYTES = 64 * 1024;

  private final ManagedChannel channel;
  private final StreamObserver<EstablishWorkerSessionRequest> requests;
  private final BlockingQueue<EstablishWorkerSessionResponse> responses =
      new LinkedBlockingQueue<>();
  private final CompletableFuture<Status> terminated = new CompletableFuture<>();
  private final WorkerIdentity identity;
  private final WorkerSessionAccepted accepted;

  /**
   * Registers a worker session that advertises the probe version, the playback slots and the probe
   * capacities. The worker is {@code WORKER_ID} unless the test names another worker. Unset, the
   * playback probe capacity is 2 and the keyframe packet scan capacity is 1, as a worker grants
   * them while it runs no job attempt, and the worker lists no measurement version.
   */
  @Builder
  @SuppressWarnings("java:S107") // The builder names each argument at the call site.
  private LoopbackProbeWorker(
      int port,
      int probeVersion,
      int availableSlots,
      int backgroundProbeCapacity,
      Integer playbackProbeCapacity,
      Integer keyframePacketScanCapacity,
      UUID workerId,
      @Singular List<Integer> measurementVersions)
      throws Exception {
    var worker = WORKER_ID;
    if (workerId != null) {
      worker = workerId;
    }

    var playbackProbes = 2;
    if (playbackProbeCapacity != null) {
      playbackProbes = playbackProbeCapacity;
    }

    var keyframePacketScans = 1;
    if (keyframePacketScanCapacity != null) {
      keyframePacketScans = keyframePacketScanCapacity;
    }

    identity =
        WorkerIdentity.newBuilder()
            .setWorkerId(toProto(worker))
            .setBootId(toProto(UUID.randomUUID()))
            .build();
    channel = plaintextChannelBuilder(port, worker).build();
    requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(
                new StreamObserver<>() {
                  @Override
                  public void onNext(EstablishWorkerSessionResponse value) {
                    responses.add(value);
                  }

                  @Override
                  public void onError(Throwable throwable) {
                    terminated.complete(Status.fromThrowable(throwable));
                  }

                  @Override
                  public void onCompleted() {
                    terminated.complete(Status.OK);
                  }
                });
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setAvailableSlots(availableSlots)
                    .setBackgroundProbeCapacity(backgroundProbeCapacity)
                    .setPlaybackProbeCapacity(playbackProbes)
                    .setKeyframePacketScanCapacity(keyframePacketScans)
                    .setBackgroundProbeCapacityControl(
                        BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_FIXED)
                    .setWorker(identity)
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                            .addProbeVersions(probeVersion)
                            .addAllMeasurementVersions(measurementVersions)))
            .build());
    var first = nextResponse();
    assertThat(first.hasSessionAccepted()).isTrue();
    accepted = first.getSessionAccepted();
  }

  /** The limits and the worker session id that the server stated when it accepted the session. */
  public WorkerSessionAccepted accepted() {
    return accepted;
  }

  public EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
    return nextResponse(Duration.ofSeconds(5));
  }

  public EstablishWorkerSessionResponse nextResponse(Duration timeout) throws InterruptedException {
    var response = responses.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    assertThat(response).isNotNull();
    return response;
  }

  /** Whether every command that the server sent so far was taken by {@link #nextResponse}. */
  public boolean hasNoResponse() {
    return responses.isEmpty();
  }

  public void changeProbeCapacities(int backgroundProbeCapacity, int keyframePacketScanCapacity) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeCapacityChanged(
                ProbeCapacityChanged.newBuilder()
                    .setBackgroundProbeCapacity(backgroundProbeCapacity)
                    .setKeyframePacketScanCapacity(keyframePacketScanCapacity))
            .build());
  }

  public void reply(ProbeAttemptResult result) {
    requests.onNext(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());
  }

  public void sendKeyframePositionsPart(ProbeKeyframePositionsPart part) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder().setProbeKeyframePositionsPart(part).build());
  }

  public void sendStreamMeasurementPart(ProbeStreamMeasurementPart part) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder().setProbeStreamMeasurementPart(part).build());
  }

  /**
   * Sends the probe report in a report call of its own, in messages of at most 64 KiB of the
   * report. The future completes with the server's answer, or exceptionally with the status that
   * ended the report call.
   */
  public CompletableFuture<ReportProbeAttemptResponse> report(ProbeAttemptReport report) {
    return report(report, LARGEST_REPORT_MESSAGE_BYTES);
  }

  /** As {@link #report(ProbeAttemptReport)}, in messages of at most this many bytes of it. */
  public CompletableFuture<ReportProbeAttemptResponse> report(
      ProbeAttemptReport report, int largestMessageBytes) {
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
                    .setWorkerSessionId(accepted.getWorkerSessionId())
                    .setWorker(identity)
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

  public CompletableFuture<Status> terminated() {
    return terminated;
  }

  public void disconnect() {
    channel.shutdownNow();
  }

  @Override
  public void close() throws InterruptedException {
    requests.onCompleted();
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }
}
