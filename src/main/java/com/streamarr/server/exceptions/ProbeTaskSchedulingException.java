package com.streamarr.server.exceptions;

import java.io.IOException;
import java.util.UUID;

public class ProbeTaskSchedulingException extends RuntimeException {

  public ProbeTaskSchedulingException(UUID mediaFileId, IOException cause) {
    super("Could not read source snapshot for media file: " + mediaFileId, cause);
  }
}
