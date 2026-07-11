package com.streamarr.server.domain.streaming;

public enum StreamSessionTerminalReason {
  STARTUP_FAILURE,
  OWNER_DESTROY,
  RETENTION_EXPIRED,
  PROVISIONING_TIMEOUT,
  AUTH_REVOKED,
  SOURCE_DELETED
}
