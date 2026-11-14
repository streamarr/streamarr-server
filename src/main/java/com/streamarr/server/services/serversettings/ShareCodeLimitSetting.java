package com.streamarr.server.services.serversettings;

import java.util.UUID;
import lombok.NonNull;

/**
 * How many live share codes a Household may hold at once (ADR 0024 §Share codes), with the range a
 * ServerAdmin can choose from. The id is the server settings row's, so every read and change names
 * the same setting.
 */
public record ShareCodeLimitSetting(@NonNull UUID id, int codes) {

  public static final int MINIMUM_CODES = 1;
  public static final int MAXIMUM_CODES = 100;

  public int minimumCodes() {
    return MINIMUM_CODES;
  }

  public int maximumCodes() {
    return MAXIMUM_CODES;
  }

  static boolean allows(int codes) {
    return codes >= MINIMUM_CODES && codes <= MAXIMUM_CODES;
  }
}
