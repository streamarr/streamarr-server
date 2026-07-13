package com.streamarr.transcode.worker;

final class WorkerJobException extends RuntimeException {

  WorkerJobException(String message) {
    super(message);
  }

  WorkerJobException(String message, Throwable cause) {
    super(message, cause);
  }
}
