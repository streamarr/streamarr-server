package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Dolby Vision Profile Tests")
class DolbyVisionProfileTest {

  @ParameterizedTest(name = "{0} has the compatible base {1}")
  @CsvSource({
    "PROFILE_5, NONE",
    "PROFILE_8_1, PQ",
    "PROFILE_8_4, HLG",
    "PROFILE_10_0, NONE",
    "PROFILE_10_1, PQ",
    "PROFILE_10_4, HLG"
  })
  @DisplayName("Should name the compatible base of its streams when asked for each profile")
  void shouldNameTheCompatibleBaseOfItsStreamsWhenAskedForEachProfile(
      DolbyVisionProfile profile, CompatibleBase base) {
    assertThat(profile.compatibleBase()).isEqualTo(base);
  }
}
