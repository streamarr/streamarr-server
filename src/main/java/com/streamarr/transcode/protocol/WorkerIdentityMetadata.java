package com.streamarr.transcode.protocol;

import io.grpc.Metadata;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkerIdentityMetadata {

  public static final Metadata.Key<String> WORKER_ID =
      Metadata.Key.of("x-streamarr-worker-id", Metadata.ASCII_STRING_MARSHALLER);
}
