package com.streamarr.server.services.streaming;

/**
 * What failed on an encoded lower variant beside a copy, so that the stream session withdraws its
 * lower variants and the copy plays on alone (ADR 0050).
 */
public enum LowerVariantFailure {
  /** The player reported a format failure while it played a lower variant. */
  PLAYER_FORMAT_FAILURE,
  /** A lower variant's job attempt failed in a way that every later attempt repeats. */
  JOB_FAILURE,
  /** The hardware encoder of a lower variant's job attempt failed (ADR 0044). */
  HARDWARE_ENCODER_FAILURE
}
