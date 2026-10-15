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
    var presentationTimes = new ArrayList<>(List.of(0L, 6_000L));
    var starts = new StreamCopySegments.Starts(presentationTimes);

    presentationTimes.add(12_000L);

    assertThat(starts.presentationTimes()).containsExactly(0L, 6_000L);
  }

  @Test
  @DisplayName("Should reject missing segment starts")
  void shouldRejectMissingSegmentStarts() {
    assertThatNullPointerException().isThrownBy(() -> new StreamCopySegments.Starts(null));
  }
}
