package com.streamarr.server.controllers.auth;

/**
 * A bearer client sends both fields; the proposed successor is what makes its rotation recoverable
 * (ADR 0020). A browser sends neither — its refresh cookie is the carrier — and a proposal
 * alongside cookie mode is rejected rather than ignored.
 */
public record RefreshRequest(String refreshToken, String proposedRefreshToken) {

  @Override
  public String toString() {
    return "RefreshRequest[refreshToken=REDACTED, proposedRefreshToken=REDACTED]";
  }
}
