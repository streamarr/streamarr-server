package com.streamarr.transcode.worker;

import com.streamarr.transcode.protocol.WorkerIdentityMetadata;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GrpcWorkerRuntime implements WorkerRuntime {

  @Override
  public ManagedChannelBuilder<?> channelBuilder(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) throws IOException {
    var builder = NettyChannelBuilder.forAddress(address.getHostString(), address.getPort());
    if (configuration.plaintext()) {
      var headers = new Metadata();
      headers.put(WorkerIdentityMetadata.WORKER_ID, configuration.workerId().toString());
      return builder.usePlaintext().intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    var tlsIdentity = configuration.tlsIdentity().orElseThrow();
    var sslContext =
        GrpcSslContexts.forClient()
            .keyManager(tlsIdentity.certificate().toFile(), tlsIdentity.privateKey().toFile())
            .trustManager(tlsIdentity.trustBundle().toFile())
            .build();
    return builder.sslContext(sslContext);
  }

  @Override
  public ExecutorService newProbeScope() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
