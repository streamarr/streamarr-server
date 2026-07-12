package com.streamarr.server.services.streaming.worker;

public interface TranscodeWorkerPort {

  StartJobResult start(StartJobCommand command);

  StopJobResult stop(StopJobCommand command);

  InspectJobResult inspect(InspectJobQuery query);
}
