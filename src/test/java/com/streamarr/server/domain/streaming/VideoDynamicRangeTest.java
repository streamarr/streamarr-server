package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ContradictoryDynamicRangeException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Dynamic Range Tests")
class VideoDynamicRangeTest {

  @ParameterizedTest
  @MethodSource("dolbyVisionNotPresent")
  @DisplayName("Should reject no compatible base when Dolby Vision is not present")
  void shouldRejectNoCompatibleBaseWhenDolbyVisionIsNotPresent(DolbyVision dolbyVision) {
    assertThatThrownBy(
            () -> new VideoDynamicRange(CompatibleBase.NONE, Presence.UNSPECIFIED, dolbyVision))
        .isInstanceOf(ContradictoryDynamicRangeException.class);
  }

  @Test
  @DisplayName("Should keep no compatible base when Dolby Vision is present")
  void shouldKeepNoCompatibleBaseWhenDolbyVisionIsPresent() {
    var present = new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED);

    var range = new VideoDynamicRange(CompatibleBase.NONE, Presence.UNSPECIFIED, present);

    assertThat(range.compatibleBase()).isEqualTo(CompatibleBase.NONE);
    assertThat(range.dolbyVision()).isEqualTo(present);
  }

  // ADR 0036: a stream copy changes the source's video only through its listed rewrites.
  @Test
  @DisplayName("Should keep the base and Dolby Vision when the rewrites strip HDR10+")
  void shouldKeepTheBaseAndDolbyVisionWhenTheRewritesStripHdr10Plus() {
    var present = new DolbyVision.Present(Optional.empty(), Presence.PRESENT);
    var source = new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, present);

    var rewritten = source.afterRewrites(List.of(BitstreamRewrite.STRIP_HDR10_PLUS));

    assertThat(rewritten)
        .isEqualTo(new VideoDynamicRange(CompatibleBase.PQ, Presence.ABSENT, present));
  }

  @Test
  @DisplayName("Should keep the base and HDR10+ when the rewrites strip Dolby Vision")
  void shouldKeepTheBaseAndHdr10PlusWhenTheRewritesStripDolbyVision() {
    var source =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.PRESENT,
            new DolbyVision.Present(Optional.empty(), Presence.PRESENT));

    var rewritten = source.afterRewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION));

    assertThat(rewritten)
        .isEqualTo(
            new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent()));
  }

  @Test
  @DisplayName("Should keep the range when the rewrites only normalize the Dolby Vision record")
  void shouldKeepTheRangeWhenTheRewritesOnlyNormalizeTheDolbyVisionRecord() {
    var source =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.PRESENT,
            new DolbyVision.Present(Optional.empty(), Presence.PRESENT));

    var rewritten = source.afterRewrites(List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));

    assertThat(rewritten).isEqualTo(source);
  }

  // ADR 0036 and streamarr/streamarr-server#651: the converted copy carries profile 8.1 as the
  // worker writes it: a record of profile 8 with compatibility identifier 1 and no enhancement
  // layer
  // at the source's level, and an RPU without residual at the end of each picture's sample.
  @Test
  @DisplayName(
      "Should describe profile 8.1 with an RPU after each picture when the rewrites convert profile"
          + " 7")
  void shouldDescribeProfile81WithAnRpuAfterEachPictureWhenTheRewritesConvertProfile7() {
    var source =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.PRESENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(7))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(6))
                        .baseLayer(true)
                        .enhancementLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT,
                Optional.of(
                    DolbyVisionRpuHeader.builder()
                        .vdrRpuProfile(1)
                        .enhancementLayerSpatialResamplingFilter(true)
                        .vdrBitDepth(12)
                        .build()),
                DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));

    var rewritten =
        source.afterRewrites(
            List.of(
                BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));

    assertThat(rewritten)
        .isEqualTo(
            new VideoDynamicRange(
                CompatibleBase.PQ,
                Presence.PRESENT,
                new DolbyVision.Present(
                    Optional.of(
                        DolbyVisionConfiguration.builder()
                            .profile(OptionalInt.of(8))
                            .level(OptionalInt.of(6))
                            .baseLayerCompatibilityId(OptionalInt.of(1))
                            .baseLayer(true)
                            .rpu(true)
                            .build()),
                    Presence.PRESENT,
                    Optional.of(
                        DolbyVisionRpuHeader.builder()
                            .vdrRpuProfile(1)
                            .disableResidual(true)
                            .vdrBitDepth(12)
                            .build()),
                    DolbyVisionRpuPlacement.AFTER_OWN_PICTURE)));
  }

  @ParameterizedTest
  @MethodSource("dolbyVisionNotPresent")
  @DisplayName(
      "Should keep the range when the rewrites convert profile 7 of a source without Dolby Vision")
  void shouldKeepTheRangeWhenTheRewritesConvertProfile7OfASourceWithoutDolbyVision(
      DolbyVision dolbyVision) {
    var source = new VideoDynamicRange(CompatibleBase.PQ, Presence.ABSENT, dolbyVision);

    var rewritten =
        source.afterRewrites(List.of(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1));

    assertThat(rewritten).isEqualTo(source);
  }

  static Stream<DolbyVision> dolbyVisionNotPresent() {
    return Stream.of(new DolbyVision.Absent(), new DolbyVision.Unspecified());
  }
}
