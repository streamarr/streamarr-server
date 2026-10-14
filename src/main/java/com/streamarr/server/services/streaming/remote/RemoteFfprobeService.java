package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.transcode.v1.ProbeRequest;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public final class RemoteFfprobeService implements FfprobeService {

  private final WorkerSessionServer server;
  private final RemoteMediaSourceMapper sourceMapper;
  private final RemoteProbeResultMapper resultMapper = new RemoteProbeResultMapper();

  public RemoteFfprobeService(WorkerSessionServer server, UUID sourceNamespaceId, Path sourceRoot) {
    this.server = server;
    this.sourceMapper = new RemoteMediaSourceMapper(sourceNamespaceId, sourceRoot);
  }

  @Override
  public ProbeOutcome probe(ProbeExecutionRequest request) {
    var dispatch =
        server.dispatchProbe(
            ProbeRequest.newBuilder()
                .setProbeAttemptId(toProto(request.attemptId()))
                .setProbeVersion(request.probeVersion())
                .setSource(sourceMapper.map(request.sourcePath()))
                .build());
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

      throw new ProbeExecutionException(
          ItemFailureReason.TEMPORARY, "The worker returned no probe result", exception);
    }
  }

  private static RuntimeException refusal(ProbeRefusal reason) {
    return switch (reason) {
      case WORKERS_BUSY -> new ProbeWorkersBusyException();
      case NO_COMPATIBLE_WORKER ->
          new ProbeExecutionException(ItemFailureReason.MISCONFIGURED, reason.description());
      case INVALID_REQUEST, NO_CONNECTED_WORKER, WORKER_UNREACHABLE, ATTEMPT_IN_PROGRESS ->
          new ProbeExecutionException(ItemFailureReason.TEMPORARY, reason.description());
    };
  }
}
