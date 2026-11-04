package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
  @CsvSource(
      textBlock =
          """
      # width, height, frames, seconds, level
       1280,   720,     24,     1,     1
       1280,   720,  24001,  1000,     2
       1280,   720,     30,     1,     2
       1920,  1080,  24000,  1001,     3
       1920,  1080,     24,     1,     3
       2560,   720,     24,     1,     4
       1920,  1080,     25,     1,     4
       1920,  1080,  30000,  1001,     4
       1920,  1080,     30,     1,     4
       3840,  1080,     24,     1,     5
       2560,  1440,     24,     1,     5
       1920,  1080,     50,     1,     5
       1920,  1080,     60,     1,     5
       3840,  1600,     24,     1,     6
       3840,  2160,  24000,  1001,     6
       3840,  2160,     24,     1,     6
       3840,  2160,     25,     1,     7
       3840,  2160,     30,     1,     7
       3840,  2160,     48,     1,     8
       3840,  2160,     50,     1,     9
       3840,  2160,  60000,  1001,     9
       3840,  2160,     60,     1,     9
       3840,  2160,    100,     1,    10
       3840,  2160,    120,     1,    10
       7680,  4320,     24,     1,    11
       7680,  4320,     30,     1,    11
       7680,  4320,     60,     1,    12
       7680,  4320,    120,     1,    13
      """)
  @DisplayName(
      "Should give the lowest level whose pixel rate and width carry the picture when a level"
          + " carries it")
  void shouldGiveTheLowestLevelWhosePixelRateAndWidthCarryThePictureWhenALevelCarriesIt(
      int width, int height, int frames, int seconds, int expectedLevel) {
    var level =
        DolbyVisionLevelTable.lowestLevelFor(
            new PictureSize(width, height), new Fraction(frames, seconds));

    assertThat(level).isEqualTo(OptionalInt.of(expectedLevel));
  }

  @ParameterizedTest(name = "{0}×{1} at {2}/{3} fps")
  @CsvSource(
      textBlock =
          """
      # width, height, frames, seconds
       7680,  4320, 120001,  1000
       7680,  4320,    240,     1
       7681,  1080,     24,     1
      """)
  @DisplayName("Should give no level when the picture exceeds every level of the table")
  void shouldGiveNoLevelWhenThePictureExceedsEveryLevelOfTheTable(
      int width, int height, int frames, int seconds) {
    var level =
        DolbyVisionLevelTable.lowestLevelFor(
            new PictureSize(width, height), new Fraction(frames, seconds));

    assertThat(level).isEmpty();
  }

  @ParameterizedTest(name = "level {0}")
  @CsvSource({"0, false", "1, true", "13, true", "14, false"})
  @DisplayName("Should hold only the levels from 1 to 13 when asked for a level number")
  void shouldHoldOnlyTheLevelsFrom1To13WhenAskedForALevelNumber(int level, boolean held) {
    assertThat(DolbyVisionLevelTable.isTableLevel(level)).isEqualTo(held);
  }
}
