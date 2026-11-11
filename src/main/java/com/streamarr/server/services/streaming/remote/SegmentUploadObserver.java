package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentCap;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
final class SegmentUploadObserver implements StreamObserver<UploadSegmentRequest> {

  private final UUID authenticatedWorkerId;
  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;
  private final StreamObserver<UploadSegmentResponse> responseObserver;
  @NonNull private final SegmentUploadAdmission.Ticket uploadTicket;

  private SegmentUploadMetadata metadata;
  private SegmentStore.PreparedSegment prepared;
  private long receivedBytes;
  private boolean closed;

  @Override
  public synchronized void onNext(UploadSegmentRequest request) {
    if (closed) {
      return;
    }
    if (uploadTicket.isClosed()) {
      // The admission reclaimed this upload's capacity after it exceeded the maximum upload age.
      // Terminate the stream: storing on would hold bytes the byte budget no longer accounts for.
      reject(Status.DEADLINE_EXCEEDED.withDescription("Segment upload exceeded the maximum age"));
      return;
    }
    if (request.hasMetadata()) {
      receiveMetadata(request.getMetadata());
      return;
    }
    if (request.hasData()) {
      receiveData(request);
      return;
    }
    reject(Status.INVALID_ARGUMENT.withDescription("Segment upload frame is required"));
  }

  private void receiveMetadata(SegmentUploadMetadata incoming) {
    if (metadata != null) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment metadata must appear exactly once"));
      return;
    }
    if (!workerConnections.authorizesUpload(authenticatedWorkerId, incoming)) {
      reject(Status.PERMISSION_DENIED.withDescription("Segment upload is not connection-owned"));
      return;
    }
    if (!isValidMetadata(incoming)) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment metadata is invalid"));
      return;
    }
    if (!uploadTicket.tryReserve(incoming.getContentLengthBytes())) {
      reject(Status.RESOURCE_EXHAUSTED.withDescription("Segment upload byte budget reached"));
      return;
    }

    metadata = incoming;
    try {
      prepared = segmentStore.prepareSegment(streamSessionId(), storedSegmentName());
    } catch (RuntimeException e) {
      rejectStorageFailure(e);
      return;
    }

    // A ticket reclaimed since its reservation expires this upload at once, so the upload must
    // already own its temporary file for that expiry to discard.
    uploadTicket.onReclaimed(this::expire);
  }

  private void receiveData(UploadSegmentRequest request) {
    if (metadata == null || request.getData().isEmpty()) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment data must follow metadata"));
      return;
    }
    if (wouldExceedDeclaredLength(request)) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment data exceeds declared length"));
      return;
    }

    try {
      prepared.append(request.getData().asReadOnlyByteBuffer());
    } catch (RuntimeException e) {
      rejectStorageFailure(e);
      return;
    }

    receivedBytes += request.getData().size();
    workerConnections.reportUploadProgress(authenticatedWorkerId, metadata);
  }

  private boolean wouldExceedDeclaredLength(UploadSegmentRequest request) {
    return receivedBytes + request.getData().size() > metadata.getContentLengthBytes();
  }

  private boolean isComplete() {
    return metadata != null && receivedBytes == metadata.getContentLengthBytes();
  }

  private UUID streamSessionId() {
    return fromProto(metadata.getStreamSessionId());
  }

  private String storedSegmentName() {
    return SegmentNames.storedSegmentName(metadata.getVariantLabel(), metadata.getSegmentName());
  }

  private void rejectStorageFailure(RuntimeException failure) {
    log.error(
        "Failed to store segment {} for stream session {}",
        storedSegmentName(),
        streamSessionId(),
        failure);
    reject(Status.INTERNAL.withDescription("Segment could not be stored"));
  }

  @Override
  public synchronized void onError(Throwable throwable) {
    log.warn("Segment upload from worker {} failed", authenticatedWorkerId, throwable);
    close();
  }

  @Override
  public synchronized void onCompleted() {
    if (closed) {
      return;
    }
    if (!isComplete()) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment upload is incomplete"));
      return;
    }
    Optional<SegmentPublication> outcome;
    try {
      outcome =
          workerConnections.publishIfAuthorized(authenticatedWorkerId, metadata, prepared::publish);
      discardPrepared();
    } catch (RuntimeException e) {
      rejectStorageFailure(e);
      return;
    }
    var status =
        outcome
            .map(SegmentUploadObserver::responseStatus)
            .orElseGet(
                () ->
                    Status.PERMISSION_DENIED.withDescription(
                        "Segment upload lost connection ownership"));
    if (!status.isOk()) {
      reject(status);
      return;
    }

    var acceptedLength = receivedBytes;
    close();
    responseObserver.onNext(
        UploadSegmentResponse.newBuilder()
            .setJobAttemptId(metadata.getJobAttemptId())
            .setSegmentName(metadata.getSegmentName())
            .setAcceptedLengthBytes(acceptedLength)
            .build());
    responseObserver.onCompleted();
  }

  private static Status responseStatus(SegmentPublication outcome) {
    return switch (outcome) {
      case PUBLISHED -> Status.OK;
      case INITIALIZATION_SEGMENT_DIFFERS ->
          Status.FAILED_PRECONDITION.withDescription(
              "Initialization segment differs from the one stored for the variant");
    };
  }

  private static boolean isValidMetadata(SegmentUploadMetadata metadata) {
    return isValidLength(metadata.getContentLengthBytes())
        && isValidName(metadata.getVariantLabel())
        && isValidName(metadata.getSegmentName())
        && contentTypeMatchesName(metadata.getContentType(), metadata.getSegmentName());
  }

  private static boolean isValidLength(long contentLength) {
    return contentLength > 0 && contentLength <= SegmentCap.BYTES;
  }

  private static boolean isValidName(String value) {
    return !value.isBlank()
        && !value.contains("..")
        && !value.contains("/")
        && !value.contains("\\");
  }

  private static boolean contentTypeMatchesName(
      SegmentContentType contentType, String segmentName) {
    return switch (contentType) {
      case SEGMENT_CONTENT_TYPE_VIDEO_MP4 -> SegmentNames.hasFmp4Extension(segmentName);
      // HLS delivery is fMP4 only (ADR 0037); the additive contract keeps the MPEG-TS value.
      case SEGMENT_CONTENT_TYPE_VIDEO_MP2T, SEGMENT_CONTENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> false;
    };
  }

  private void reject(Status status) {
    close();
    responseObserver.onError(status.asRuntimeException());
  }

  private synchronized void expire() {
    reject(Status.DEADLINE_EXCEEDED.withDescription("Segment upload exceeded the maximum age"));
  }

  private void close() {
    if (closed) {
      return;
    }
    closed = true;
    uploadTicket.close();
    try {
      discardPrepared();
    } catch (RuntimeException e) {
      log.warn("Failed to discard the upload of segment {}", metadata.getSegmentName(), e);
    }
  }

  // Deletes the upload's temporary file. A published segment has already left that file.
  private void discardPrepared() {
    var discarded = prepared;
    prepared = null;
    if (discarded == null) {
      return;
    }

    try {
      discarded.close();
    } catch (RuntimeException e) {
      workerConnections.countUploadDiscardFailure();
      throw e;
    }
  }
}
