package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Dolby Vision Output Tests")
class DolbyVisionOutputTest {

  // ADR 0013: a profile without a compatible base carries its Dolby Vision in CODECS.
  @ParameterizedTest
  @EnumSource(
      value = DolbyVisionProfile.class,
      names = {"PROFILE_5", "PROFILE_10_0"})
  @DisplayName("Should name no supplemental codec when the profile has no compatible base")
  void shouldNameNoSupplementalCodecWhenTheProfileHasNoCompatibleBase(DolbyVisionProfile profile) {
    assertThat(new DolbyVisionOutput(profile, 6).supplementalCodecs()).isEmpty();
  }

  // RFC 6381 codec strings use ASCII digits, whatever the server's format locale.
  @Test
  @DisplayName("Should write the level in ASCII digits when the default format locale uses others")
  void shouldWriteTheLevelInAsciiDigitsWhenTheDefaultFormatLocaleUsesOthers() {
    var original = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6).supplementalCodecs())
          .contains("dvh1.08.06/db1p");
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, original);
    }
  }
}
