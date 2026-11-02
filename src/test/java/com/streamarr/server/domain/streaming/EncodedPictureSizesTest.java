package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Encoded Picture Sizes Tests")
class EncodedPictureSizesTest {

  @Test
  @DisplayName(
      "Should choose a height past the bound width's exact height when the encoder rounds its"
          + " width down to the bound")
  void
      shouldChooseAHeightPastTheBoundWidthsExactHeightWhenTheEncoderRoundsItsWidthDownToTheBound() {
    var source = new PictureSize(1080, 1920);
    var bounds = new PictureSize(1000, 1920);

    var largest = EncodedPictureSizes.picturesWithin(source, bounds).findFirst();

    assertThat(largest).contains(new PictureSize(1000, 1778));
  }

  @Test
  @DisplayName(
      "Should skip a height whose width the encoder rounds up past the bound when the bound width"
          + " is odd")
  void shouldSkipAHeightWhoseWidthTheEncoderRoundsUpPastTheBoundWhenTheBoundWidthIsOdd() {
    var source = new PictureSize(1920, 1080);
    var bounds = new PictureSize(1279, 720);

    var largest = EncodedPictureSizes.picturesWithin(source, bounds).findFirst();

    assertThat(largest).contains(new PictureSize(1276, 718));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesAndBoundsWithoutATwoByTwoPicture")
  @DisplayName(
      "Should list no picture when the bounds or the source leave no picture of two by two pixels")
  void shouldListNoPictureWhenTheBoundsOrTheSourceLeaveNoPictureOfTwoByTwoPixels(
      SourceWithinBounds sourceWithinBounds) {
    assertThat(
            EncodedPictureSizes.picturesWithin(
                sourceWithinBounds.source(), sourceWithinBounds.bounds()))
        .isEmpty();
  }

  // A picture two pixels high whose width the bounds cut below two, bounds a pixel wide or high, a
  // source a pixel high or too narrow to reach two pixels wide, and sources without area.
  static Stream<SourceWithinBounds> sourcesAndBoundsWithoutATwoByTwoPicture() {
    var fullHd = new PictureSize(1920, 1080);
    return Stream.of(
        sourceWithinBounds(new PictureSize(4096, 2), new PictureSize(1280, 720)),
        sourceWithinBounds(fullHd, new PictureSize(1920, 1)),
        sourceWithinBounds(fullHd, new PictureSize(1, 1080)),
        sourceWithinBounds(new PictureSize(1920, 1), fullHd),
        sourceWithinBounds(new PictureSize(1, 3), fullHd),
        sourceWithinBounds(new PictureSize(0, 1080), fullHd),
        sourceWithinBounds(new PictureSize(1920, 0), fullHd));
  }

  private static SourceWithinBounds sourceWithinBounds(PictureSize source, PictureSize bounds) {
    return SourceWithinBounds.builder().source(source).bounds(bounds).build();
  }

  @Builder
  record SourceWithinBounds(PictureSize source, PictureSize bounds) {}

  @Test
  @DisplayName(
      "Should widen a one-pixel-wide source to two pixels when the encoder rounds its width up")
  void shouldWidenAOnePixelWideSourceToTwoPixelsWhenTheEncoderRoundsItsWidthUp() {
    var source = new PictureSize(1, 1080);

    assertThat(
            EncodedPictureSizes.picturesWithin(source, EncodedPictureSizes.NO_BOUNDS).findFirst())
        .contains(new PictureSize(2, 1080));
  }

  @Test
  @DisplayName("Should end the list at two pixels high when the source is wide")
  void shouldEndTheListAtTwoPixelsHighWhenTheSourceIsWide() {
    var source = new PictureSize(1920, 8);

    assertThat(EncodedPictureSizes.picturesWithin(source, source))
        .containsExactly(
            new PictureSize(1920, 8),
            new PictureSize(1440, 6),
            new PictureSize(960, 4),
            new PictureSize(480, 2));
  }
}
