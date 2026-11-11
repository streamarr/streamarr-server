package com.streamarr.server.services.auth;

/** Share codes made as the application makes them, for unit tests in any package. */
public final class ShareCodesFixture {

  private ShareCodesFixture() {}

  public static ShareCodes shareCodes() {
    return new ShareCodes(new UserCodeGenerator());
  }
}
