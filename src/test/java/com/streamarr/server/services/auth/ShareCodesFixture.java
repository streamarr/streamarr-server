package com.streamarr.server.services.auth;

import java.util.ArrayDeque;
import java.util.List;

/** Share codes made as the application makes them, for unit tests in any package. */
public final class ShareCodesFixture {

  private ShareCodesFixture() {}

  public static ShareCodes shareCodes() {
    return new ShareCodes(new UserCodeGenerator());
  }

  /** Share codes that draw the given codes in turn, so a test can make a drawn code collide. */
  public static ShareCodes drawing(String... codes) {
    var draws = new ArrayDeque<>(List.of(codes));
    return new ShareCodes(
        new UserCodeGenerator() {
          @Override
          public String generate() {
            return draws.removeFirst();
          }
        });
  }
}
