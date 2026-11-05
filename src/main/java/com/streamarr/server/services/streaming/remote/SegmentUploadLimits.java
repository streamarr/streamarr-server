package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.streamarr.transcode.v1.SegmentUploadLimitReached;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;

/** The limits within which the server admits segment uploads from workers. */
final class SegmentUploadLimits {

  static final int MAXIMUM_CONCURRENT_SEGMENT_UPLOADS = 32;
  static final long MAXIMUM_BUFFERED_SEGMENT_BYTES = 512L * 1024 * 1024;

  // A ladder's variants upload in parallel, so the allowance has to clear a full ladder; it is a
  // blast-radius bound on one worker's wedged streams, not a throughput limit.
  static final int MAXIMUM_SEGMENT_UPLOADS_PER_WORKER = 8;

  private static final Status WORKER_LIMIT_REFUSAL =
      Status.newBuilder()
          .setCode(Code.RESOURCE_EXHAUSTED_VALUE)
          .setMessage("Worker segment upload limit reached")
          .addDetails(Any.pack(SegmentUploadLimitReached.getDefaultInstance()))
          .build();

  private SegmentUploadLimits() {}

  /** Ends an upload call beyond the worker's limit, marked so the worker queues the segment. */
  static StatusRuntimeException workerLimitRefusal() {
    return StatusProto.toStatusRuntimeException(WORKER_LIMIT_REFUSAL);
  }
}
