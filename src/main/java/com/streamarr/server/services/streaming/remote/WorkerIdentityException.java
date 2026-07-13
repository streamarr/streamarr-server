package com.streamarr.server.services.streaming.remote;

public final class WorkerIdentityException extends RuntimeException {

  public WorkerIdentityException() {
    super("Worker certificate does not contain one allowed SPIFFE identity");
  }
}
