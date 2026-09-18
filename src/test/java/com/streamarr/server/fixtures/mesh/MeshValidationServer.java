package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MeshValidationServer {

  static final UUID SOURCE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private MeshValidationServer() {}

  public static void main() throws Exception {
    var configuration =
        WorkerSessionServerConfiguration.builder().address("0.0.0.0").port(9090).build();
    var segments = new MeshSegmentStore();
    try (var server = new WorkerSessionServer(configuration, segments);
        var httpThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      var http = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
      http.setExecutor(httpThreads);
      http.createContext("/health", exchange -> respond(exchange, "HTTP_ACCESS_OK"));
      http.createContext("/probe", exchange -> probe(server, exchange));
      http.createContext("/media/", new MeshMediaHandler(server, segments));
      http.start();
      try {
        new CountDownLatch(1).await();
      } finally {
        http.stop(0);
      }
    }
  }

  private static void probe(WorkerSessionServer server, HttpExchange exchange) throws IOException {
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_ID))
                    .setRelativeKey("mesh-fixture.mkv"))
            .build();
    try {
      var result = server.dispatchProbe(request).orElseThrow().get(10, TimeUnit.SECONDS);
      if (!result.getProbeAttemptId().equals(request.getProbeAttemptId())
          || result.getProbeVersion() != request.getProbeVersion()
          || !result.getMedia().getContainer().getFormat().equals("mesh-fixture")) {
        throw new IllegalStateException("The worker did not return the correlated fixture result");
      }

      respond(exchange, "PROBE_COMPLETED");
    } catch (Exception failure) {
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
      throw new IOException("Mesh probe exchange failed", failure);
    }
  }

  private static void respond(HttpExchange exchange, String message) throws IOException {
    var body = message.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
