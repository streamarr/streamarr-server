package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

    var height = EncodedPictureSizes.largestHeightWithin(source, bounds);

    assertThat(height).isEqualTo(1778);
    assertThat(EncodedPictureSizes.atHeight(source, height)).isEqualTo(new PictureSize(1000, 1778));
  }
}
