package com.streamarr.contract;

import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Channel;

public final class PublishedContractConsumer {

  private PublishedContractConsumer() {}

  public static TranscodeWorkerServiceGrpc.TranscodeWorkerServiceStub workerSession(Channel channel) {
    return TranscodeWorkerServiceGrpc.newStub(channel);
  }

  public static WorkerRegistration registration(WorkerIdentity identity) {
    return WorkerRegistration.newBuilder().setWorker(identity).setAvailableSlots(1).build();
  }
}
