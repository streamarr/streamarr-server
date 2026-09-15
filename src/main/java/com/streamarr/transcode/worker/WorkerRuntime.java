package com.streamarr.transcode.worker;

import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

public interface WorkerRuntime {

  ManagedChannelBuilder<?> channelBuilder(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) throws IOException;

  ExecutorService newProbeScope();
}
