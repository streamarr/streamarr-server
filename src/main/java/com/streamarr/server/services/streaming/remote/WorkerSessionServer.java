package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.VariantJob;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

@Slf4j
public final class WorkerSessionServer
    implements AutoCloseable, ApplicationListener<ContextClosedEvent> {

  private static final int MAXIMUM_CONCURRENT_CALLS_PER_CONNECTION = 33;
  private static final int MAXIMUM_INBOUND_MESSAGE_BYTES = 128 * 1024;
  private static final int PERMITTED_CLIENT_KEEPALIVE_SECONDS = 10;
  private final WorkerSessionServerConfiguration configuration;
  private final SegmentStore segmentStore;
  private final HeldSegmentRequests heldSegmentRequests;
  private final LiveWorkerConnectionRegistry workerConnections;
  private final WorkerSessionServerRuntime runtime = new WorkerSessionServerRuntime(log);
  private boolean started;

  @Builder
  public WorkerSessionServer(
      @NonNull WorkerSessionServerConfiguration configuration,
      @NonNull SegmentStore segmentStore,
      @NonNull MeterRegistry meterRegistry,
      @NonNull ApplicationEventPublisher eventPublisher) {
    this.configuration = configuration;
    heldSegmentRequests = new HeldSegmentRequests(meterRegistry);
    workerConnections =
        LiveWorkerConnectionRegistry.builder()
            .configuration(configuration)
            .meterRegistry(meterRegistry)
            .heldRequests(heldSegmentRequests)
            .eventPublisher(eventPublisher)
            .build();
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
    // A dead worker connection must be detected and closed so its upload admission slots are
    // released; without keepalive probes it would pin them until the OS gives up on the TCP peer.
    var keepalive = configuration.keepalive();
    runtime.start(
        Executors.newVirtualThreadPerTaskExecutor(),
        startingExecutor ->
            builder
                .executor(startingExecutor)
                .maxConcurrentCallsPerConnection(MAXIMUM_CONCURRENT_CALLS_PER_CONNECTION)
                .maxInboundMessageSize(MAXIMUM_INBOUND_MESSAGE_BYTES)
                .keepAliveTime(keepalive.time().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(keepalive.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveTime(PERMITTED_CLIENT_KEEPALIVE_SECONDS, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .build()
                .start());
  }

  /**
   * The segment requests held until a worker of this server stores their segment. A stored segment
   * or an ended job attempt wakes the requests held on its variant.
   */
  public HeldSegmentRequests heldSegmentRequests() {
    return heldSegmentRequests;
  }

  public synchronized int port() {
    return runtime.server().getPort();
  }

  public synchronized Optional<VariantDispatch> dispatch(VariantJob job) {
    requireStarted();
    return workerConnections.dispatch(job);
  }

  /** Dispatches only to a worker that advertises the pinned worker-verified encoder. */
  public synchronized Optional<VariantDispatch> dispatch(
      VariantJob job, @NonNull WorkerVerifiedEncoder pinnedEncoder) {
    requireStarted();
    return workerConnections.dispatch(job, pinnedEncoder);
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

  /**
   * The probe capacity of the connected workers that run probes of the version in the namespace. A
   * server that has not started has no connected worker. The read never waits for a dispatch.
   */
  public ProbeCapacity probeCapacity(UUID sourceNamespaceId, int probeVersion) {
    return workerConnections.probeCapacity(sourceNamespaceId, probeVersion);
  }

  public synchronized Optional<VariantDispatch> dispatchTo(
      ExecutionTargetId target, VariantJob job) {
    requireStarted();
    return workerConnections.dispatchTo(target, job);
  }

  /** Dispatches to the target only when it advertises the pinned worker-verified encoder. */
  public synchronized Optional<VariantDispatch> dispatchTo(
      ExecutionTargetId target, VariantJob job, @NonNull WorkerVerifiedEncoder pinnedEncoder) {
    requireStarted();
    return workerConnections.dispatchTo(target, job, pinnedEncoder);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  public synchronized Set<ExecutionTargetId> eligibleWorkers(
      UUID sourceNamespaceId, com.streamarr.transcode.v1.TranscodeDecision decision) {
    requireStarted();
    return workerConnections.eligibleWorkers(sourceNamespaceId, decision);
  }

  public synchronized Set<ExecutionTargetId> eligibleWorkers(
      UUID sourceNamespaceId, @NonNull WorkerVerifiedEncoder pinnedEncoder) {
    requireStarted();
    return workerConnections.eligibleWorkers(sourceNamespaceId, pinnedEncoder);
  }

  public synchronized boolean stopVariant(UUID streamSessionId, String variantLabel) {
    requireStarted();
    return workerConnections.stopVariant(streamSessionId, variantLabel);
  }

  public synchronized void stopStreamSession(UUID streamSessionId) {
    requireStarted();
    workerConnections.stopStreamSession(streamSessionId);
  }

  public synchronized void destroyStreamSession(UUID streamSessionId) {
    requireStarted();
    workerConnections.destroyStreamSession(streamSessionId);
  }

  public synchronized boolean isRunning(UUID streamSessionId, String variantLabel) {
    requireStarted();
    return workerConnections.isRunning(streamSessionId, variantLabel);
  }

  public synchronized Optional<ExecutionTargetId> runningExecutionTarget(
      UUID streamSessionId, String variantLabel) {
    requireStarted();
    return workerConnections.runningExecutionTarget(streamSessionId, variantLabel);
  }

  public synchronized Optional<TranscodeFailure> terminalFailure(UUID streamSessionId) {
    requireStarted();
    return workerConnections.terminalFailure(streamSessionId);
  }

  public synchronized boolean hasConnectedWorker(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.hasConnectedWorker(sourceNamespaceId);
  }

  public synchronized Set<String> verifiedVideoCodecFamilies(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.verifiedVideoCodecFamilies(sourceNamespaceId);
  }

  /** Whether a connected worker can read the source namespace and probe at the version. */
  public synchronized boolean hasProbeWorker(UUID sourceNamespaceId, int probeVersion) {
    requireStarted();
    return workerConnections.hasProbeWorker(sourceNamespaceId, probeVersion);
  }

  public synchronized Set<String> encodableVideoCodecFamilies(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.encodableVideoCodecFamilies(sourceNamespaceId);
  }

  public synchronized Set<String> hardwareEncodableVideoCodecFamilies(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.hardwareEncodableVideoCodecFamilies(sourceNamespaceId);
  }

  public synchronized Set<String> verifiedHardwareVideoCodecFamilies(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.verifiedHardwareVideoCodecFamilies(sourceNamespaceId);
  }

  public synchronized int availableSlots(UUID sourceNamespaceId) {
    requireStarted();
    return workerConnections.availableSlots(sourceNamespaceId);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  public synchronized int availableSlots(
      UUID sourceNamespaceId, com.streamarr.transcode.v1.TranscodeDecision decision) {
    requireStarted();
    return workerConnections.availableSlots(sourceNamespaceId, decision);
  }

  public synchronized int availableSlots(
      UUID sourceNamespaceId, @NonNull WorkerVerifiedEncoder pinnedEncoder) {
    requireStarted();
    return workerConnections.availableSlots(sourceNamespaceId, pinnedEncoder);
  }

  private void requireStarted() {
    if (!started) {
      throw new IllegalStateException("Worker session server is not started");
    }
  }

  /**
   * Starts the server's shutdown. Spring publishes the event before it stops the schedulers whose
   * probe threads then stop waiting, so each probe that they cancel tells its worker that the
   * server is shutting down. A probe that reaches its timeout still carries the timeout reason.
   */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    workerConnections.beginShutdown();
  }

  @Override
  public synchronized void close() {
    runtime.close();
    workerConnections.close();
    started = false;
  }
}
