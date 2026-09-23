package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
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
      throw new ProbeExecutionException(exception);
    } catch (ExecutionException exception) {
      throw new ProbeExecutionException(exception);
    }
  }

  private static RuntimeException refusal(ProbeRefusal reason) {
    if (reason == ProbeRefusal.WORKERS_BUSY) {
      return new ProbeWorkersBusyException();
    }

    return new ProbeExecutionException(reason.description());
  }
}
