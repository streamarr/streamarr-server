package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest
  @CsvSource({
    "4096, 2, 1280, 720",
    "1920, 1080, 1920, 1",
    "1920, 1080, 1, 1080",
    "1920, 1, 1920, 1080",
    "1, 3, 1920, 1080",
    "0, 1080, 1920, 1080",
    "1920, 0, 1920, 1080"
  })
  @DisplayName(
      "Should list no picture when the bounds or the source leave no picture of two by two pixels")
  void shouldListNoPictureWhenTheBoundsOrTheSourceLeaveNoPictureOfTwoByTwoPixels(
      int sourceWidth, int sourceHeight, int boundWidth, int boundHeight) {
    var source = new PictureSize(sourceWidth, sourceHeight);
    var bounds = new PictureSize(boundWidth, boundHeight);

    assertThat(EncodedPictureSizes.picturesWithin(source, bounds)).isEmpty();
  }

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
