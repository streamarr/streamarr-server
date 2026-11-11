package com.streamarr.server.domain.auth;

/** Lifecycle of a share code (ADR 0024 §Share codes); only a LIVE code admits an offer. */
public enum ShareCodeStatus {
  LIVE,
  SPENT,
  WITHDRAWN,
  EXPIRED,
  INVALIDATED
}
