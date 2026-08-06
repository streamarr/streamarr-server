package com.streamarr.server.repositories.auth;

/** The atomic outcome of checking issuance capacity and conditionally taking one slot. */
public record DeviceAuthorizationInsertResult(boolean inserted, int outstanding) {

  public DeviceAuthorizationInsertResult {
    if (outstanding < 0) {
      throw new IllegalArgumentException("Outstanding device authorizations cannot be negative.");
    }
    if (inserted && outstanding == 0) {
      throw new IllegalArgumentException("A successful insert must leave one outstanding code.");
    }
  }
}
