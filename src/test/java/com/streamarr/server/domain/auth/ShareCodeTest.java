package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Code Tests")
class ShareCodeTest {

  @Test
  @DisplayName("Should leave the stored digest out when a code is rendered")
  void shouldLeaveStoredDigestOutWhenCodeIsRendered() {
    var digest = new byte[32];
    Arrays.fill(digest, (byte) 0x5a);
    var shareCode =
        ShareCode.builder()
            .householdId(UUID.randomUUID())
            .issuerAccountId(UUID.randomUUID())
            .codeDigest(digest)
            .expiresAt(Instant.parse("2026-11-17T12:00:00Z"))
            .build();

    assertThat(shareCode.toString())
        .contains("codeDigest=REDACTED")
        .doesNotContain(Arrays.toString(digest))
        .doesNotContain(HexFormat.of().formatHex(digest))
        .doesNotContain(Base64.getEncoder().encodeToString(digest));
  }
}
