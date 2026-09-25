package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.VariantJob;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
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
  private final WorkerSessionServerConfiguration configuration;
  private final SegmentStore segmentStore;
  private final LiveWorkerConnectionRegistry workerConnections;
  private final WorkerSessionServerRuntime runtime = new WorkerSessionServerRuntime(log);
  private boolean started;

  public WorkerSessionServer(
      @NonNull WorkerSessionServerConfiguration configuration,
      @NonNull SegmentStore segmentStore,
      @NonNull MeterRegistry meterRegistry) {
    this.configuration = configuration;
    workerConnections = new LiveWorkerConnectionRegistry(configuration, meterRegistry);
    this.segmentStore = segmentStore;
  }

  public synchronized void start() throws IOException {
    if (started) {
      throw new IllegalStateException("Worker session server is already started");
    }

    var service = new WorkerSessionGrpcService(workerConnections, segmentStore);
    try {
      startListener(
          NettyServerBuilder.forAddress(
                  new InetSocketAddress(configuration.address(), configuration.port()))
              .addService(
                  ServerInterceptors.intercept(service, new WorkerIdentityServerInterceptor())));

      started = true;
    } finally {
      if (!started) {
        close();
      }
    }
  }

  private void startListener(NettyServerBuilder builder) throws IOException {
    runtime.start(
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

  public synchronized boolean dispatch(VariantJob job) {
    requireStarted();
    return workerConnections.dispatch(job);
  }

  /**
   * Returns a refusal with its reason when no worker accepts the attempt. Each execution attempt
   * requires a fresh, non-nil ID, a nonzero contract version, and a source.
   *
   * <p>Cancelling the future requests worker termination; its reservation remains until a terminal
   * reply or session end. The configured probe deadline fails the future and requests cancellation.
   * A worker that does not acknowledge cancellation within the grace period is disconnected and
   * fenced before more work can be assigned to that session.
   */
  public synchronized ProbeDispatch dispatchProbe(ProbeRequest request) {
    requireStarted();
    return workerConnections.dispatchProbe(request);
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
    started = false;
  }
}
