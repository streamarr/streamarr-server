package com.streamarr.server.graphql.dto;

/** The code appears here once and never again. */
public record IssuedShareCodeDetails(ShareCodeDetails shareCode, String code) {

  @Override
  public String toString() {
    return "IssuedShareCodeDetails[shareCode=%s, code=REDACTED]".formatted(shareCode.id());
  }
}
