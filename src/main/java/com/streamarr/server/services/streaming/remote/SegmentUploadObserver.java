package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
final class SegmentUploadObserver implements StreamObserver<UploadSegmentRequest> {

  private static final int MAXIMUM_SEGMENT_BYTES = 16 * 1024 * 1024;
  private static final String DEFAULT_VARIANT_LABEL = "default";

  private final UUID authenticatedWorkerId;
  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;
  private final StreamObserver<UploadSegmentResponse> responseObserver;
  @NonNull private final SegmentUploadAdmission uploadAdmission;

  private SegmentUploadMetadata metadata;
  private ByteArrayOutputStream data;
  private long reservedBytes;
  private boolean closed;

  @Override
  public void onNext(UploadSegmentRequest request) {
    if (closed) {
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
    if (!validLength(incoming.getContentLengthBytes())
        || !validName(incoming.getVariantLabel())
        || !validName(incoming.getSegmentName())
        || !contentTypeMatchesName(incoming.getContentType(), incoming.getSegmentName())) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment metadata is invalid"));
      return;
    }
    if (!uploadAdmission.tryReserve(incoming.getContentLengthBytes())) {
      reject(Status.RESOURCE_EXHAUSTED.withDescription("Segment upload byte budget reached"));
      return;
    }

    metadata = incoming;
    reservedBytes = incoming.getContentLengthBytes();
    data = new ByteArrayOutputStream();
  }

  private void receiveData(UploadSegmentRequest request) {
    if (metadata == null || request.getData().isEmpty()) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment data must follow metadata"));
      return;
    }
    if ((long) data.size() + request.getData().size() > metadata.getContentLengthBytes()) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment data exceeds declared length"));
      return;
    }
    data.writeBytes(request.getData().toByteArray());
  }

  @Override
  public void onError(Throwable throwable) {
    log.warn("Segment upload from worker {} failed", authenticatedWorkerId, throwable);
    close();
  }

  @Override
  public void onCompleted() {
    if (closed) {
      return;
    }
    if (metadata == null || data.size() != metadata.getContentLengthBytes()) {
      reject(Status.INVALID_ARGUMENT.withDescription("Segment upload is incomplete"));
      return;
    }
    if (!workerConnections.authorizesUpload(authenticatedWorkerId, metadata)) {
      reject(Status.PERMISSION_DENIED.withDescription("Segment upload lost connection ownership"));
      return;
    }

    var segmentName = qualifiedSegmentName();
    boolean published;
    try (var prepared =
        segmentStore.prepareSegment(
            fromProto(metadata.getStreamSessionId()), segmentName, data.toByteArray())) {
      published =
          workerConnections.publishIfAuthorized(authenticatedWorkerId, metadata, prepared::publish);
    } catch (RuntimeException e) {
      log.error(
          "Failed to store segment {} for stream session {}",
          segmentName,
          fromProto(metadata.getStreamSessionId()),
          e);
      reject(Status.INTERNAL.withDescription("Segment could not be stored"));
      return;
    }
    if (!published) {
      reject(Status.PERMISSION_DENIED.withDescription("Segment upload lost connection ownership"));
      return;
    }

    var acceptedLength = data.size();
    close();
    responseObserver.onNext(
        UploadSegmentResponse.newBuilder()
            .setJobAttemptId(metadata.getJobAttemptId())
            .setSegmentName(metadata.getSegmentName())
            .setAcceptedLengthBytes(acceptedLength)
            .build());
    responseObserver.onCompleted();
  }

  private String qualifiedSegmentName() {
    if (DEFAULT_VARIANT_LABEL.equals(metadata.getVariantLabel())) {
      return metadata.getSegmentName();
    }
    return metadata.getVariantLabel() + "/" + metadata.getSegmentName();
  }

  private static boolean validLength(long contentLength) {
    return contentLength > 0 && contentLength <= MAXIMUM_SEGMENT_BYTES;
  }

  private static boolean validName(String value) {
    return !value.isBlank()
        && !value.contains("..")
        && !value.contains("/")
        && !value.contains("\\");
  }

  private static boolean contentTypeMatchesName(
      SegmentContentType contentType, String segmentName) {
    return switch (contentType) {
      case SEGMENT_CONTENT_TYPE_VIDEO_MP2T -> segmentName.endsWith(".ts");
      case SEGMENT_CONTENT_TYPE_VIDEO_MP4 ->
          segmentName.endsWith(".m4s") || segmentName.endsWith(".mp4");
      case SEGMENT_CONTENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> false;
    };
  }

  private void reject(Status status) {
    close();
    responseObserver.onError(status.asRuntimeException());
  }

  private void close() {
    if (closed) {
      return;
    }
    closed = true;
    data = null;
    uploadAdmission.release(reservedBytes);
    reservedBytes = 0;
  }
}
