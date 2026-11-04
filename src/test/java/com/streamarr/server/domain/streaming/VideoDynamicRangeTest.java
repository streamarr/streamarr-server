package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ContradictoryDynamicRangeException;
import java.util.List;
import java.util.Optional;
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

  static Stream<DolbyVision> dolbyVisionNotPresent() {
    return Stream.of(new DolbyVision.Absent(), new DolbyVision.Unspecified());
  }
}
