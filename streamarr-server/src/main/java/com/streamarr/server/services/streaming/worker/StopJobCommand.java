package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.util.UUID;
import lombok.Builder;

@Builder
public record StopJobCommand(UUID commandId, WorkerTarget target, TranscodeJobRef jobRef) {

  public StopJobCommand {
    if (commandId == null || target == null || jobRef == null) {
      throw new IllegalArgumentException("Stop command values are required");
    }
  }
}
