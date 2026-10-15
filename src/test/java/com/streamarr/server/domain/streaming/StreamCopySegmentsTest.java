package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Stream Copy Segments Tests")
class StreamCopySegmentsTest {

  @Test
  @DisplayName("Should keep the segment starts it was given when the caller's list changes")
  void shouldKeepTheSegmentStartsItWasGivenWhenTheCallersListChanges() {
    var first = new KeyframePosition(0, 0);
    var second = new KeyframePosition(6_000, 6_000);
    var openingKeyframes = new ArrayList<>(List.of(first, second));
    var starts = new StreamCopySegments.Starts(openingKeyframes);

    openingKeyframes.add(new KeyframePosition(12_000, 12_000));

    assertThat(starts.openingKeyframes()).containsExactly(first, second);
  }

  @Test
  @DisplayName("Should reject missing segment starts")
  void shouldRejectMissingSegmentStarts() {
    assertThatNullPointerException().isThrownBy(() -> new StreamCopySegments.Starts(null));
  }
}
