package com.streamarr.server.repositories.streaming;

/** A stored calibration document that this server cannot read. */
public class InvalidCalibrationDocumentException extends RuntimeException {

  InvalidCalibrationDocumentException(String message, Throwable cause) {
    super(message, cause);
  }

  InvalidCalibrationDocumentException(String message) {
    super(message);
  }
}
