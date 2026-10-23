package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.transcode.v1.ProbeRequest;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class RemoteFfprobeService implements FfprobeService {

  private final WorkerSessionServer server;
  private final UUID sourceNamespaceId;
  private final RemoteMediaSourceMapper sourceMapper;
  private final RemoteProbeResultMapper resultMapper = new RemoteProbeResultMapper();

  public RemoteFfprobeService(WorkerSessionServer server, UUID sourceNamespaceId, Path sourceRoot) {
    this.server = server;
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceMapper = new RemoteMediaSourceMapper(sourceNamespaceId, sourceRoot);
  }

  @Override
  public ProbeOutcome probe(ProbeExecutionRequest request) {
    return dispatch(probeRequest(request).setPriority(priorityOf(request.priority())).build());
  }

  /** Serves {@link com.streamarr.server.services.streaming.ProbeWorkers}. */
  public boolean hasCompatibleWorker(int probeVersion) {
    return server.hasProbeWorker(sourceNamespaceId, probeVersion);
  }

  /**
   * Serves {@link com.streamarr.server.services.streaming.KeyframePacketScanner}. A scan is never a
   * playback probe, so it always goes as a background probe.
   */
  public ProbeOutcome scanKeyframePackets(ProbeExecutionRequest request) {
    return dispatch(
        probeRequest(request)
            .setScanKeyframePackets(true)
            .setPriority(priorityOf(ProbePriority.BACKGROUND))
            .build());
  }

  // The domain and the worker contract both name a probe's priority ProbePriority.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.ProbePriority priorityOf(ProbePriority priority) {
    return switch (priority) {
      case PLAYBACK -> com.streamarr.transcode.v1.ProbePriority.PROBE_PRIORITY_PLAYBACK;
      case BACKGROUND -> com.streamarr.transcode.v1.ProbePriority.PROBE_PRIORITY_BACKGROUND;
    };
  }

  private ProbeRequest.Builder probeRequest(ProbeExecutionRequest request) {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(request.attemptId()))
        .setProbeVersion(request.probeVersion())
        .setSource(sourceMapper.map(request.sourcePath()));
  }

  private ProbeOutcome dispatch(ProbeRequest request) {
    var dispatch = server.dispatchProbe(request);
    var pending =
        switch (dispatch) {
          case ProbeDispatch.Dispatched(var result) -> result;
          case ProbeDispatch.Refused(var reason) -> throw refusal(reason);
        };
    try {
      return resultMapper.map(pending.get());
    } catch (InterruptedException exception) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ProbeCancelledException("The server stopped waiting for the probe", exception);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof ProbeExecutionException failure) {
        throw failure;
      }

      if (exception.getCause() instanceof TimeoutException) {
        throw new ProbeTimedOutException(exception);
      }

      throw new ProbeExecutionException(
          ItemFailureReason.TEMPORARY, "The worker returned no probe result", exception);
    }
  }

  private static RuntimeException refusal(ProbeRefusal reason) {
    return switch (reason) {
      // The probe waits for a worker without a failure, so it runs once a worker can take it.
      case WORKERS_BUSY, NO_COMPATIBLE_WORKER, NO_CONNECTED_WORKER ->
          new ProbeWorkersBusyException(reason.description());
      case INVALID_REQUEST, WORKER_UNREACHABLE, ATTEMPT_IN_PROGRESS ->
          new ProbeExecutionException(ItemFailureReason.TEMPORARY, reason.description());
    };
  }
}
