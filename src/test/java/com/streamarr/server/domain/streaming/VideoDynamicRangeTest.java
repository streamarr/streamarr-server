package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ContradictoryDynamicRangeException;
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

  static Stream<DolbyVision> dolbyVisionNotPresent() {
    return Stream.of(new DolbyVision.Absent(), new DolbyVision.Unspecified());
  }
}
