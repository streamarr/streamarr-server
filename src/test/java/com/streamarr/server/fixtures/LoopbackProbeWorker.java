package com.streamarr.server.fixtures;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.Builder;

/** A gRPC worker session driven by the test: it records server commands and sends replies. */
public final class LoopbackProbeWorker implements AutoCloseable {

  private final ManagedChannel channel;
  private final StreamObserver<EstablishWorkerSessionRequest> requests;
  private final BlockingQueue<EstablishWorkerSessionResponse> responses =
      new LinkedBlockingQueue<>();
  private final CompletableFuture<Status> terminated = new CompletableFuture<>();

  @Builder
  private LoopbackProbeWorker(int port, int probeVersion, int availableSlots) throws Exception {
    channel = plaintextChannelBuilder(port, WORKER_ID).build();
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
                    .setWorker(
                        WorkerIdentity.newBuilder()
                            .setWorkerId(toProto(WORKER_ID))
                            .setBootId(toProto(UUID.randomUUID())))
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                            .addProbeVersions(probeVersion)))
            .build());
    assertThat(nextResponse().hasSessionAccepted()).isTrue();
  }

  public EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
    return nextResponse(Duration.ofSeconds(5));
  }

  public EstablishWorkerSessionResponse nextResponse(Duration timeout) throws InterruptedException {
    var response = responses.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    assertThat(response).isNotNull();
    return response;
  }

  public void reply(ProbeAttemptResult result) {
    requests.onNext(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());
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
