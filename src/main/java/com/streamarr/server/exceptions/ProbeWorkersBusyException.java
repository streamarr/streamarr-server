package com.streamarr.server.exceptions;

/**
 * No connected worker can start the probe now: every compatible worker's probe capacity is in use,
 * or no compatible worker is connected. The probe has not started and has not failed.
 */
public class ProbeWorkersBusyException extends RuntimeException {

  public ProbeWorkersBusyException(String reason) {
    super(reason);
  }
}
