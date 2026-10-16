package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Playback Facts Tests")
class VideoPlaybackFactsTest {

  private static final PictureSize CODED_1080P = new PictureSize(1920, 1080);

  @ParameterizedTest(name = "{0}")
  @MethodSource("oddCrops")
  @DisplayName("Should round a crop down to the chroma grid when the source subsamples its chroma")
  void shouldRoundACropDownToTheChromaGridWhenTheSourceSubsamplesItsChroma(
      Optional<ChromaSubsampling> chromaSubsampling, PictureSize displayed) {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .chromaSubsampling(chromaSubsampling)
            .containerCropping(Optional.of(ContainerCropping.builder().top(5).left(3).build()))
            .build();

    assertThat(facts.displayedSize(CODED_1080P)).isEqualTo(displayed);
  }

  // FFmpeg's crop filter, without exact cropping, rounds the width and height down to whole chroma
  // samples. FFmpeg 8.0.1 cropped 3 columns and 5 rows from a 64x36 frame to 60x30 in 4:2:0, 60x31
  // in 4:2:2 and 61x31 in 4:4:4.
  static Stream<Arguments> oddCrops() {
    return Stream.of(
        arguments(Optional.of(ChromaSubsampling.CHROMA_422), new PictureSize(1916, 1075)),
        arguments(Optional.of(ChromaSubsampling.CHROMA_444), new PictureSize(1917, 1075)),
        arguments(Optional.of(ChromaSubsampling.MONOCHROME), new PictureSize(1917, 1075)),
        arguments(Optional.empty(), new PictureSize(1916, 1074)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("displayedSizes")
  @DisplayName("Should crop and then rotate the coded size when a player shows the picture")
  void shouldCropAndThenRotateTheCodedSizeWhenAPlayerShowsThePicture(
      String scenario,
      OptionalInt rotationDegrees,
      Optional<ContainerCropping> cropping,
      PictureSize displayed) {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .rotationDegrees(rotationDegrees)
            .containerCropping(cropping)
            .build();

    assertThat(facts.displayedSize(CODED_1080P)).isEqualTo(displayed);
  }

  // FFmpeg crops in the coded orientation, then transposes for a quarter turn. It flips a half
  // turn in place and applies no other angle before scaling.
  static Stream<Arguments> displayedSizes() {
    var letterbox = ContainerCropping.builder().top(140).bottom(140).build();
    var pillarbox = ContainerCropping.builder().left(240).right(240).build();
    return Stream.of(
        arguments("no rotation or crop", OptionalInt.empty(), Optional.empty(), CODED_1080P),
        arguments(
            "phone held upright",
            OptionalInt.of(-90),
            Optional.empty(),
            new PictureSize(1080, 1920)),
        arguments(
            "quarter turn the other way",
            OptionalInt.of(90),
            Optional.empty(),
            new PictureSize(1080, 1920)),
        arguments("half turn", OptionalInt.of(180), Optional.empty(), CODED_1080P),
        arguments("angle off the quarter turns", OptionalInt.of(45), Optional.empty(), CODED_1080P),
        arguments(
            "letterbox crop",
            OptionalInt.empty(),
            Optional.of(letterbox),
            new PictureSize(1920, 800)),
        arguments(
            "pillarbox crop before a quarter turn",
            OptionalInt.of(-90),
            Optional.of(pillarbox),
            new PictureSize(1080, 1440)),
        arguments(
            "odd crop of 4:2:0, rounded down to even rows and columns",
            OptionalInt.empty(),
            Optional.of(ContainerCropping.builder().top(5).left(3).build()),
            new PictureSize(1916, 1074)),
        arguments(
            "crop that leaves no picture",
            OptionalInt.empty(),
            Optional.of(ContainerCropping.builder().left(960).right(960).build()),
            CODED_1080P));
  }
}
