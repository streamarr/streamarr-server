package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.Any;
import io.grpc.protobuf.StatusProto;
import java.util.List;

final class SegmentUploadRefusals {

  private SegmentUploadRefusals() {}

  /** The google.rpc.Status details that a refused upload call carries, as a worker reads them. */
  static List<Any> statusDetails(Throwable refusal) {
    var status = StatusProto.fromThrowable(refusal);
    if (status == null) {
      return List.of();
    }

    return status.getDetailsList();
  }
}
