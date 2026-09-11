package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID;

import com.streamarr.transcode.protocol.WorkerIdentityMetadata;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.UUID;

final class LoopbackWorkerIdentityInterceptor implements ServerInterceptor {

  @Override
  public <R, S> ServerCall.Listener<R> interceptCall(
      ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
    var claimedIdentity = headers.get(WorkerIdentityMetadata.WORKER_ID);
    if (claimedIdentity == null) {
      return reject(call);
    }

    UUID workerId;
    try {
      workerId = UUID.fromString(claimedIdentity);
    } catch (IllegalArgumentException _) {
      return reject(call);
    }

    return Contexts.interceptCall(
        Context.current().withValue(AUTHENTICATED_WORKER_ID, workerId), call, headers, next);
  }

  private <R, S> ServerCall.Listener<R> reject(ServerCall<R, S> call) {
    call.close(Status.UNAUTHENTICATED, new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
