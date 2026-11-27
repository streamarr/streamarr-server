package com.streamarr.server.services.identity;

import lombok.NonNull;

/** The answer to issuing a share code: the only place the code itself appears. */
public record NewShareCode(@NonNull ShareCodeWithIssuer shareCode, @NonNull String code) {

  @Override
  public String toString() {
    return "NewShareCode[shareCode=%s, code=REDACTED]".formatted(shareCode.shareCode().getId());
  }
}
