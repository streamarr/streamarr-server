package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("SHA-256 Digest Tests")
class Sha256DigestTest {

  @Test
  @DisplayName("Should reject value when digest is not 32 bytes")
  void shouldRejectValueWhenDigestIsNot32Bytes() {
    var value = new byte[31];

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Sha256Digest(value))
        .withMessage("SHA-256 digest must contain exactly 32 bytes");
  }

  @Test
  @DisplayName("Should protect digest bytes from caller mutation")
  void shouldProtectDigestBytesFromCallerMutation() {
    var value = new byte[32];
    value[0] = 7;
    var digest = new Sha256Digest(value);
    value[0] = 8;
    var exposed = digest.bytes();
    exposed[0] = 9;

    assertThat(digest.bytes()).startsWith(7).hasSize(32);
  }

  @Test
  @DisplayName("Should compare by content without exposing digest bytes in diagnostics")
  void shouldCompareByContentWithoutExposingDigestBytesInDiagnostics() {
    var value = new byte[32];
    value[0] = 7;
    var first = new Sha256Digest(value);
    var second = new Sha256Digest(value.clone());
    var differentValue = value.clone();
    differentValue[0] = 8;

    assertThat(first)
        .isEqualTo(second)
        .hasSameHashCodeAs(second)
        .isNotEqualTo(new Sha256Digest(differentValue))
        .isNotEqualTo("digest")
        .hasToString("Sha256Digest[redacted]");
  }
}
