package com.streamarr.server.services.auth;

import com.streamarr.server.properties.CredentialCodeProperties;

/** Share codes made as the application makes them, for unit tests in any package. */
public final class ShareCodesFixture {

  /** The test profile's share code key, so a unit test and a test server agree on digests. */
  public static final String KEY = "c2hhcmUtY29kZS10ZXN0LWtleS1vZi0zMi1ieXRlcyE=";

  private ShareCodesFixture() {}

  public static ShareCodes shareCodes() {
    return keyedBy(KEY);
  }

  public static ShareCodes keyedBy(String configuredKey) {
    return new ShareCodes(
        new UserCodeGenerator(),
        CredentialCodeProperties.builder().shareCodeKey(configuredKey).build());
  }
}
