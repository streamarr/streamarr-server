package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Opaque Codes Tests")
class OpaqueCodesTest {

  private final OpaqueCodes codes = new OpaqueCodes();

  @Test
  @DisplayName("Should issue a publicId.secret pair when an opaque code is generated")
  void shouldIssuePublicIdSecretPairWhenOpaqueCodeIsGenerated() {
    var issued = codes.issue();

    assertThat(issued.code()).startsWith(issued.publicId() + ".");
    var presented = codes.parse(issued.code()).orElseThrow();
    assertThat(presented.publicId()).isEqualTo(issued.publicId());
    assertThat(codes.matches(presented, issued.digest())).isTrue();
    assertThat(
            codes.matches(
                new OpaqueCodes.PresentedCode(issued.publicId(), "not-the-secret"),
                issued.digest()))
        .isFalse();
  }

  @Test
  @DisplayName("Should refuse malformed shapes when an opaque code is parsed")
  void shouldRefuseMalformedShapesWhenOpaqueCodeIsParsed() {
    assertThat(codes.parse(null)).isEmpty();
    assertThat(codes.parse("")).isEmpty();
    assertThat(codes.parse("nodot")).isEmpty();
    assertThat(codes.parse(".secretonly")).isEmpty();
    assertThat(codes.parse("publiconly.")).isEmpty();
  }

  @Test
  @DisplayName("Should avoid repetition and secret leakage when opaque codes are issued")
  void shouldAvoidRepetitionAndSecretLeakageWhenOpaqueCodesAreIssued() {
    var first = codes.issue();
    var second = codes.issue();

    assertThat(first.code()).isNotEqualTo(second.code());
    assertThat(first.publicId()).isNotEqualTo(second.publicId());
    assertThat(first.toString()).doesNotContain(first.code().split("\\.")[1]);
    assertThat(codes.parse(first.code()).orElseThrow().toString())
        .doesNotContain(first.code().split("\\.")[1]);
  }
}
