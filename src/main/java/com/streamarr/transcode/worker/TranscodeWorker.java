package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.transcode.protocol.ProtoUuid;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.StartRenditionCommand;
import com.streamarr.transcode.v1.StopRenditionCommand;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TranscodeWorker implements AutoCloseable {

  private static final int CONNECTION_TIMEOUT_SECONDS = 5;

  private final TranscodeWorkerConfiguration configuration;
  private final FfmpegTranscodeEngine engine;
  private final WorkerRenditionJobMapper jobMapper;
  private final Map<UUID, ActiveRendition> activeRenditions = new HashMap<>();

  private ManagedChannel channel;
  private ExecutorService executor;
  private StreamObserver<WorkerSessionRequest> requests;

  public TranscodeWorker(TranscodeWorkerConfiguration configuration, FfmpegTranscodeEngine engine) {
    this.configuration = configuration;
    this.engine = engine;
    jobMapper =
        new WorkerRenditionJobMapper(
            new WorkerMediaSourceResolver(configuration.sourceNamespaces()));
  }

  public synchronized void start(String host, int port) throws Exception {
    if (channel != null) {
      throw new IllegalStateException("Transcode worker is already started");
    }

    var tlsIdentity = configuration.tlsIdentity();
    var sslContext =
        GrpcSslContexts.forClient()
            .keyManager(tlsIdentity.certificate().toFile(), tlsIdentity.privateKey().toFile())
            .trustManager(tlsIdentity.trustBundle().toFile())
            .build();
    executor = Executors.newVirtualThreadPerTaskExecutor();
    channel = NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    var accepted = new CompletableFuture<Void>();
    requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .workerSession(new WorkerResponseObserver(accepted));
    send(registration());
    accepted.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private WorkerSessionRequest registration() {
    var capabilities = WorkerCapabilities.newBuilder();
    configuration.sourceNamespaces().keySet().stream()
        .map(ProtoUuid::toProto)
        .forEach(capabilities::addSourceNamespaceIds);
    var registration =
        WorkerRegistration.newBuilder()
            .setWorker(identity())
            .setCapabilities(capabilities)
            .setAvailableSlots(1);
    return WorkerSessionRequest.newBuilder().setRegistration(registration).build();
  }

  private WorkerIdentity identity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(configuration.workerId()))
        .setBootId(toProto(configuration.bootId()))
        .build();
  }

  private synchronized void startRendition(StartRenditionCommand command) {
    var job = command.getJob();
    if (!command.getTarget().equals(identity())) {
      sendFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      return;
    }

    try {
      var outputDirectory =
          configuration.segmentBasePath().resolve(fromProto(job.getJobAttemptId()).toString());
      Files.createDirectories(outputDirectory);
      var request = jobMapper.map(job);
      engine.start(request, outputDirectory);
      activeRenditions.put(
          fromProto(job.getJobAttemptId()),
          new ActiveRendition(request.sessionId(), request.variantLabel()));
      send(
          WorkerSessionRequest.newBuilder()
              .setJobAttemptStarted(
                  JobAttemptStarted.newBuilder().setJobAttemptId(job.getJobAttemptId()))
              .build());
    } catch (IOException | WorkerJobException e) {
      sendFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
    }
  }

  private synchronized void stopRendition(StopRenditionCommand command) {
    if (!command.getTarget().equals(identity())) {
      return;
    }

    var jobAttemptId = fromProto(command.getJobAttemptId());
    var rendition = activeRenditions.remove(jobAttemptId);
    if (rendition == null) {
      return;
    }

    engine.stop(rendition.streamSessionId(), rendition.renditionName());
    send(
        WorkerSessionRequest.newBuilder()
            .setJobAttemptStopped(
                JobAttemptStopped.newBuilder().setJobAttemptId(command.getJobAttemptId()))
            .build());
  }

  private void sendFailure(RenditionJob job, JobAttemptFailure failure) {
    send(
        WorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(failure))
            .build());
  }

  private synchronized void send(WorkerSessionRequest request) {
    requests.onNext(request);
  }

  private synchronized void stopActiveRenditions() {
    activeRenditions
        .values()
        .forEach(rendition -> engine.stop(rendition.streamSessionId(), rendition.renditionName()));
    activeRenditions.clear();
  }

  @Override
  public synchronized void close() {
    if (channel == null) {
      return;
    }

    stopActiveRenditions();
    requests.onCompleted();
    channel.shutdownNow();
    try {
      channel.awaitTermination(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    executor.shutdownNow();
    requests = null;
    channel = null;
    executor = null;
  }

  private final class WorkerResponseObserver implements StreamObserver<WorkerSessionResponse> {

    private final CompletableFuture<Void> accepted;

    private WorkerResponseObserver(CompletableFuture<Void> accepted) {
      this.accepted = accepted;
    }

    @Override
    public void onNext(WorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        accepted.complete(null);
        return;
      }
      if (response.hasStartRendition()) {
        executor.submit(() -> startRendition(response.getStartRendition()));
        return;
      }
      if (response.hasStopRendition()) {
        executor.submit(() -> stopRendition(response.getStopRendition()));
      }
    }

    @Override
    public void onError(Throwable throwable) {
      stopActiveRenditions();
      accepted.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      stopActiveRenditions();
      if (!accepted.isDone()) {
        accepted.completeExceptionally(new IllegalStateException("Worker session closed"));
      }
    }
  }

  private record ActiveRendition(UUID streamSessionId, String renditionName) {}
}
