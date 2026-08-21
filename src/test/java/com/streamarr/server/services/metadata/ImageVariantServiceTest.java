package com.streamarr.server.services.metadata;

import static com.streamarr.server.fakes.TestImages.createSolidPngImage;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.Builder;
import net.coobird.thumbnailator.Thumbnails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Image Variant Service Tests")
class ImageVariantServiceTest {

  private final ImageVariantService imageVariantService = new ImageVariantService();

  @Test
  @DisplayName("Should generate four variants when given a valid image")
  void shouldGenerateFourVariantsWhenGivenValidImage() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    assertThat(variants)
        .extracting(ImageVariantService.GeneratedVariant::variant)
        .containsExactlyInAnyOrder(
            ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE, ImageSize.ORIGINAL);
  }

  @Test
  @DisplayName("Should resize small variant to 185px width when image type is poster")
  void shouldResizeSmallVariantTo185pxWidthWhenImageTypeIsPoster() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.width()).isEqualTo(185);
  }

  @Test
  @DisplayName("Should resize small variant to 300px width when image type is backdrop")
  void shouldResizeSmallVariantTo300pxWidthWhenImageTypeIsBackdrop() {
    var imageData = createTestImage(1920, 1080);

    var variants = imageVariantService.generateVariants(imageData, ImageType.BACKDROP);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.width()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should compute ambient colors on small variant when generating variants")
  void shouldComputeAmbientColorsOnSmallVariantWhenGeneratingVariants() {
    var imageData = createSolidPngImage(600, 900, 0x00A0A0);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.ambientColors())
        .isEqualTo(
            Optional.of(
                AmbientColors.builder()
                    .topLeft("#00a0a0")
                    .topRight("#00a0a0")
                    .bottomRight("#00a0a0")
                    .bottomLeft("#00a0a0")
                    .primary("#00a0a0")
                    .build()));
  }

  @Test
  @DisplayName("Should omit ambient colors on non-small variants when generating variants")
  void shouldOmitAmbientColorsOnNonSmallVariantsWhenGeneratingVariants() {
    var imageData = createSolidPngImage(600, 900, 0x00A0A0);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    assertThat(variants)
        .filteredOn(v -> v.variant() != ImageSize.SMALL)
        .hasSize(3)
        .allSatisfy(v -> assertThat(v.ambientColors()).isEqualTo(Optional.empty()));
  }

  @Test
  @DisplayName("Should preserve aspect ratio when resizing")
  void shouldPreserveAspectRatioWhenResizing() {
    var imageData = createTestImage(600, 900);
    var sourceAspectRatio = 600.0 / 900.0;

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    for (var variant : variants) {
      var variantAspectRatio = (double) variant.width() / variant.height();
      assertThat(variantAspectRatio).isCloseTo(sourceAspectRatio, offset(0.005));
    }
  }

  @Test
  @DisplayName("Should preserve original dimensions when variant is original")
  void shouldPreserveOriginalDimensionsWhenVariantIsOriginal() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var original =
        variants.stream().filter(v -> v.variant() == ImageSize.ORIGINAL).findFirst().orElseThrow();
    assertThat(original.width()).isEqualTo(600);
    assertThat(original.height()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should compute BlurHash when variant is small")
  void shouldComputeBlurHashWhenVariantIsSmall() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.blurHash()).isNotBlank();
  }

  @Test
  @DisplayName("Should return null BlurHash when variant is not small")
  void shouldReturnNullBlurHashWhenVariantIsNotSmall() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var nonSmallVariants = variants.stream().filter(v -> v.variant() != ImageSize.SMALL).toList();

    assertThat(nonSmallVariants).isNotEmpty();
    for (var variant : nonSmallVariants) {
      assertThat(variant.blurHash()).isNull();
    }
  }

  @ParameterizedTest
  @EnumSource(ImageType.class)
  @DisplayName("Should generate four variants when given any ImageType")
  void shouldGenerateFourVariantsWhenGivenAnyImageType(ImageType imageType) {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, imageType);

    assertThat(variants).hasSize(4);
  }

  @Test
  @DisplayName("Should consider variants equal when all fields match")
  void shouldConsiderVariantsEqualWhenAllFieldsMatch() {
    var a = defaultVariantBuilder().data(new byte[] {1, 2, 3}).build();
    var b = defaultVariantBuilder().data(new byte[] {1, 2, 3}).build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  @DisplayName("Should consider variants equal when ambient color values match")
  void shouldConsiderVariantsEqualWhenAmbientColorValuesMatch() {
    var a = defaultVariantBuilder().ambientColors(ambientColors("#00a0a0")).build();
    var b = defaultVariantBuilder().ambientColors(ambientColors("#00a0a0")).build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  @DisplayName("Should consider variant equal when compared to itself")
  void shouldConsiderVariantEqualWhenComparedToItself() {
    var variant = defaultVariantBuilder().build();

    assertThat(variant).isEqualTo(variant);
  }

  @Test
  @DisplayName("Should not consider variant equal when compared to another type")
  void shouldNotConsiderVariantEqualWhenComparedToAnotherType() {
    var variant = defaultVariantBuilder().build();

    assertThat(variant).isNotEqualTo("not-a-variant");
  }

  @Test
  @DisplayName("Should not consider variants equal when only height differs")
  void shouldNotConsiderVariantsEqualWhenOnlyHeightDiffers() {
    var a = defaultVariantBuilder().build();
    var b = defaultVariantBuilder().height(300).build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when data differs")
  void shouldNotConsiderVariantsEqualWhenDataDiffers() {
    var a = defaultVariantBuilder().data(new byte[] {1}).build();
    var b = defaultVariantBuilder().data(new byte[] {2}).build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when variant size differs")
  void shouldNotConsiderVariantsEqualWhenVariantSizeDiffers() {
    var a = defaultVariantBuilder().build();
    var b = defaultVariantBuilder().variant(ImageSize.LARGE).build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when dimensions differ")
  void shouldNotConsiderVariantsEqualWhenDimensionsDiffer() {
    var a = defaultVariantBuilder().build();
    var b = defaultVariantBuilder().width(200).build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when blurHash differs")
  void shouldNotConsiderVariantsEqualWhenBlurHashDiffers() {
    var a = defaultVariantBuilder().blurHash("hash1").build();
    var b = defaultVariantBuilder().blurHash("hash2").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when ambient colors differ")
  void shouldNotConsiderVariantsEqualWhenAmbientColorsDiffer() {
    var teal = ambientColors("#00a0a0");
    var a = defaultVariantBuilder().ambientColors(teal).build();
    var b = defaultVariantBuilder().build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variant equal when compared to null")
  void shouldNotConsiderVariantEqualWhenComparedToNull() {
    var variant = defaultVariantBuilder().build();

    assertThat(variant).isNotEqualTo(null);
  }

  @Test
  @DisplayName("Should include data length when converted to string")
  void shouldIncludeDataLengthWhenConvertedToString() {
    var variant = defaultVariantBuilder().data(new byte[] {1, 2, 3}).build();

    assertThat(variant.toString()).contains("dataLength=3");
  }

  @Test
  @DisplayName("Should include ambient colors when converted to string")
  void shouldIncludeAmbientColorsWhenConvertedToString() {
    var variant = defaultVariantBuilder().ambientColors(ambientColors("#00a0a0")).build();

    assertThat(variant.toString()).contains("ambientColors=", "primary=#00a0a0");
  }

  @Test
  @DisplayName("Should report zero data length when converted to string with null data")
  void shouldReportZeroDataLengthWhenConvertedToStringWithNullData() {
    var variant = defaultVariantBuilder().data(null).build();

    assertThat(variant.toString()).contains("dataLength=0");
  }

  private static GeneratedVariantBuilder defaultVariantBuilder() {
    return emptyVariantBuilder()
        .variant(ImageSize.SMALL)
        .data(new byte[] {1})
        .width(100)
        .height(150)
        .blurHash("hash");
  }

  private static AmbientColors ambientColors(String primary) {
    return AmbientColors.builder()
        .topLeft("#010101")
        .topRight("#020202")
        .bottomRight("#030303")
        .bottomLeft("#040404")
        .primary(primary)
        .build();
  }

  @Builder(builderClassName = "GeneratedVariantBuilder", builderMethodName = "emptyVariantBuilder")
  private static ImageVariantService.GeneratedVariant buildVariant(
      ImageSize variant,
      byte[] data,
      int width,
      int height,
      String blurHash,
      AmbientColors ambientColors) {
    return new ImageVariantService.GeneratedVariant(
        variant, data, width, height, blurHash, Optional.ofNullable(ambientColors));
  }

  @Test
  @DisplayName("Should throw when image data is null")
  void shouldThrowWhenImageDataIsNull() {
    assertThatThrownBy(() -> imageVariantService.generateVariants(null, ImageType.POSTER))
        .isInstanceOf(ImageProcessingException.class);
  }

  @Test
  @DisplayName("Should throw when image data is corrupt")
  void shouldThrowWhenImageDataIsCorrupt() {
    assertThatThrownBy(
            () -> imageVariantService.generateVariants(new byte[] {0, 1, 2}, ImageType.POSTER))
        .isInstanceOf(ImageProcessingException.class);
  }

  @Test
  @DisplayName("Should wrap IOException when image reading fails")
  void shouldWrapIOExceptionWhenImageReadingFails() {
    var imageData = new byte[] {1, 2, 3};

    try (var mockedImageIO = mockStatic(ImageIO.class)) {
      mockedImageIO
          .when(() -> ImageIO.read(any(InputStream.class)))
          .thenThrow(new IOException("disk error"));

      assertThatThrownBy(() -> imageVariantService.generateVariants(imageData, ImageType.POSTER))
          .isInstanceOf(ImageProcessingException.class)
          .hasCauseInstanceOf(IOException.class);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("Should wrap IOException when image resizing fails")
  void shouldWrapIOExceptionWhenImageResizingFails() throws IOException {
    var imageData = createTestImage(600, 900);

    try (var mockedThumbnails = mockStatic(Thumbnails.class)) {
      var mockBuilder = mock(Thumbnails.Builder.class);
      mockedThumbnails.when(() -> Thumbnails.of(any(BufferedImage.class))).thenReturn(mockBuilder);
      when(mockBuilder.width(anyInt())).thenReturn(mockBuilder);
      when(mockBuilder.asBufferedImage()).thenThrow(new IOException("resize failed"));

      assertThatThrownBy(() -> imageVariantService.generateVariants(imageData, ImageType.POSTER))
          .isInstanceOf(ImageProcessingException.class)
          .hasCauseInstanceOf(IOException.class);
    }
  }
}
