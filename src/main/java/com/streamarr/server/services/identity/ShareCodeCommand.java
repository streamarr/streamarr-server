package com.streamarr.server.services.identity;

import lombok.Builder;
import lombok.NonNull;

/** A share code as a person typed it, and the address of the request that presents it. */
@Builder
public record ShareCodeCommand(String code, @NonNull String ipAddress) {

  @Override
  public String toString() {
    return "ShareCodeCommand[code=REDACTED, ipAddress=%s]".formatted(ipAddress);
  }

  public static class ShareCodeCommandBuilder {

    @Override
    public String toString() {
      return "ShareCodeCommandBuilder[REDACTED]";
    }
  }
}
