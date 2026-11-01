package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Video Codec Tier Tests")
class VideoCodecTierTest {

  @Test
  @DisplayName("Should refuse to compare a tier when the caller passes no maximum")
  void shouldRefuseToCompareATierWhenTheCallerPassesNoMaximum() {
    assertThatThrownBy(() -> VideoCodecTier.MAIN.isAtMost(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("maximum");
  }
}
