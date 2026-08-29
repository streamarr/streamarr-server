package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Palette Tests")
class PaletteTest {

  private static final Swatch TEAL = new Swatch(0x00A0A0, 10);
  private static final Swatch LIGHT_CYAN = new Swatch(0x68F8F8, 6);
  private static final Swatch NAVY = new Swatch(0x103070, 5);
  private static final Swatch DARK_SLATE = new Swatch(0x2A3A36, 50);
  private static final Swatch PALE_GRAY = new Swatch(0xC8D0CC, 8);
  private static final Swatch MID_SLATE = new Swatch(0x7A8A86, 20);
  private static final Swatch GRAY = new Swatch(0x808080, 30);

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
    assertThat(palette.swatchFor(Target.DARK_VIBRANT))
        .as("teal also fits DARK_VIBRANT but exclusive selection spent it on VIBRANT")
        .isEmpty();
  }

  @Test
  @DisplayName("Should prefer the vibrant candidate nearer target lightness when two qualify")
  void shouldPreferVibrantCandidateNearerTargetLightnessWhenTwoQualify() {
    var palette = new Palette(List.of(LIGHT_CYAN, TEAL));

    assertThat(palette.swatchFor(Target.VIBRANT)).contains(TEAL);
    assertThat(palette.swatchFor(Target.LIGHT_VIBRANT)).contains(LIGHT_CYAN);
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
  @DisplayName("Should report the most populous swatch as dominant")
  void shouldReportMostPopulousSwatchAsDominant() {
    var palette = new Palette(List.of(TEAL, DARK_SLATE, GRAY));

    assertThat(palette.dominantSwatch()).isEqualTo(DARK_SLATE);
  }

  @Test
  @DisplayName("Should reject construction when swatch list is empty")
  void shouldRejectConstructionWhenSwatchListIsEmpty() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Palette(List.of()));
  }
}
