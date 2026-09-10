package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Palette Tests")
class PaletteTest {

  private static final Swatch TEAL = swatch().rgb(0x00A0A0).population(10).build();
  private static final Swatch LIGHT_CYAN = swatch().rgb(0x68F8F8).population(6).build();
  private static final Swatch NAVY = swatch().rgb(0x103070).population(5).build();
  private static final Swatch DARK_SLATE = swatch().rgb(0x2A3A36).population(50).build();
  private static final Swatch PALE_GRAY = swatch().rgb(0xC8D0CC).population(8).build();
  private static final Swatch MID_SLATE = swatch().rgb(0x7A8A86).population(20).build();
  private static final Swatch GRAY = swatch().rgb(0x808080).population(30).build();

  @Test
  @DisplayName("Should select one swatch per target when artwork covers every profile")
  void shouldSelectOneSwatchPerTargetWhenArtworkCoversEveryProfile() {
    var palette = new Palette(List.of(TEAL, LIGHT_CYAN, NAVY, DARK_SLATE, PALE_GRAY, MID_SLATE));

    assertThat(palette.swatchFor(Target.VIBRANT)).contains(TEAL);
    assertThat(palette.swatchFor(Target.LIGHT_VIBRANT)).contains(LIGHT_CYAN);
    assertThat(palette.swatchFor(Target.DARK_VIBRANT)).contains(NAVY);
    assertThat(palette.swatchFor(Target.LIGHT_MUTED)).contains(PALE_GRAY);
    assertThat(palette.swatchFor(Target.MUTED)).contains(MID_SLATE);
    assertThat(palette.swatchFor(Target.DARK_MUTED)).contains(DARK_SLATE);
  }

  @Test
  @DisplayName("Should leave a target empty when its only candidate was claimed by vibrant")
  void shouldLeaveTargetEmptyWhenItsOnlyCandidateWasClaimedByVibrant() {
    var palette = new Palette(List.of(TEAL, GRAY));

    assertThat(palette.swatchFor(Target.VIBRANT)).contains(TEAL);
    assertThat(palette.swatchFor(Target.DARK_VIBRANT)).isEmpty();
  }

  @Test
  @DisplayName("Should prefer target lightness when saturation and population are equal")
  void shouldPreferTargetLightnessWhenSaturationAndPopulationAreEqual() {
    var distant = swatch().rgb(0xA00000).population(10).build();
    var near = swatch().rgb(0xF80000).population(10).build();

    assertThat(new Palette(List.of(distant, near)).swatchFor(Target.VIBRANT)).contains(near);
  }

  @Test
  @DisplayName("Should prefer target saturation when lightness and population are equal")
  void shouldPreferTargetSaturationWhenLightnessAndPopulationAreEqual() {
    var muted = swatch().rgb(0xC04040).population(10).build();
    var saturated = swatch().rgb(0xFF0101).population(10).build();

    assertThat(new Palette(List.of(muted, saturated)).swatchFor(Target.VIBRANT))
        .contains(saturated);
  }

  @Test
  @DisplayName("Should prefer larger population when saturation and lightness are equal")
  void shouldPreferLargerPopulationWhenSaturationAndLightnessAreEqual() {
    var rare = swatch().rgb(0xF80000).population(5).build();
    var common = swatch().rgb(0x00F800).population(10).build();

    assertThat(new Palette(List.of(rare, common)).swatchFor(Target.VIBRANT)).contains(common);
  }

  @ParameterizedTest(name = "dominant population {0}: vibrant RGB {1}")
  @CsvSource({"60, 0xB00000", "100, 0xF80000"})
  @DisplayName("Should normalize population to the dominant swatch when it cannot fill the target")
  void shouldNormalizePopulationToDominantSwatchWhenItCannotFillTarget(
      int population, int expected) {
    var dominant = swatch().rgb(0x808080).population(population).build();
    var near = swatch().rgb(0xF80000).population(10).build();
    var populous = swatch().rgb(0xB00000).population(30).build();

    assertThat(new Palette(List.of(dominant, near, populous)).swatchFor(Target.VIBRANT))
        .map(Swatch::rgb)
        .contains(expected);
  }

  @Test
  @DisplayName("Should retain the first candidate when target scores are tied")
  void shouldRetainFirstCandidateWhenTargetScoresAreTied() {
    var first = swatch().rgb(0xF80000).population(10).build();
    var second = swatch().rgb(0x00F800).population(10).build();

    assertThat(new Palette(List.of(first, second)).swatchFor(Target.VIBRANT)).contains(first);
    assertThat(new Palette(List.of(second, first)).swatchFor(Target.VIBRANT)).contains(second);
  }

  @Test
  @DisplayName("Should return empty when no swatch fits the target")
  void shouldReturnEmptyWhenNoSwatchFitsTheTarget() {
    var palette = new Palette(List.of(GRAY));

    assertThat(palette.swatchFor(Target.VIBRANT)).isEmpty();
    assertThat(palette.swatchFor(Target.LIGHT_VIBRANT)).isEmpty();
    assertThat(palette.swatchFor(Target.DARK_VIBRANT)).isEmpty();
  }

  @Test
  @DisplayName("Should report the dominant swatch when it cannot fill the vibrant target")
  void shouldReportDominantSwatchWhenItCannotFillVibrantTarget() {
    var palette = new Palette(List.of(TEAL, DARK_SLATE, GRAY));

    assertThat(palette.dominantSwatch()).isEqualTo(DARK_SLATE);
    assertThat(palette.swatchFor(Target.VIBRANT)).contains(TEAL);
  }

  @Test
  @DisplayName("Should reject construction when swatch list is empty")
  void shouldRejectConstructionWhenSwatchListIsEmpty() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Palette(List.of()));
  }

  @Builder(builderMethodName = "swatch")
  private static Swatch sampleSwatch(int rgb, int population) {
    return new Swatch(rgb, population);
  }
}
