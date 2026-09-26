package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.services.streaming.remote.protocol.WorkerIdentityMetadata;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class MeshValidationClient {

  private MeshValidationClient() {}

  public static void main(String[] args) throws Exception {
    var mode = args[0];
    var host = args[1];
    if (mode.equals("http-forbidden")) {
      requireHttpForbidden(args[1]);
      return;
    }

    if (mode.equals("tls-required")) {
      requireHttpTls(host);
      return;
    }

    if (mode.equals("http")) {
      requireHttpResponse("http://" + host + ":8080/health", "HTTP_ACCESS_OK");
      return;
    }

    if (mode.equals("media")) {
      requireHttpResponse("http://" + host + ":8080/media/probe", "MEDIA_PROBE_COMPLETED");
      downloadSegment(host);
      return;
    }

    if (mode.equals("worker-health")) {
      requireHttpResponse(
          "http://" + host + ":9091/actuator/health/liveness", "{\"status\":\"UP\"}");
      requireHttpResponse(
          "http://" + host + ":9091/actuator/health/readiness", "{\"status\":\"UP\"}");
      return;
    }

    var workerId = UUID.randomUUID();
    var headers = new Metadata();
    headers.put(WorkerIdentityMetadata.WORKER_ID, workerId.toString());
    var channel =
        NettyChannelBuilder.forAddress(host, 9090)
            .usePlaintext()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
            .build();
    try {
      var session = new Session();
      session.requests =
          TranscodeWorkerServiceGrpc.newStub(channel).establishWorkerSession(session);
      session.register(workerId);
      if (mode.equals("registered")) {
        session.accepted.get(10, TimeUnit.SECONDS);
        System.out.println("WORKER_REGISTERED");
        return;
      }

      if (mode.equals("allowed")) {
        session.accepted.get(10, TimeUnit.SECONDS);
        requireHttpResponse("http://" + host + ":8080/probe", "PROBE_COMPLETED");
        return;
      }

      requireDenial(session, Status.Code.valueOf(mode));
    } finally {
      channel.shutdownNow();
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Worker channel did not terminate");
      }
    }
  }

  private static void downloadSegment(String host) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://" + host + ":8080/media/segment"))
              .timeout(Duration.ofSeconds(45))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body().length == 0) {
        throw new IllegalStateException("Media segment exchange failed: " + response.statusCode());
      }

      Files.write(Path.of("/tmp/mesh-segment.mp4"), response.body());
      System.out.println("MEDIA_SEGMENT_RECEIVED");
    }
  }

  private static void requireDenial(Session session, Status.Code expected) throws Exception {
    try {
      session.accepted.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      var actual = Status.fromThrowable(failure.getCause()).getCode();
      if (actual != expected) {
        throw new IllegalStateException(
            "Expected " + expected + " but received " + actual, failure);
      }

      System.out.println("DENIED_" + actual);
      return;
    }

    throw new IllegalStateException("Worker registration unexpectedly succeeded");
  }

  private static void requireHttpResponse(String address, String expected) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(15)).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || !response.body().equals(expected)) {
        throw new IllegalStateException("HTTP exchange failed: " + response.statusCode());
      }

      System.out.println(expected);
    }
  }

  private static void requireHttpForbidden(String address) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(15)).build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 403) {
        throw new IllegalStateException("Expected HTTP 403 but received " + response.statusCode());
      }

      System.out.println("HTTP_FORBIDDEN");
    }
  }

  private static void requireHttpTls(String address) throws Exception {
    try {
      requireHttpResponse(address, "HTTP_ACCESS_OK");
    } catch (ConnectException | HttpTimeoutException failure) {
      throw new IllegalStateException(
          "Mesh target unavailable. TLS enforcement was not verified", failure);
    } catch (IOException _) {
      System.out.println("HTTP_PLAINTEXT_REJECTED");
      return;
    }

    throw new IllegalStateException("Plaintext HTTP bypassed the existing strict mesh policy");
  }

  private static final class Session implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CompletableFuture<Void> accepted = new CompletableFuture<>();
    private StreamObserver<EstablishWorkerSessionRequest> requests;

    private void register(UUID workerId) {
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(
                          WorkerIdentity.newBuilder()
                              .setWorkerId(toProto(workerId))
                              .setBootId(toProto(UUID.randomUUID())))
                      .setAvailableSlots(1)
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(MeshValidationServer.SOURCE_ID))
                              .addProbeVersions(1)))
              .build());
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        accepted.complete(null);
        return;
      }

      if (response.hasStartProbe()) {
        var request = response.getStartProbe().getRequest();
        requests.onNext(
            EstablishWorkerSessionRequest.newBuilder()
                .setProbeResult(
                    ProbeAttemptResult.newBuilder()
                        .setProbeAttemptId(request.getProbeAttemptId())
                        .setProbeVersion(request.getProbeVersion())
                        .setMedia(
                            ProbeMediaInfo.newBuilder()
                                .setContainer(
                                    ProbeContainerInfo.newBuilder().setFormat("mesh-fixture"))))
                .build());
      }
    }

    @Override
    public void onError(Throwable failure) {
      accepted.completeExceptionally(failure);
    }

    @Override
    public void onCompleted() {
      accepted.completeExceptionally(new IllegalStateException("Worker session ended"));
    }
  }
}
