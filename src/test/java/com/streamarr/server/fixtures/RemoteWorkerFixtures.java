package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.remote.ProbeAttemptReport;
import com.streamarr.server.services.streaming.remote.ProbeDispatch;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.server.services.streaming.remote.protocol.WorkerIdentityMetadata;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.Future;

public final class RemoteWorkerFixtures {
  public static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  public static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private RemoteWorkerFixtures() {}

  public static WorkerVerifiedVideoEncoder softwareEncoder(
      VideoCodecFamily codecFamily, String encoder) {
    return workerVerifiedEncoder(codecFamily, encoder, EncoderKind.ENCODER_KIND_SOFTWARE);
  }

  public static WorkerVerifiedVideoEncoder hardwareEncoder(
      VideoCodecFamily codecFamily, String encoder) {
    return workerVerifiedEncoder(codecFamily, encoder, EncoderKind.ENCODER_KIND_HARDWARE);
  }

  private static WorkerVerifiedVideoEncoder workerVerifiedEncoder(
      VideoCodecFamily codecFamily, String encoder, EncoderKind kind) {
    var verified = WorkerVerifiedVideoEncoder.newBuilder();
    verified.getBackendBuilder().setCodecFamily(codecFamily).setEncoder(encoder).setKind(kind);
    return verified.build();
  }

  public static WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder
      serverConfigurationBuilder() {
    return WorkerSessionServerConfiguration.builder().port(0);
  }

  public static NettyChannelBuilder plaintextChannelBuilder(int port, UUID workerId) {
    var headers = new Metadata();
    headers.put(WorkerIdentityMetadata.WORKER_ID, workerId.toString());
    return NettyChannelBuilder.forAddress("127.0.0.1", port)
        .usePlaintext()
        .intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  public static Future<ProbeAttemptReport> dispatched(ProbeDispatch dispatch) {
    return switch (dispatch) {
      case ProbeDispatch.Dispatched(var result) -> result;
      case ProbeDispatch.Refused(var reason) ->
          throw new IllegalStateException("Probe dispatch refused: " + reason);
    };
  }
}
