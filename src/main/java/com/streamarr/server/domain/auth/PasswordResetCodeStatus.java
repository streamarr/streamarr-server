package com.streamarr.server.domain.auth;

public enum PasswordResetCodeStatus {
  PENDING,
  REDEEMED,
  CANCELED,
  EXPIRED,
  INVALIDATED
}
