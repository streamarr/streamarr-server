package com.streamarr.server.services.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
final class DeviceCodeGenerator {

  private static final int DEVICE_CODE_BYTES = 32;

  private final SecureRandom secureRandom;

  DeviceCodeGenerator() {
    this(new SecureRandom());
  }

  DeviceCodeGenerator(SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
  }

  String generate() {
    var bytes = new byte[DEVICE_CODE_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
