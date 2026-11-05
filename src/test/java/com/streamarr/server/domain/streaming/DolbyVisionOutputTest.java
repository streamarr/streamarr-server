package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  // ADR 0013: a copy of profile 5 or 10.0 carries the Dolby Vision sample entry, and its CODECS
  // names that entry with the two-digit profile and level.
  @ParameterizedTest
  @CsvSource({"PROFILE_5, 6, DVH1, dvh1.05.06", "PROFILE_10_0, 13, DAV1, dav1.10.13"})
  @DisplayName(
      "Should name the Dolby Vision sample entry and its codec string when the profile has no"
          + " compatible base")
  void shouldNameTheDolbyVisionSampleEntryAndItsCodecStringWhenTheProfileHasNoCompatibleBase(
      DolbyVisionProfile profile, int level, VideoSampleEntry sampleEntry, String codecs) {
    var output = new DolbyVisionOutput(profile, level);

    assertThat(output.dolbyVisionSampleEntry()).contains(sampleEntry);
    assertThat(output.codecs()).contains(codecs);
  }

  // ADR 0013: a copy on a PQ or HLG base keeps its base sample entry, whose CODECS the probe
  // computed.
  @ParameterizedTest
  @EnumSource(
      value = DolbyVisionProfile.class,
      names = {"PROFILE_8_1", "PROFILE_8_4", "PROFILE_10_1", "PROFILE_10_4"})
  @DisplayName(
      "Should name no Dolby Vision sample entry or codec string when the profile has a compatible"
          + " base")
  void shouldNameNoDolbyVisionSampleEntryOrCodecStringWhenTheProfileHasACompatibleBase(
      DolbyVisionProfile profile) {
    var output = new DolbyVisionOutput(profile, 6);

    assertThat(output.dolbyVisionSampleEntry()).isEmpty();
    assertThat(output.codecs()).isEmpty();
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
