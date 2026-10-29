package com.streamarr.server.domain.streaming;

/**
 * A job attempt failure that every replacement attempt with the same decision would repeat, so no
 * producer of the same stream session can repair it.
 */
public enum TranscodeFailure {
  OUTPUT_MISMATCH,
  INVALID_SPECIFICATION
}
