package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each expected level comes from Dolby's level table, as FFmpeg 8.1.3 copies it in
 * libavcodec/dovi_rpuenc.c. A level bounds the pixel rate (width × height × frame rate) and the
 * width. Its pixel rates are 1280×720 at 24 and 30 fps (levels 1 and 2), 1920×1080 at 24, 30 and 60
 * fps (3 to 5), 3840×2160 at 24, 30, 48, 60 and 120 fps (6 to 10), and 7680×4320 at 30, 60 and 120
 * fps (11 to 13). Its widths are 1280 up to level 2, 1920 at level 3, 2560 at level 4, 3840 from
 * level 5 to 10, and 7680 from level 11.
 */
@Tag("UnitTest")
@DisplayName("Dolby Vision Level Table Tests")
class DolbyVisionLevelTableTest {

  @ParameterizedTest(name = "{0}×{1} at {2}/{3} fps is level {4}")
  @MethodSource("picturesWithinALevel")
  @DisplayName(
      "Should give the lowest level whose pixel rate and width carry the picture when a level"
          + " carries it")
  void shouldGiveTheLowestLevelWhosePixelRateAndWidthCarryThePictureWhenALevelCarriesIt(
      int width, int height, int rateNumerator, int rateDenominator, int expectedLevel) {
    var level =
        DolbyVisionLevelTable.lowestLevelFor(
            new PictureSize(width, height), new Fraction(rateNumerator, rateDenominator));

    assertThat(level).isEqualTo(OptionalInt.of(expectedLevel));
  }

  static Stream<Arguments> picturesWithinALevel() {
    return Stream.of(
        Arguments.of(1280, 720, 24, 1, 1),
        Arguments.of(1280, 720, 24_001, 1_000, 2),
        Arguments.of(1280, 720, 30, 1, 2),
        Arguments.of(1920, 1080, 24_000, 1_001, 3),
        Arguments.of(1920, 1080, 24, 1, 3),
        Arguments.of(2560, 720, 24, 1, 4),
        Arguments.of(1920, 1080, 25, 1, 4),
        Arguments.of(1920, 1080, 30_000, 1_001, 4),
        Arguments.of(1920, 1080, 30, 1, 4),
        Arguments.of(3840, 1080, 24, 1, 5),
        Arguments.of(2560, 1440, 24, 1, 5),
        Arguments.of(1920, 1080, 50, 1, 5),
        Arguments.of(1920, 1080, 60, 1, 5),
        Arguments.of(3840, 1600, 24, 1, 6),
        Arguments.of(3840, 2160, 24_000, 1_001, 6),
        Arguments.of(3840, 2160, 24, 1, 6),
        Arguments.of(3840, 2160, 25, 1, 7),
        Arguments.of(3840, 2160, 30, 1, 7),
        Arguments.of(3840, 2160, 48, 1, 8),
        Arguments.of(3840, 2160, 50, 1, 9),
        Arguments.of(3840, 2160, 60_000, 1_001, 9),
        Arguments.of(3840, 2160, 60, 1, 9),
        Arguments.of(3840, 2160, 100, 1, 10),
        Arguments.of(3840, 2160, 120, 1, 10),
        Arguments.of(7680, 4320, 24, 1, 11),
        Arguments.of(7680, 4320, 30, 1, 11),
        Arguments.of(7680, 4320, 60, 1, 12),
        Arguments.of(7680, 4320, 120, 1, 13));
  }

  @ParameterizedTest(name = "{0}×{1} at {2}/{3} fps")
  @MethodSource("picturesAboveEveryLevel")
  @DisplayName("Should give no level when the picture exceeds every level of the table")
  void shouldGiveNoLevelWhenThePictureExceedsEveryLevelOfTheTable(
      int width, int height, int rateNumerator, int rateDenominator) {
    var level =
        DolbyVisionLevelTable.lowestLevelFor(
            new PictureSize(width, height), new Fraction(rateNumerator, rateDenominator));

    assertThat(level).isEmpty();
  }

  static Stream<Arguments> picturesAboveEveryLevel() {
    return Stream.of(
        Arguments.of(7680, 4320, 120_001, 1_000),
        Arguments.of(7680, 4320, 240, 1),
        Arguments.of(7681, 1080, 24, 1));
  }

  @ParameterizedTest(name = "level {0}")
  @CsvSource({"0, false", "1, true", "13, true", "14, false"})
  @DisplayName("Should hold only the levels from 1 to 13 when asked for a level number")
  void shouldHoldOnlyTheLevelsFrom1To13WhenAskedForALevelNumber(int level, boolean held) {
    assertThat(DolbyVisionLevelTable.isTableLevel(level)).isEqualTo(held);
  }
}
