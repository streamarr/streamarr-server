package com.streamarr.server.fixtures;

import java.security.SecureRandom;
import java.util.Base64;

public final class RefreshProposalFixture {

  private static final int PROPOSAL_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private RefreshProposalFixture() {}

  /** A fresh 256-bit successor in the canonical unpadded base64url form the contract requires. */
  public static String proposal() {
    var bytes = new byte[PROPOSAL_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
