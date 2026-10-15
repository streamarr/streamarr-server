package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Encoder Preset Choice Tests")
class EncoderPresetChoiceTest {

  @ParameterizedTest(name = "{0}")
  @EnumSource(EncoderPreset.class)
  @DisplayName("Should offer a fixed choice for every preset when a ServerAdmin picks one")
  void shouldOfferFixedChoiceForEveryPresetWhenServerAdminPicksOne(EncoderPreset preset) {
    var choice = EncoderPresetChoice.named(preset.name());

    assertThat(choice).isPresent();
    assertThat(choice.get().resolveFor("h264")).isEqualTo(preset);
    assertThat(choice.get().resolveFor("hevc")).isEqualTo(preset);
    assertThat(choice.get().resolveFor("av1")).isEqualTo(preset);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = EncoderPresetChoice.class,
      names = "AUTOMATIC",
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should resolve every fixed choice to its preset when a variant job starts")
  void shouldResolveEveryFixedChoiceToItsPresetWhenVariantJobStarts(EncoderPresetChoice choice) {
    assertThat(choice.resolveFor("h264").name()).isEqualTo(choice.name());
  }
}
