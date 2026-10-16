package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Video Encode Decision Tests")
class VideoEncodeDecisionTest {

  @ParameterizedTest(name = "{0}x{1} at {2} fps and {3} b/s → level {4}, {5}")
  @CsvSource({
    // The measured cases from streamarr/streamarr-server#501.
    "1920, 1080, 60, 5000000, 42, avc1.64002A",
    "1920, 1080, 30, 5000000, 40, avc1.640028",
    "1280, 720, 60, 3000000, 32, avc1.640020",
    "1920, 1080, 59.94005994005994, 5000000, 42, avc1.64002A",
    "1920, 1080, 23.976, 5000000, 40, avc1.640028",
    // A portrait source after rotation, as FFmpeg scales it to 1080 lines.
    "608, 1080, 30, 5000000, 31, avc1.64001F",
    "854, 480, 23.976, 1500000, 30, avc1.64001E",
    "640, 360, 23.976, 800000, 30, avc1.64001E",
    "256, 144, 30, 200000, 12, avc1.64000C",
    // The buffer size, twice the bitrate, exceeds level 4.0's 31,250,000 bits.
    "1920, 1080, 30, 24000000, 41, avc1.640029",
    // The bitrate exceeds level 4.1's 62,500,000 b/s, so only level 5.0 allows it.
    "1920, 1080, 30, 70000000, 50, avc1.640032",
    // Four frames of 396 macroblocks exceed level 1.1's 900-macroblock decoded picture buffer.
    "352, 288, 1, 100000, 12, avc1.64000C",
    // 256 macroblocks across exceed the width that every level below 4.0 allows.
    "4096, 64, 1, 100000, 40, avc1.640028",
    "3840, 2160, 60, 40000000, 52, avc1.640034",
    "7680, 4320, 60, 100000000, 61, avc1.64003D",
  })
  @DisplayName("Should force H.264 High at the smallest Table A-1 level when the output fits it")
  void shouldForceH264HighAtSmallestTableA1LevelWhenTheOutputFitsIt(
      int width, int height, double frameRate, long bitrate, int level, String codecString) {
    var decision =
        VideoEncodeDecision.of(
            output("h264")
                .width(width)
                .height(height)
                .frameRate(frameRate)
                .bitrate(bitrate)
                .build());

    assertThat(decision.codecProfile()).isEqualTo(VideoCodecProfile.H264_HIGH);
    assertThat(decision.tier()).isEmpty();
    assertThat(decision.level()).isEqualTo(level);
    assertThat(decision.codecString()).isEqualTo(codecString);
  }

  @ParameterizedTest(name = "{0}x{1} at {2} fps and {3} b/s → level {4}, {5}")
  @CsvSource({
    // The measured cases from streamarr/streamarr-server#501.
    "1920, 1080, 60, 5000000, 41, av01.0.09M.08",
    "1920, 1080, 30, 5000000, 40, av01.0.08M.08",
    "1280, 720, 60, 3000000, 40, av01.0.08M.08",
    // A portrait source after rotation, as FFmpeg scales it to 1080 lines.
    "608, 1080, 30, 5000000, 30, av01.0.04M.08",
    "854, 480, 23.976, 1500000, 30, av01.0.04M.08",
    "640, 360, 23.976, 800000, 21, av01.0.01M.08",
    "256, 144, 30, 200000, 20, av01.0.00M.08",
    // The bitrate exceeds level 4.0's 12 Mb/s in the Main tier.
    "1920, 1080, 30, 15000000, 41, av01.0.09M.08",
    // 4,096 columns exceed level 2.1's widest picture of 2,816 columns.
    "4096, 64, 1, 100000, 30, av01.0.04M.08",
    // 2,560 lines exceed level 3.0's tallest picture of 2,448 lines.
    "64, 2560, 1, 100000, 31, av01.0.05M.08",
    "3840, 2160, 60, 40000000, 51, av01.0.13M.08",
    "7680, 4320, 60, 100000000, 61, av01.0.17M.08",
  })
  @DisplayName("Should force AV1 Main in the Main tier at the smallest Annex A level")
  void shouldForceAv1MainInTheMainTierAtTheSmallestAnnexALevel(
      int width, int height, double frameRate, long bitrate, int level, String codecString) {
    var decision =
        VideoEncodeDecision.of(
            output("av1")
                .width(width)
                .height(height)
                .frameRate(frameRate)
                .bitrate(bitrate)
                .build());

    assertThat(decision.codecProfile()).isEqualTo(VideoCodecProfile.AV1_MAIN);
    assertThat(decision.tier()).isEqualTo(Optional.of(VideoCodecTier.MAIN));
    assertThat(decision.level()).isEqualTo(level);
    assertThat(decision.codecString()).isEqualTo(codecString);
  }

  @ParameterizedTest
  @CsvSource({"h264", "av1"})
  @DisplayName("Should refuse the encode when no level allows the output")
  void shouldRefuseTheEncodeWhenNoLevelAllowsTheOutput(String codecFamily) {
    var output =
        output(codecFamily).width(7680).height(4320).frameRate(240).bitrate(100_000_000L).build();

    assertThatThrownBy(() -> VideoEncodeDecision.of(output))
        .isInstanceOf(UnsupportedVideoEncodeException.class)
        .hasMessageContaining("7680x4320");
  }

  @Test
  @DisplayName("Should refuse the encode when the codec family has no level table")
  void shouldRefuseTheEncodeWhenTheCodecFamilyHasNoLevelTable() {
    var output = output("hevc").width(1920).height(1080).frameRate(24).bitrate(5_000_000L).build();

    assertThatThrownBy(() -> VideoEncodeDecision.of(output))
        .isInstanceOf(UnsupportedVideoEncodeException.class)
        .hasMessageContaining("hevc");
  }

  @Test
  @DisplayName("Should write the codec string with ASCII digits when the default locale differs")
  void shouldWriteTheCodecStringWithAsciiDigitsWhenTheDefaultLocaleDiffers() {
    var defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      var h264 =
          VideoEncodeDecision.of(
              output("h264").width(1920).height(1080).frameRate(60).bitrate(5_000_000L).build());
      var av1 =
          VideoEncodeDecision.of(
              output("av1").width(1920).height(1080).frameRate(60).bitrate(5_000_000L).build());

      assertThat(h264.codecString()).isEqualTo("avc1.64002A");
      assertThat(av1.codecString()).isEqualTo("av01.0.09M.08");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  private static EncodedVideoOutput.EncodedVideoOutputBuilder output(String codecFamily) {
    return EncodedVideoOutput.builder().codecFamily(codecFamily);
  }
}
