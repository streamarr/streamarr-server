package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import java.util.UUID;
import lombok.Builder;

@Builder
public record StartJobCommand(UUID commandId, WorkerTarget target, TranscodeJobSpec specification) {

  public StartJobCommand {
    if (commandId == null || target == null || specification == null) {
      throw new IllegalArgumentException("Start command values are required");
    }
  }
}
