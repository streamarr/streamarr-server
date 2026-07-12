package com.streamarr.server.services.streaming.worker;

public enum StopJobRejection {
  TARGET_MISMATCH,
  STALE_GENERATION,
  COMMAND_CONFLICT
}
