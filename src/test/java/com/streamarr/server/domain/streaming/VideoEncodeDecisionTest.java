package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Encode Decision Tests")
class VideoEncodeDecisionTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("h264LevelCases")
  @DisplayName("Should force H.264 High at the smallest Table A-1 level when the output fits it")
  void shouldForceH264HighAtSmallestTableA1LevelWhenTheOutputFitsIt(LevelCase levelCase) {
    var decision =
        VideoEncodeDecision.of(
            output("h264")
                .width(levelCase.width())
                .height(levelCase.height())
                .frameRate(levelCase.frameRate())
                .bitrate(levelCase.bitrate())
                .build());

    assertThat(decision.codecProfile()).isEqualTo(VideoCodecProfile.H264_HIGH);
    assertThat(decision.tier()).isEmpty();
    assertThat(decision.level()).isEqualTo(levelCase.level());
    assertThat(decision.codecString()).isEqualTo(levelCase.codecString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("av1LevelCases")
  @DisplayName("Should force AV1 Main in the Main tier at the smallest Annex A level")
  void shouldForceAv1MainInTheMainTierAtTheSmallestAnnexALevel(LevelCase levelCase) {
    var decision =
        VideoEncodeDecision.of(
            output("av1")
                .width(levelCase.width())
                .height(levelCase.height())
                .frameRate(levelCase.frameRate())
                .bitrate(levelCase.bitrate())
                .build());

    assertThat(decision.codecProfile()).isEqualTo(VideoCodecProfile.AV1_MAIN);
    assertThat(decision.tier()).isEqualTo(Optional.of(VideoCodecTier.MAIN));
    assertThat(decision.level()).isEqualTo(levelCase.level());
    assertThat(decision.codecString()).isEqualTo(levelCase.codecString());
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

  private static Stream<LevelCase> h264LevelCases() {
    return Stream.of(
        // The measured cases from streamarr/streamarr-server#501.
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(60)
            .bitrate(5000000L)
            .level(42)
            .codecString("avc1.64002A")
            .build(),
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(30)
            .bitrate(5000000L)
            .level(40)
            .codecString("avc1.640028")
            .build(),
        LevelCase.builder()
            .width(1280)
            .height(720)
            .frameRate(60)
            .bitrate(3000000L)
            .level(32)
            .codecString("avc1.640020")
            .build(),
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(59.94005994005994)
            .bitrate(5000000L)
            .level(42)
            .codecString("avc1.64002A")
            .build(),
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(23.976)
            .bitrate(5000000L)
            .level(40)
            .codecString("avc1.640028")
            .build(),
        // A portrait source after rotation, as FFmpeg scales it to 1080 lines.
        LevelCase.builder()
            .width(608)
            .height(1080)
            .frameRate(30)
            .bitrate(5000000L)
            .level(31)
            .codecString("avc1.64001F")
            .build(),
        LevelCase.builder()
            .width(854)
            .height(480)
            .frameRate(23.976)
            .bitrate(1500000L)
            .level(30)
            .codecString("avc1.64001E")
            .build(),
        LevelCase.builder()
            .width(640)
            .height(360)
            .frameRate(23.976)
            .bitrate(800000L)
            .level(30)
            .codecString("avc1.64001E")
            .build(),
        LevelCase.builder()
            .width(256)
            .height(144)
            .frameRate(30)
            .bitrate(200000L)
            .level(12)
            .codecString("avc1.64000C")
            .build(),
        // The buffer size, twice the bitrate, exceeds level 4.0's 31,250,000 bits.
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(30)
            .bitrate(24000000L)
            .level(41)
            .codecString("avc1.640029")
            .build(),
        // The bitrate exceeds level 4.1's 62,500,000 b/s, so only level 5.0 allows it.
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(30)
            .bitrate(70000000L)
            .level(50)
            .codecString("avc1.640032")
            .build(),
        // Four frames of 396 macroblocks exceed level 1.1's 900-macroblock decoded picture buffer.
        LevelCase.builder()
            .width(352)
            .height(288)
            .frameRate(1)
            .bitrate(100000L)
            .level(12)
            .codecString("avc1.64000C")
            .build(),
        // 256 macroblocks across exceed the width that every level below 4.0 allows.
        LevelCase.builder()
            .width(4096)
            .height(64)
            .frameRate(1)
            .bitrate(100000L)
            .level(40)
            .codecString("avc1.640028")
            .build(),
        LevelCase.builder()
            .width(3840)
            .height(2160)
            .frameRate(60)
            .bitrate(40000000L)
            .level(52)
            .codecString("avc1.640034")
            .build(),
        LevelCase.builder()
            .width(7680)
            .height(4320)
            .frameRate(60)
            .bitrate(100000000L)
            .level(61)
            .codecString("avc1.64003D")
            .build());
  }

  private static Stream<LevelCase> av1LevelCases() {
    return Stream.of(
        // The measured cases from streamarr/streamarr-server#501.
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(60)
            .bitrate(5000000L)
            .level(41)
            .codecString("av01.0.09M.08")
            .build(),
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(30)
            .bitrate(5000000L)
            .level(40)
            .codecString("av01.0.08M.08")
            .build(),
        LevelCase.builder()
            .width(1280)
            .height(720)
            .frameRate(60)
            .bitrate(3000000L)
            .level(40)
            .codecString("av01.0.08M.08")
            .build(),
        // A portrait source after rotation, as FFmpeg scales it to 1080 lines.
        LevelCase.builder()
            .width(608)
            .height(1080)
            .frameRate(30)
            .bitrate(5000000L)
            .level(30)
            .codecString("av01.0.04M.08")
            .build(),
        LevelCase.builder()
            .width(854)
            .height(480)
            .frameRate(23.976)
            .bitrate(1500000L)
            .level(30)
            .codecString("av01.0.04M.08")
            .build(),
        LevelCase.builder()
            .width(640)
            .height(360)
            .frameRate(23.976)
            .bitrate(800000L)
            .level(21)
            .codecString("av01.0.01M.08")
            .build(),
        LevelCase.builder()
            .width(256)
            .height(144)
            .frameRate(30)
            .bitrate(200000L)
            .level(20)
            .codecString("av01.0.00M.08")
            .build(),
        // The bitrate exceeds level 4.0's 12 Mb/s in the Main tier.
        LevelCase.builder()
            .width(1920)
            .height(1080)
            .frameRate(30)
            .bitrate(15000000L)
            .level(41)
            .codecString("av01.0.09M.08")
            .build(),
        // 4,096 columns exceed level 2.1's widest picture of 2,816 columns.
        LevelCase.builder()
            .width(4096)
            .height(64)
            .frameRate(1)
            .bitrate(100000L)
            .level(30)
            .codecString("av01.0.04M.08")
            .build(),
        // 2,560 lines exceed level 3.0's tallest picture of 2,448 lines.
        LevelCase.builder()
            .width(64)
            .height(2560)
            .frameRate(1)
            .bitrate(100000L)
            .level(31)
            .codecString("av01.0.05M.08")
            .build(),
        LevelCase.builder()
            .width(3840)
            .height(2160)
            .frameRate(60)
            .bitrate(40000000L)
            .level(51)
            .codecString("av01.0.13M.08")
            .build(),
        LevelCase.builder()
            .width(7680)
            .height(4320)
            .frameRate(60)
            .bitrate(100000000L)
            .level(61)
            .codecString("av01.0.17M.08")
            .build());
  }

  @Builder
  private record LevelCase(
      int width, int height, double frameRate, long bitrate, int level, String codecString) {

    @Override
    public String toString() {
      return "%dx%d at %s fps and %d b/s → level %d, %s"
          .formatted(width, height, frameRate, bitrate, level, codecString);
    }
  }

  private static EncodedVideoOutput.EncodedVideoOutputBuilder output(String codecFamily) {
    return EncodedVideoOutput.builder().codecFamily(codecFamily);
  }
}
