package com.streamarr.server.graphql.inputs;

/** The PIN is a new secret value; it is consumed synchronously and never echoed. */
public record SetProfilePinInput(String profileId, String pin) {

  @Override
  public String toString() {
    return "SetProfilePinInput[profileId=%s, pin=REDACTED]".formatted(profileId);
  }
}
