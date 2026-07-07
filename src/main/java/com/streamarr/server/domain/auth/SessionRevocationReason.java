package com.streamarr.server.domain.auth;

public enum SessionRevocationReason {
  LOGOUT,
  TOKEN_REUSE,
  PASSWORD_CHANGE,
  ADMIN
}
