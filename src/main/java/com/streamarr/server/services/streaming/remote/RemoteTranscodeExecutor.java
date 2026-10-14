package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

public final class RemoteTranscodeExecutor implements TranscodeExecutor {

  private final WorkerSessionServer workerServer;
  private final UUID sourceNamespaceId;
  private final RemoteVariantJobMapper jobMapper;

  public RemoteTranscodeExecutor(
      WorkerSessionServer workerServer, UUID sourceNamespaceId, Path sourceRoot) {
    this.workerServer = workerServer;
    this.sourceNamespaceId = sourceNamespaceId;
    jobMapper = new RemoteVariantJobMapper(sourceNamespaceId, sourceRoot);
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    var job = jobMapper.map(request);
    var dispatched =
        request
            .pinnedEncoderBackend()
            .map(pinned -> workerServer.dispatch(job, pinned))
            .orElseGet(() -> workerServer.dispatch(job));
    return dispatched
        .map(dispatch -> handleFor(request, dispatch))
        .orElseThrow(
            () -> new TranscodeException("No connected transcode worker can run this variant"));
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
    return workerServer
        .dispatchTo(target, jobMapper.map(request))
        .map(dispatch -> handleFor(request, dispatch))
        .orElseThrow(
            () ->
                new TranscodeException(
                    "Worker connection " + target.value() + " cannot run this variant"));
  }

  private static TranscodeHandle handleFor(TranscodeRequest request, VariantDispatch dispatch) {
    var handle =
        TranscodeHandle.remoteDispatch(
            request.attemptId(), TranscodeStatus.ACTIVE, request.startSequenceNumber());
    return dispatch.encoder().map(handle::withEncoder).orElse(handle);
  }

  @Override
  public void stop(UUID sessionId) {
    workerServer.stopStreamSession(sessionId);
  }

  @Override
  public void stopVariant(UUID sessionId, String variantLabel) {
    workerServer.stopVariant(sessionId, variantLabel);
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return workerServer.isRunning(sessionId, variantLabel);
  }

  @Override
  public boolean isHealthy() {
    return workerServer.hasConnectedWorker(sourceNamespaceId);
  }

  @Override
  public int availableSlots() {
    return workerServer.availableSlots(sourceNamespaceId);
  }

  @Override
  public int availableSlots(TranscodeDecision decision) {
    return workerServer.availableSlots(
        sourceNamespaceId, RemoteVariantJobMapper.decision(decision));
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(TranscodeDecision decision) {
    return workerServer.eligibleWorkers(
        sourceNamespaceId, RemoteVariantJobMapper.decision(decision));
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(EncoderBackend pinnedEncoderBackend) {
    return workerServer.eligibleWorkers(sourceNamespaceId, pinnedEncoderBackend);
  }

  @Override
  public Set<String> encodableVideoCodecFamilies() {
    return workerServer.encodableVideoCodecFamilies(sourceNamespaceId);
  }
}
