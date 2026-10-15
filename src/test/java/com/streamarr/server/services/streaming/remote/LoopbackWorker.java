package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A worker that registers over a real worker session with one libx264 encoder and eight job slots.
 * The test reads the commands that the server sends and reports job attempt outcomes.
 */
final class LoopbackWorker implements AutoCloseable {

  private final StreamObserver<EstablishWorkerSessionRequest> requests;
  private final BlockingQueue<EstablishWorkerSessionResponse> responses;

  private LoopbackWorker(
      StreamObserver<EstablishWorkerSessionRequest> requests,
      BlockingQueue<EstablishWorkerSessionResponse> responses) {
    this.requests = requests;
    this.responses = responses;
  }

  static LoopbackWorker register(ManagedChannel channel) {
    var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
    var requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new QueueingObserver(responses));
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setWorker(
                        WorkerIdentity.newBuilder()
                            .setWorkerId(toProto(WORKER_ID))
                            .setBootId(toProto(UUID.randomUUID())))
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                            .addVideoEncoders(
                                softwareEncoder(
                                    VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")))
                    .setAvailableSlots(8))
            .build());
    return new LoopbackWorker(requests, responses);
  }

  EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
    var response = responses.poll(5, TimeUnit.SECONDS);
    assertThat(response).as("worker session response must arrive").isNotNull();
    return response;
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

  void reportFailed(VariantJob job) {
    requests.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
            .build());
  }

  @Override
  public void close() {
    requests.onCompleted();
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
