package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.VariantJob;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class WorkerSessionServer implements AutoCloseable {

  private static final int MAXIMUM_CONCURRENT_CALLS_PER_CONNECTION = 33;
  private static final int MAXIMUM_INBOUND_MESSAGE_BYTES = 128 * 1024;
  // A dead worker connection must be detected and closed so its upload admission slots are
  // released; without keepalive probes it would pin them until the OS gives up on the TCP peer.
  private static final int KEEPALIVE_TIME_SECONDS = 30;
  private static final int KEEPALIVE_TIMEOUT_SECONDS = 10;
  private static final int PERMITTED_CLIENT_KEEPALIVE_SECONDS = 10;
  private final WorkerSessionListeners listeners;
  private final SegmentStore segmentStore;
  private final LiveWorkerConnectionRegistry workerConnections = new LiveWorkerConnectionRegistry();
  private final WorkerSessionServerRuntime runtime = new WorkerSessionServerRuntime(log);
  private final WorkerSessionServerRuntime loopbackRuntime = new WorkerSessionServerRuntime(log);
  private boolean started;

  public WorkerSessionServer(
      @NonNull WorkerSessionServerConfiguration configuration, @NonNull SegmentStore segmentStore) {
    this(
        WorkerSessionListeners.builder().mutualTls(Optional.of(configuration)).build(),
        segmentStore);
  }

  private WorkerSessionServer(WorkerSessionListeners listeners, SegmentStore segmentStore) {
    this.listeners = listeners;
    this.segmentStore = segmentStore;
  }

  public static WorkerSessionServer forListeners(
      @NonNull WorkerSessionListeners listeners, @NonNull SegmentStore segmentStore) {
    return new WorkerSessionServer(listeners, segmentStore);
  }

  public synchronized void start() throws IOException {
    if (started) {
      throw new IllegalStateException("Worker session server is already started");
    }

    var service = new WorkerSessionGrpcService(workerConnections, segmentStore);
    try {
      if (listeners.mutualTls().isPresent()) {
        startMutualTls(listeners.mutualTls().orElseThrow(), service);
      }

      if (listeners.loopbackPort().isPresent()) {
        startListener(
            loopbackRuntime,
            NettyServerBuilder.forAddress(
                    new InetSocketAddress("127.0.0.1", listeners.loopbackPort().getAsInt()))
                .addService(
                    ServerInterceptors.intercept(
                        service, new LoopbackWorkerIdentityInterceptor())));
      }

      started = true;
    } finally {
      if (!started) {
        close();
      }
    }
  }

  private void startMutualTls(
      WorkerSessionServerConfiguration configuration, WorkerSessionGrpcService service)
      throws IOException {
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
    startListener(
        runtime,
        NettyServerBuilder.forPort(configuration.port())
            .sslContext(sslContext)
            .addService(ServerInterceptors.intercept(service, identityInterceptor)));
  }

  private void startListener(WorkerSessionServerRuntime listenerRuntime, NettyServerBuilder builder)
      throws IOException {
    listenerRuntime.start(
        Executors.newVirtualThreadPerTaskExecutor(),
        startingExecutor ->
            builder
                .executor(startingExecutor)
                .maxConcurrentCallsPerConnection(MAXIMUM_CONCURRENT_CALLS_PER_CONNECTION)
                .maxInboundMessageSize(MAXIMUM_INBOUND_MESSAGE_BYTES)
                .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .permitKeepAliveTime(PERMITTED_CLIENT_KEEPALIVE_SECONDS, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .build()
                .start());
  }

  public synchronized int port() {
    return runtime.server().getPort();
  }

  public synchronized int loopbackPort() {
    return loopbackRuntime.server().getPort();
  }

  public synchronized boolean dispatch(VariantJob job) {
    requireStarted();
    return workerConnections.dispatch(job);
  }

  public synchronized boolean dispatchTo(ExecutionTargetId target, VariantJob job) {
    requireStarted();
    return workerConnections.dispatchTo(target, job);
  }

  public synchronized Set<ExecutionTargetId> eligibleWorkers(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.eligibleWorkers(sourceNamespaceId);
  }

  public synchronized boolean stopVariant(UUID streamSessionId, String variantLabel) {
    requireStarted();
    return workerConnections.stopVariant(streamSessionId, variantLabel);
  }

  public synchronized void stopStreamSession(UUID streamSessionId) {
    requireStarted();
    workerConnections.stopStreamSession(streamSessionId);
  }

  public synchronized boolean isRunning(UUID streamSessionId, String variantLabel) {
    requireStarted();
    return workerConnections.isRunning(streamSessionId, variantLabel);
  }

  public synchronized boolean hasConnectedWorker(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.hasConnectedWorker(sourceNamespaceId);
  }

  public synchronized int availableSlots(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.availableSlots(sourceNamespaceId);
  }

  private void requireStarted() {
    if (!started) {
      throw new IllegalStateException("Worker session server is not started");
    }
  }

  @Override
  public synchronized void close() {
    runtime.close();
    loopbackRuntime.close();
    started = false;
  }
}
