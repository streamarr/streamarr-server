package com.streamarr.transcode.worker;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class WorkerHealthServer implements AutoCloseable {

  private static final String READINESS = "readiness";

  private final HealthStatusManager health = new HealthStatusManager();
  private final Server server;

  WorkerHealthServer(int port) {
    health.setStatus("liveness", ServingStatus.SERVING);
    health.setStatus(READINESS, ServingStatus.NOT_SERVING);
    server = NettyServerBuilder.forPort(port).addService(health.getHealthService()).build();
  }

  void start() throws IOException {
    server.start();
  }

  int port() {
    return server.getPort();
  }

  void sessionAccepted() {
    health.setStatus(READINESS, ServingStatus.SERVING);
  }

  void sessionDisconnected() {
    health.setStatus(READINESS, ServingStatus.NOT_SERVING);
  }

  @Override
  public void close() {
    health.enterTerminalState();
    server.shutdownNow();
    try {
      server.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
