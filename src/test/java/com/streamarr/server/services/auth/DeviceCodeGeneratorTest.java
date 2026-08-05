package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Code Generator Tests")
class DeviceCodeGeneratorTest {

  @Test
  @DisplayName(
      "Should encode all 256 random bits as canonical base64url when generating a device code")
  void shouldEncodeAll256RandomBitsAsCanonicalBase64urlWhenGeneratingDeviceCode() {
    var bytes = new byte[32];
    for (var index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) index;
    }
    var generator = new DeviceCodeGenerator(fixedRandom(bytes));

    var deviceCode = generator.generate();

    assertThat(deviceCode)
        .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        .hasSize(43);
  }

  private static SecureRandom fixedRandom(byte[] entropy) {
    return new SecureRandom() {
      @Override
      public void nextBytes(byte[] bytes) {
        assertThat(bytes).hasSameSizeAs(entropy);
        System.arraycopy(entropy, 0, bytes, 0, bytes.length);
      }
    };
  }
}
