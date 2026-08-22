package com.streamarr.server.graphql.inputs;

/** The PIN is a new secret value; it is consumed synchronously and never echoed. */
public record OverrideProfilePinInput(String profileId, String pin, String reason) {

  @Override
  public String toString() {
    return "OverrideProfilePinInput[profileId=%s, pin=REDACTED, reason=%s]"
        .formatted(profileId, reason);
  }
}
