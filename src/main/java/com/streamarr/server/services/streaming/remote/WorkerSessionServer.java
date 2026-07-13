package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.RenditionJob;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WorkerSessionServer implements AutoCloseable {

  private final WorkerSessionServerConfiguration configuration;
  private final SegmentStore segmentStore;
  private final LiveWorkerConnectionRegistry workerConnections = new LiveWorkerConnectionRegistry();

  private Server server;
  private ExecutorService executor;

  public WorkerSessionServer(
      WorkerSessionServerConfiguration configuration, SegmentStore segmentStore) {
    this.configuration = Objects.requireNonNull(configuration);
    this.segmentStore = Objects.requireNonNull(segmentStore);
  }

  public synchronized void start() throws IOException {
    if (server != null) {
      throw new IllegalStateException("Worker session server is already started");
    }

    var tlsIdentity = configuration.tlsIdentity();
    var sslContext =
        GrpcSslContexts.forServer(
                tlsIdentity.certificate().toFile(), tlsIdentity.privateKey().toFile())
            .trustManager(tlsIdentity.trustBundle().toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    var identityInterceptor =
        new WorkerIdentityServerInterceptor(
            new WorkerSpiffeIdentityMapper(configuration.trustDomain()));
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server =
        NettyServerBuilder.forPort(configuration.port())
            .sslContext(sslContext)
            .executor(executor)
            .addService(
                ServerInterceptors.intercept(
                    new WorkerSessionGrpcService(workerConnections, segmentStore),
                    identityInterceptor))
            .build()
            .start();
  }

  public synchronized int port() {
    requireStarted();
    return server.getPort();
  }

  public synchronized boolean dispatch(RenditionJob job) {
    requireStarted();
    return workerConnections.dispatch(job);
  }

  public synchronized boolean stopRendition(UUID jobAttemptId) {
    requireStarted();
    return workerConnections.stopRendition(jobAttemptId);
  }

  public synchronized void stopStreamSession(UUID streamSessionId) {
    requireStarted();
    workerConnections.stopStreamSession(streamSessionId);
  }

  public synchronized boolean isRunning(UUID streamSessionId) {
    requireStarted();
    return workerConnections.isRunning(streamSessionId);
  }

  public synchronized boolean isRunning(UUID streamSessionId, String renditionName) {
    requireStarted();
    return workerConnections.isRunning(streamSessionId, renditionName);
  }

  public synchronized boolean hasConnectedWorker() {
    return server != null && workerConnections.hasConnectedWorker();
  }

  private void requireStarted() {
    if (server == null) {
      throw new IllegalStateException("Worker session server is not started");
    }
  }

  @Override
  public synchronized void close() {
    if (server == null) {
      return;
    }

    server.shutdownNow();
    try {
      server.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    executor.shutdownNow();
    server = null;
    executor = null;
  }
}
