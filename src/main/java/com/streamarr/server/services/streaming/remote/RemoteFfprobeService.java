package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
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
    var pending =
        server
            .dispatchProbe(
                ProbeRequest.newBuilder()
                    .setProbeAttemptId(toProto(request.attemptId()))
                    .setProbeVersion(request.probeVersion())
                    .setSource(sourceMapper.map(request.sourcePath()))
                    .build())
            .orElseThrow(ProbeExecutionException::new);
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
}
