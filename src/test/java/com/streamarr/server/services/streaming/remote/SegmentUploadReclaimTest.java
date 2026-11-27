package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.SegmentUploadTickets.admitted;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.ForwardingPreparedSegment;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Upload Reclaim Tests")
class SegmentUploadReclaimTest {

  @Test
  @DisplayName("Should terminate an upload when admission reclaims its expired ticket")
  void shouldTerminateUploadWhenAdmissionReclaimsExpiredTicket() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 1024, 8, Duration.ofSeconds(30), clock);
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var workerConnections = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerSessionId =
        workerConnections.register(
            workerId, registration(worker, sourceNamespaceId), noOpResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(workerConnections.dispatch(job)).isPresent();

    var error = new AtomicReference<Throwable>();
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(workerId)
            .workerConnections(workerConnections)
            .segmentStore(new FakeSegmentStore())
            .responseObserver(errorCapturingResponseObserver(error))
            .uploadTicket(admitted(admission.tryAdmit(workerId)))
            .build();
    var partialData = ByteString.copyFromUtf8("partial");
    observer.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(
                metadataBuilder(workerSessionId, worker, job)
                    .setContentLengthBytes(partialData.size() + 1L))
            .build());
    observer.onNext(UploadSegmentRequest.newBuilder().setData(partialData).build());
    assertThat(error.get()).isNull();

    clock.advance(Duration.ofSeconds(31));
    try (var successor = admitted(admission.tryAdmit(UUID.randomUUID()))) {
      assertThat(successor.tryReserve(partialData.size() + 1L)).isTrue();
    }

    assertThat(error.get())
        .as("reclaim must terminate the observer without waiting for another onNext")
        .isNotNull();
    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
  }

  @Test
  @DisplayName(
      "Should discard the upload's temporary bytes when admission reclaims its ticket before the upload's first data")
  void shouldDiscardTemporaryBytesWhenAdmissionReclaimsTicketBeforeFirstData() {
    var clock = new MutableClock();
    var admission = new SegmentUploadAdmission(1, 1024, 8, Duration.ofSeconds(30), clock);
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var workerConnections = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerSessionId =
        workerConnections.register(
            workerId, registration(worker, sourceNamespaceId), noOpResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(workerConnections.dispatch(job)).isPresent();
    var discarded = new AtomicBoolean();
    // The ticket expires and a successor reclaims it after the upload reserved its bytes and
    // before the upload learns of the reclaim.
    var store =
        new FakeSegmentStore() {
          @Override
          public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
            clock.advance(Duration.ofSeconds(31));
            admitted(admission.tryAdmit(UUID.randomUUID())).close();
            return new ForwardingPreparedSegment(super.prepareSegment(sessionId, segmentName)) {
              @Override
              public void close() {
                discarded.set(true);
              }
            };
          }
        };
    var error = new AtomicReference<Throwable>();
    var observer =
        SegmentUploadObserver.builder()
            .authenticatedWorkerId(workerId)
            .workerConnections(workerConnections)
            .segmentStore(store)
            .responseObserver(errorCapturingResponseObserver(error))
            .uploadTicket(admitted(admission.tryAdmit(workerId)))
            .build();

    observer.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(metadataBuilder(workerSessionId, worker, job).setContentLengthBytes(8L))
            .build());

    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(discarded).isTrue();
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

  private static SegmentUploadMetadata.Builder metadataBuilder(
      UUID workerSessionId, WorkerIdentity worker, VariantJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel())
        .setSegmentName("segment0.m4s")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4);
  }

  private static StreamObserver<UploadSegmentResponse> errorCapturingResponseObserver(
      AtomicReference<Throwable> error) {
    return new StreamObserver<>() {
      @Override
      public void onNext(UploadSegmentResponse value) {
        // This observer records only terminal errors.
      }

      @Override
      public void onError(Throwable throwable) {
        error.set(throwable);
      }

      @Override
      public void onCompleted() {
        // This observer records only terminal errors.
      }
    };
  }

  private static <T> StreamObserver<T> noOpResponseObserver() {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        // No response is relevant to this reclaim test.
      }

      @Override
      public void onError(Throwable throwable) {
        // No response is relevant to this reclaim test.
      }

      @Override
      public void onCompleted() {
        // No response is relevant to this reclaim test.
      }
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
