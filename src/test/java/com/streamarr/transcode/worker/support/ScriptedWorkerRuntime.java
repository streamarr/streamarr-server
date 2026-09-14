package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import com.streamarr.transcode.worker.WorkerRuntime;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScriptedWorkerRuntime implements WorkerRuntime {

  private Connection connection;
  private QueuedProbeExecutor probes;

  @Override
  public ManagedChannelBuilder<?> channelBuilder(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) {
    connection = new Connection();
    return new ChannelBuilder(connection);
  }

  @Override
  public ExecutorService newProbeScope() {
    probes = new QueuedProbeExecutor();
    return probes;
  }

  public Connection connection() {
    assertThat(connection).as("the worker has opened its control connection").isNotNull();
    return connection;
  }

  public QueuedProbeExecutor probes() {
    assertThat(probes).as("the worker has opened its probe task scope").isNotNull();
    return probes;
  }

  public static final class Connection extends ManagedChannel {

    private final SessionCall call = new SessionCall();
    private boolean shutdown;

    public WorkerRegistration registration() throws Exception {
      return call.registration.get(5, TimeUnit.SECONDS);
    }

    public List<ProbeAttemptResult> results() {
      return call.events.stream()
          .filter(EstablishWorkerSessionRequest::hasProbeResult)
          .map(EstablishWorkerSessionRequest::getProbeResult)
          .toList();
    }

    public void deliver(EstablishWorkerSessionResponse response) {
      call.responses.onMessage(response);
    }

    public void complete() {
      call.responses.onClose(Status.OK, new Metadata());
    }

    public void fail(String description) {
      call.responses.onClose(Status.UNAVAILABLE.withDescription(description), new Metadata());
    }

    @Override
    public <Q, R> ClientCall<Q, R> newCall(MethodDescriptor<Q, R> method, CallOptions options) {
      assertThat(method).isEqualTo(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod());
      return sessionCall();
    }

    @SuppressWarnings("unchecked")
    private <Q, R> ClientCall<Q, R> sessionCall() {
      // newCall checks the descriptor, so these type parameters are the session envelope types.
      return (ClientCall<Q, R>) call;
    }

    @Override
    public String authority() {
      return "scripted-control-plane";
    }

    @Override
    public ManagedChannel shutdown() {
      shutdown = true;
      return this;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return shutdown();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  private static final class ChannelBuilder extends ForwardingChannelBuilder2<ChannelBuilder> {

    private final ManagedChannel channel;
    private final ManagedChannelBuilder<?> delegate =
        NettyChannelBuilder.forAddress("localhost", 1);

    private ChannelBuilder(ManagedChannel channel) {
      this.channel = channel;
    }

    @Override
    protected ManagedChannelBuilder<?> delegate() {
      return delegate;
    }

    @Override
    public ManagedChannel build() {
      return channel;
    }
  }

  private static final class SessionCall
      extends ClientCall<EstablishWorkerSessionRequest, EstablishWorkerSessionResponse> {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<EstablishWorkerSessionRequest> events =
        new ConcurrentLinkedQueue<>();
    private Listener<EstablishWorkerSessionResponse> responses;

    @Override
    public void start(Listener<EstablishWorkerSessionResponse> listener, Metadata headers) {
      responses = listener;
    }

    @Override
    public void sendMessage(EstablishWorkerSessionRequest message) {
      events.add(message);
      if (message.hasRegistration()) {
        registration.complete(message.getRegistration());
        responses.onMessage(
            EstablishWorkerSessionResponse.newBuilder()
                .setSessionAccepted(
                    WorkerSessionAccepted.newBuilder()
                        .setWorkerSessionId(toProto(UUID.randomUUID())))
                .build());
      }
    }

    @Override
    public void request(int messages) {
      // Delivery is driven explicitly by the scripted control plane.
    }

    @Override
    public void cancel(String message, Throwable cause) {
      // An already queued response can still be delivered after local shutdown.
    }

    @Override
    public void halfClose() {
      // Server completion is controlled independently from the client's half-close.
    }
  }
}
