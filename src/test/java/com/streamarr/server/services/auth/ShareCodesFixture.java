package com.streamarr.server.services.auth;

import com.streamarr.server.properties.CredentialCodeProperties;
import java.util.ArrayDeque;
import java.util.List;

/** Share codes made as the application makes them, for unit tests in any package. */
public final class ShareCodesFixture {

  /** The test profile's share code key, so a unit test and a test server agree on digests. */
  public static final String KEY = "c2hhcmUtY29kZS10ZXN0LWtleS1vZi0zMi1ieXRlcyE=";

  private ShareCodesFixture() {}

  public static ShareCodes shareCodes() {
    return keyedBy(KEY);
  }

  public static ShareCodes keyedBy(String configuredKey) {
    return drawnBy(new UserCodeGenerator(), configuredKey);
  }

  /** Share codes that draw the given codes in turn, so a test can make a drawn code collide. */
  public static ShareCodes drawing(String... codes) {
    var draws = new ArrayDeque<>(List.of(codes));
    return drawnBy(
        new UserCodeGenerator() {
          @Override
          public String generate() {
            return draws.removeFirst();
          }
        },
        KEY);
  }

  private static ShareCodes drawnBy(UserCodeGenerator generator, String configuredKey) {
    return new ShareCodes(
        generator, CredentialCodeProperties.builder().shareCodeKey(configuredKey).build());
  }
}
