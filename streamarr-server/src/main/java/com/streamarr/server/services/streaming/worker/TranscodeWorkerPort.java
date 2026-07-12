package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobObservation;

public interface TranscodeWorkerPort {

  StartJobResult start(StartJobCommand command);

  StopJobResult stop(StopJobCommand command);

  TranscodeJobObservation inspect(InspectJobQuery query);
}
