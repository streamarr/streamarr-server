package com.streamarr.server.domain.auth;

public enum PasswordResetCodeStatus {
  PENDING,
  REDEEMED,
  EXPIRED,
  INVALIDATED
}
