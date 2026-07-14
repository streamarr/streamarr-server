package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.nio.file.Path;
import java.util.UUID;

public final class RemoteTranscodeExecutor implements TranscodeExecutor {

  private final WorkerSessionServer workerServer;
  private final RemoteVariantJobMapper jobMapper;

  public RemoteTranscodeExecutor(
      WorkerSessionServer workerServer, UUID sourceNamespaceId, Path sourceRoot) {
    this.workerServer = workerServer;
    jobMapper = new RemoteVariantJobMapper(sourceNamespaceId, sourceRoot);
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    if (!workerServer.dispatch(jobMapper.map(request))) {
      throw new TranscodeException("No connected transcode worker can run this variant");
    }
    return new TranscodeHandle(0, TranscodeStatus.ACTIVE, request.startNumber());
  }

  @Override
  public void stop(UUID sessionId) {
    workerServer.stopStreamSession(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return workerServer.isRunning(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return workerServer.isRunning(sessionId, variantLabel);
  }

  @Override
  public boolean isHealthy() {
    return workerServer.hasConnectedWorker();
  }

  @Override
  public int availableSlots() {
    return workerServer.availableSlots();
  }
}
