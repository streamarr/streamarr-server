package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Segment Names Tests")
class SegmentNamesTest {

  @Test
  @DisplayName("Should parse the timeline index when the name is a canonical media segment")
  void shouldParseTimelineIndexWhenNameIsCanonicalMediaSegment() {
    assertThat(SegmentNames.indexOf("segment5.ts")).hasValue(5);
    assertThat(SegmentNames.indexOf("segment0.m4s")).hasValue(0);
    assertThat(SegmentNames.indexOf("720p/segment12.m4s")).hasValue(12);
    assertThat(SegmentNames.parseIndex("720p/segment12.m4s")).isEqualTo(12);
  }

  @Test
  @DisplayName("Should parse index zero when the name is an init segment")
  void shouldParseIndexZeroWhenNameIsInitSegment() {
    assertThat(SegmentNames.indexOf("init.mp4")).isEmpty();
    assertThat(SegmentNames.indexOf("720p/init.mp4")).isEmpty();
    assertThat(SegmentNames.parseIndex("init.mp4")).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"foo.ts", "notasegment5.ts", "segment5.mp4", "segment.ts", "segment5", "init.m4s"})
  @DisplayName("Should carry no index when the name matches no naming scheme")
  void shouldCarryNoIndexWhenNameMatchesNoNamingScheme(String name) {
    assertThat(SegmentNames.indexOf(name)).isEmpty();
  }

  @Test
  @DisplayName("Should carry no index when the index overflows the naming scheme")
  void shouldCarryNoIndexWhenIndexOverflowsTheNamingScheme() {
    assertThat(SegmentNames.indexOf("segment99999999999999999999.ts")).isEmpty();
    assertThat(SegmentNames.indexOf("segment9999999999.m4s")).isEmpty();
  }

  @Test
  @DisplayName("Should compute the sibling name when the name carries an index")
  void shouldComputeSiblingNameWhenNameCarriesAnIndex() {
    assertThat(SegmentNames.siblingName("segment7.ts", 3)).isEqualTo("segment3.ts");
    assertThat(SegmentNames.siblingName("720p/segment7.m4s", 3)).isEqualTo("720p/segment3.m4s");
  }

  @Test
  @DisplayName("Should compute the sibling media segment when the name is an init segment")
  void shouldComputeSiblingMediaSegmentWhenNameIsInitSegment() {
    assertThat(SegmentNames.siblingName("init.mp4", 4)).isEqualTo("segment4.m4s");
    assertThat(SegmentNames.siblingName("720p/init.mp4", 4)).isEqualTo("720p/segment4.m4s");
  }

  @Test
  @DisplayName("Should recognize init segments when the basename is exactly init.mp4")
  void shouldRecognizeInitSegmentsWhenBasenameIsExactlyInitMp4() {
    assertThat(SegmentNames.isInitSegment("init.mp4")).isTrue();
    assertThat(SegmentNames.isInitSegment("720p/init.mp4")).isTrue();
    assertThat(SegmentNames.isInitSegment("segment3.m4s")).isFalse();
    assertThat(SegmentNames.isInitSegment("notinit.mp4")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"foo.ts", "notinit.mp4", "segment5.mp4"})
  @DisplayName("Should throw when a sibling is requested for a name matching no naming scheme")
  void shouldThrowWhenSiblingRequestedForNameMatchingNoNamingScheme(String name) {
    assertThatThrownBy(() -> SegmentNames.siblingName(name, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(name);
  }
}
