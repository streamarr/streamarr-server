package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Video Codec Tier Tests")
class VideoCodecTierTest {

  @ParameterizedTest(name = "{0} at most {1}: {2}")
  @CsvSource({"MAIN, MAIN, true", "MAIN, HIGH, true", "HIGH, HIGH, true", "HIGH, MAIN, false"})
  @DisplayName("Should place a tier at or below the maximum only when the maximum is not lower")
  void shouldPlaceATierAtOrBelowTheMaximumOnlyWhenTheMaximumIsNotLower(
      VideoCodecTier tier, VideoCodecTier maximum, boolean atMost) {
    assertThat(tier.isAtMost(maximum)).isEqualTo(atMost);
  }

  @Test
  @DisplayName("Should refuse to compare a tier when the caller passes no maximum")
  void shouldRefuseToCompareATierWhenTheCallerPassesNoMaximum() {
    assertThatThrownBy(() -> VideoCodecTier.MAIN.isAtMost(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("maximum");
  }
}
