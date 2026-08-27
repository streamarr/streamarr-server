package com.streamarr.server.graphql.inputs;

public record DeclineManagerInvitationInput(String code) {

  @Override
  public String toString() {
    return "DeclineManagerInvitationInput[code=REDACTED]";
  }
}
