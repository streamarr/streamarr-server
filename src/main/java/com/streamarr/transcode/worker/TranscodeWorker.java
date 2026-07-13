package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.google.protobuf.ByteString;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.transcode.protocol.ProtoUuid;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartRenditionCommand;
import com.streamarr.transcode.v1.StopRenditionCommand;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.v1.WorkerSessionRequest;
import com.streamarr.transcode.v1.WorkerSessionResponse;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TranscodeWorker implements AutoCloseable {

  private static final int CONNECTION_TIMEOUT_SECONDS = 5;
  private static final int SEGMENT_CHUNK_BYTES = 64 * 1024;
  private static final int SEGMENT_WAIT_SECONDS = 30;

  private final TranscodeWorkerConfiguration configuration;
  private final FfmpegTranscodeEngine engine;
  private final WorkerRenditionJobMapper jobMapper;
  private final Map<UUID, ActiveRendition> activeRenditions = new HashMap<>();

  private ManagedChannel channel;
  private ExecutorService executor;
  private StreamObserver<WorkerSessionRequest> requests;
  private WorkerSessionAccepted workerSession;

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
    var accepted = new CompletableFuture<WorkerSessionAccepted>();
    requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .workerSession(new WorkerResponseObserver(accepted));
    send(registration());
    workerSession = accepted.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
            .setAvailableSlots(configuration.availableSlots());
    return WorkerSessionRequest.newBuilder().setRegistration(registration).build();
  }

  private WorkerIdentity identity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(configuration.workerId()))
        .setBootId(toProto(configuration.bootId()))
        .build();
  }

  private void startRendition(StartRenditionCommand command) {
    var job = command.getJob();
    Path outputDirectory;
    try {
      synchronized (this) {
        if (!command.getTarget().equals(identity())) {
          sendFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
          return;
        }

        outputDirectory =
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
      }
    } catch (IOException | RuntimeException _) {
      sendFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return;
    }

    try {
      uploadFirstSegment(job, outputDirectory);
    } catch (Exception _) {
      failRendition(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
    }
  }

  private void uploadFirstSegment(RenditionJob job, Path outputDirectory) throws Exception {
    var segmentName = firstSegmentName(job);
    var segmentPath = awaitSegment(outputDirectory.resolve(segmentName));
    var segmentLength = Files.size(segmentPath);
    var response = new CompletableFuture<UploadSegmentResponse>();
    var upload =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .uploadSegment(new SegmentUploadResponseObserver(response));
    upload.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(segmentMetadata(job, segmentName, segmentLength))
            .build());
    try (InputStream input = Files.newInputStream(segmentPath)) {
      byte[] chunk;
      while ((chunk = input.readNBytes(SEGMENT_CHUNK_BYTES)).length > 0) {
        upload.onNext(
            UploadSegmentRequest.newBuilder().setData(ByteString.copyFrom(chunk)).build());
      }
    }
    upload.onCompleted();
    var accepted = response.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (accepted.getAcceptedLengthBytes() != segmentLength) {
      throw new WorkerJobException("Server accepted an incomplete segment");
    }
  }

  private Path awaitSegment(Path segmentPath) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEGMENT_WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(segmentPath)) {
        return segmentPath;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new WorkerJobException("Interrupted while waiting for a segment");
      }
    }
    throw new WorkerJobException("Timed out waiting for a segment");
  }

  private SegmentUploadMetadata segmentMetadata(
      RenditionJob job, String segmentName, long segmentLength) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(workerSession.getWorkerSessionId())
        .setWorker(identity())
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setRenditionName(job.getRendition().getRenditionName())
        .setSegmentName(segmentName)
        .setContentType(contentType(job))
        .setContentLengthBytes(segmentLength)
        .build();
  }

  private String firstSegmentName(RenditionJob job) {
    var extension =
        switch (job.getDecision().getContainer()) {
          case CONTAINER_FORMAT_MPEG_TS -> ".ts";
          case CONTAINER_FORMAT_FMP4 -> ".m4s";
          case CONTAINER_FORMAT_UNSPECIFIED, UNRECOGNIZED ->
              throw new WorkerJobException("Container format is required");
        };
    return "segment" + job.getExecution().getStartNumber() + extension;
  }

  private SegmentContentType contentType(RenditionJob job) {
    return switch (job.getDecision().getContainer()) {
      case CONTAINER_FORMAT_MPEG_TS -> SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T;
      case CONTAINER_FORMAT_FMP4 -> SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4;
      case CONTAINER_FORMAT_UNSPECIFIED, UNRECOGNIZED ->
          throw new WorkerJobException("Container format is required");
    };
  }

  private synchronized void failRendition(RenditionJob job, JobAttemptFailure failure) {
    var rendition = activeRenditions.remove(fromProto(job.getJobAttemptId()));
    if (rendition != null) {
      engine.stop(rendition.streamSessionId(), rendition.renditionName());
    }
    sendFailure(job, failure);
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
    workerSession = null;
    channel = null;
    executor = null;
  }

  private final class WorkerResponseObserver implements StreamObserver<WorkerSessionResponse> {

    private final CompletableFuture<WorkerSessionAccepted> accepted;

    private WorkerResponseObserver(CompletableFuture<WorkerSessionAccepted> accepted) {
      this.accepted = accepted;
    }

    @Override
    public void onNext(WorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        accepted.complete(response.getSessionAccepted());
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

  private record SegmentUploadResponseObserver(CompletableFuture<UploadSegmentResponse> response)
      implements StreamObserver<UploadSegmentResponse> {

    @Override
    public void onNext(UploadSegmentResponse value) {
      response.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      if (!response.isDone()) {
        response.completeExceptionally(new IllegalStateException("Segment upload closed"));
      }
    }
  }

  private record ActiveRendition(UUID streamSessionId, String renditionName) {}
}
