package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A worker that registers over a real worker session with eight job slots, or the job slots that
 * the test chooses, and, unless the test names other encoders, one libx264 encoder. The test reads
 * the commands that the server sends and reports job attempt outcomes.
 */
final class LoopbackWorker implements AutoCloseable {

  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
  private static final int DEFAULT_AVAILABLE_SLOTS = 8;

  private final ManagedChannel channel;
  private final WorkerIdentity identity;
  private final StreamObserver<EstablishWorkerSessionRequest> requests;
  private final BlockingQueue<EstablishWorkerSessionResponse> responses;
  private final Map<Uuid, VariantJob> runningJobs = new LinkedHashMap<>();
  // Known once the test reads the server's acceptance of the worker session.
  private Uuid workerSessionId;
  private boolean closed;

  private LoopbackWorker(
      ManagedChannel channel,
      WorkerRegistration registration,
      BlockingQueue<EstablishWorkerSessionResponse> responses) {
    this.channel = channel;
    identity = registration.getWorker();
    this.responses = responses;
    requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new QueueingObserver(responses));
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
  }

  static LoopbackWorker register(ManagedChannel channel) {
    return register(channel, DEFAULT_AVAILABLE_SLOTS);
  }

  static LoopbackWorker register(ManagedChannel channel, int availableSlots) {
    return register(
        channel,
        registration(
            WORKER_ID,
            availableSlots,
            softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")));
  }

  static LoopbackWorker register(ManagedChannel channel, UUID workerId, int availableSlots) {
    return register(
        channel,
        registration(
            workerId,
            availableSlots,
            softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")));
  }

  static LoopbackWorker register(
      ManagedChannel channel, UUID workerId, WorkerVerifiedVideoEncoder... encoders) {
    return register(channel, registration(workerId, DEFAULT_AVAILABLE_SLOTS, encoders));
  }

  /** A worker without encoders that lists the copy support's rewrites and copy features. */
  static LoopbackWorker register(
      ManagedChannel channel, UUID workerId, WorkerCapabilities.Builder copySupport) {
    var registration = registration(workerId, DEFAULT_AVAILABLE_SLOTS).toBuilder();
    registration.getCapabilitiesBuilder().mergeFrom(copySupport.build());
    return register(channel, registration.build());
  }

  private static LoopbackWorker register(ManagedChannel channel, WorkerRegistration registration) {
    return new LoopbackWorker(channel, registration, new LinkedBlockingQueue<>());
  }

  private static WorkerRegistration registration(
      UUID workerId, int availableSlots, WorkerVerifiedVideoEncoder... encoders) {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(workerId))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder()
                .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                .addAllVideoEncoders(List.of(encoders)))
        .setAvailableSlots(availableSlots)
        .build();
  }

  EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
    var response = responses.poll(RESPONSE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    assertThat(response).as("worker session response must arrive").isNotNull();
    if (response.hasSessionAccepted()) {
      workerSessionId = response.getSessionAccepted().getWorkerSessionId();
    }

    return response;
  }

  /**
   * Uploads the job attempt's media segment over its own call, as the worker's producer does. The
   * future completes with the server's answer, or fails with its refusal.
   */
  CompletableFuture<UploadSegmentResponse> uploadMediaSegment(VariantJob job, int index) {
    assertThat(workerSessionId).as("the server accepted the worker session").isNotNull();
    var segmentName = SegmentNames.mediaSegmentName(index);
    var data = new byte[] {1};
    var answer = new CompletableFuture<UploadSegmentResponse>();
    var upload =
        TranscodeWorkerServiceGrpc.newStub(channel).uploadSegment(new AnswerObserver(answer));
    upload.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(
                SegmentUploadMetadata.newBuilder()
                    .setWorkerSessionId(workerSessionId)
                    .setWorker(identity)
                    .setStreamSessionId(job.getStreamSessionId())
                    .setJobId(job.getJobId())
                    .setJobAttemptId(job.getJobAttemptId())
                    .setVariantLabel(job.getVariant().getVariantLabel())
                    .setSegmentName(segmentName)
                    .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4)
                    .setContentLengthBytes(data.length))
            .build());
    upload.onNext(UploadSegmentRequest.newBuilder().setData(ByteString.copyFrom(data)).build());
    upload.onCompleted();
    return answer;
  }

  /** The next jobs the server starts on this worker, skipping stop commands. */
  List<VariantJob> nextJobs(int count) throws InterruptedException {
    var jobs = new ArrayList<VariantJob>();
    while (jobs.size() < count) {
      var response = nextResponse();
      if (response.hasStartVariant()) {
        jobs.add(response.getStartVariant().getJob());
      }
    }

    return jobs;
  }

  /**
   * The commands that the server sends up to and including the first one that matches, in the order
   * the server sent them.
   */
  List<EstablishWorkerSessionResponse> responsesThrough(
      Predicate<EstablishWorkerSessionResponse> last) throws InterruptedException {
    var received = new ArrayList<EstablishWorkerSessionResponse>();
    while (received.isEmpty() || !last.test(received.getLast())) {
      received.add(nextResponse());
    }

    return received;
  }

  /**
   * Applies the start and stop commands that the server sends until the running job attempts are
   * exactly one for each given variant, starting at the given media segment.
   */
  void awaitRunningJobs(Map<String, Integer> startSegmentByVariant) throws InterruptedException {
    var deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
    while (!runningStartSegments().equals(startSegmentByVariant)) {
      var response = responses.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (response == null) {
        break;
      }

      applyJobCommand(response);
    }

    assertThat(runningStartSegments())
        .as("running job attempts by variant and start segment")
        .isEqualTo(startSegmentByVariant);
  }

  private void applyJobCommand(EstablishWorkerSessionResponse response) {
    if (response.hasStartVariant()) {
      var job = response.getStartVariant().getJob();
      runningJobs.put(job.getJobAttemptId(), job);
      return;
    }

    if (response.hasStopVariant()) {
      runningJobs.remove(response.getStopVariant().getJobAttemptId());
    }
  }

  private Map<String, Integer> runningStartSegments() {
    return runningJobs.values().stream()
        .collect(
            Collectors.toMap(
                job -> job.getVariant().getVariantLabel(),
                job -> job.getExecution().getStartSequenceNumber()));
  }

  /** Reports that the job attempt started on the libx264 encoder that this worker registered. */
  void reportStarted(VariantJob job) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptStarted(
                JobAttemptStarted.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setEncoderBackend(
                        softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
                            .getBackend()))
            .build());
  }

  void reportFailed(VariantJob job) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
            .build());
  }

  // Ends the worker session once; a test may end it before teardown does.
  @Override
  public void close() {
    if (closed) {
      return;
    }

    closed = true;
    requests.onCompleted();
  }

  private record AnswerObserver(CompletableFuture<UploadSegmentResponse> answer)
      implements StreamObserver<UploadSegmentResponse> {

    @Override
    public void onNext(UploadSegmentResponse value) {
      answer.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      answer.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      answer.completeExceptionally(
          new IllegalStateException("Segment upload closed without an answer"));
    }
  }

  private record QueueingObserver(BlockingQueue<EstablishWorkerSessionResponse> responses)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      // The test observes the session only through the commands it receives.
    }

    @Override
    public void onCompleted() {
      // The server ends the session when the test closes the channel.
    }
  }
}
