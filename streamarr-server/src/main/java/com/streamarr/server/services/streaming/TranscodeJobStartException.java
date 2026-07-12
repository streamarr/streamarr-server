package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.transcode.engine.model.TranscodeJobRef;

public final class TranscodeJobStartException extends RuntimeException {

  private final transient TranscodeJobRef jobRef;
  private final transient StartJobResult result;

  public TranscodeJobStartException(TranscodeJobRef jobRef, StartJobResult result) {
    super("Transcode job " + jobRef + " did not reach an accepted running state");
    this.jobRef = jobRef;
    this.result = result;
  }

  public TranscodeJobRef jobRef() {
    return jobRef;
  }

  public StartJobResult result() {
    return result;
  }
}
