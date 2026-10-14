package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Encoder Preset Tests")
class EncoderPresetTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"h264, FASTER", "hevc, VERYFAST", "av1, FASTER", "vp9, FASTER"})
  @DisplayName("Should resolve Automatic per video codec family when a variant job starts")
  void shouldResolveAutomaticPerVideoCodecFamilyWhenVariantJobStarts(
      String videoCodecFamily, EncoderPreset expected) {
    assertThat(EncoderPreset.automaticFor(videoCodecFamily)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should reject a null video codec family when resolving Automatic")
  void shouldRejectNullVideoCodecFamilyWhenResolvingAutomatic() {
    assertThatThrownBy(() -> EncoderPreset.automaticFor(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("videoCodecFamily");
  }
}
