package com.streamarr.server.graphql.inputs;

public record OfferProfileShareInput(String profileId, String shareCode) {

  @Override
  public String toString() {
    return "OfferProfileShareInput[profileId=%s, shareCode=REDACTED]".formatted(profileId);
  }
}
