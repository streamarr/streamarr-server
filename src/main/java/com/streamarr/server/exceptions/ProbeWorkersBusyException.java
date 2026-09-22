package com.streamarr.server.exceptions;

/** Every compatible worker's slots are in use; the probe has not started and has not failed. */
public class ProbeWorkersBusyException extends RuntimeException {

  public ProbeWorkersBusyException() {
    super("All compatible workers are busy");
  }
}
