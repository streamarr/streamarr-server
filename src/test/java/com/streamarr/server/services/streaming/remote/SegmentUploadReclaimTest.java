package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Upload Reclaim Tests")
class SegmentUploadReclaimTest {

  @Test
  @DisplayName("Should terminate an upload as soon as admission reclaims its expired ticket")
  void shouldTerminateUploadAsSoonAsAdmissionReclaimsItsExpiredTicket() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 1024, 8, Duration.ofSeconds(30), clock);
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var workerConnections = new LiveWorkerConnectionRegistry();
    var workerSessionId =
        workerConnections.register(
            workerId, registration(worker, sourceNamespaceId), noOpResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(workerConnections.dispatch(job)).isTrue();

    var error = new AtomicReference<Throwable>();
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(workerId)
            .workerConnections(workerConnections)
            .segmentStore(new FakeSegmentStore())
            .responseObserver(errorCapturingResponseObserver(error))
            .uploadTicket(admission.tryAdmit(workerId).orElseThrow())
            .build();
    var partialData = ByteString.copyFromUtf8("partial");
    observer.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(metadata(workerSessionId, worker, job, partialData.size() + 1L))
            .build());
    observer.onNext(UploadSegmentRequest.newBuilder().setData(partialData).build());
    assertThat(error.get()).isNull();

    clock.advance(Duration.ofSeconds(31));
    try (var successor = admission.tryAdmit(UUID.randomUUID()).orElseThrow()) {
      assertThat(successor.tryReserve(partialData.size() + 1L)).isTrue();
    }

    assertThat(error.get())
        .as("reclaim must terminate the observer without waiting for another onNext")
        .isNotNull();
    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
  }

  private static WorkerIdentity worker(UUID workerId) {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(workerId))
        .setBootId(toProto(UUID.randomUUID()))
        .build();
  }

  private static WorkerRegistration registration(WorkerIdentity worker, UUID sourceNamespaceId) {
    return WorkerRegistration.newBuilder()
        .setWorker(worker)
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(sourceNamespaceId)))
        .setAvailableSlots(1)
        .build();
  }

  private static VariantJob variantJob(UUID sourceNamespaceId) {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(sourceNamespaceId))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private static SegmentUploadMetadata metadata(
      UUID workerSessionId, WorkerIdentity worker, VariantJob job, long contentLength) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel())
        .setSegmentName("segment0.ts")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T)
        .setContentLengthBytes(contentLength)
        .build();
  }

  private static StreamObserver<UploadSegmentResponse> errorCapturingResponseObserver(
      AtomicReference<Throwable> error) {
    return new StreamObserver<>() {
      @Override
      public void onNext(UploadSegmentResponse value) {}

      @Override
      public void onError(Throwable throwable) {
        error.set(throwable);
      }

      @Override
      public void onCompleted() {}
    };
  }

  private static <T> StreamObserver<T> noOpResponseObserver() {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {}

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onCompleted() {}
    };
  }

  private static final class MutableClock extends Clock {

    private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
