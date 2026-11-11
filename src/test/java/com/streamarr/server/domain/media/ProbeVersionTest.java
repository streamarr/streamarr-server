package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe version")
class ProbeVersionTest {

  // ADR 0032 decides this for every probe version through this one: "An earlier outcome and a
  // probe version 9 outcome of the same file share one media time zero."
  private static final int LAST_VERSION_WITH_A_DECIDED_KEYFRAME_TIMELINE = 9;

  @Test
  @DisplayName("Should have a decided keyframe timeline when the current probe version rises")
  void shouldHaveDecidedKeyframeTimelineWhenCurrentProbeVersionRises() {
    assertThat(ProbeVersion.CURRENT)
        .as(
            "Decide whether probe version %d moves media time zero. If it does, make it"
                + " ProbeVersion.FIRST_ON_CURRENT_KEYFRAME_TIMELINE, so that keyframe positions"
                + " that an earlier version's packet scan read are dropped. Then record the"
                + " decision here.",
            ProbeVersion.CURRENT)
        .isLessThanOrEqualTo(LAST_VERSION_WITH_A_DECIDED_KEYFRAME_TIMELINE);
  }

  @Test
  @DisplayName("Should place the current probe version on the current keyframe timeline")
  void shouldPlaceCurrentProbeVersionOnCurrentKeyframeTimeline() {
    assertThat(ProbeVersion.isOnCurrentKeyframeTimeline(ProbeVersion.CURRENT)).isTrue();
  }

  @Test
  @DisplayName(
      "Should place a probe version on the current keyframe timeline only from the timeline's"
          + " first version")
  void shouldPlaceProbeVersionOnCurrentKeyframeTimelineOnlyFromTimelinesFirstVersion() {
    var first = ProbeVersion.FIRST_ON_CURRENT_KEYFRAME_TIMELINE;

    assertThat(ProbeVersion.isOnCurrentKeyframeTimeline(first - 1)).isFalse();
    assertThat(ProbeVersion.isOnCurrentKeyframeTimeline(first)).isTrue();
  }
}
