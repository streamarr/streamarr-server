package com.streamarr.server.services.metadata;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
  @DisplayName("Should preserve aspect ratio when resizing")
  void shouldPreserveAspectRatioWhenResizing() {
    var imageData = createTestImage(600, 900);
    var sourceAspectRatio = 600.0 / 900.0;

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    for (var variant : variants) {
      var variantAspectRatio = (double) variant.width() / variant.height();
      assertThat(variantAspectRatio)
          .isCloseTo(sourceAspectRatio, org.assertj.core.data.Offset.offset(0.02));
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
    var a =
        new ImageVariantService.GeneratedVariant(
            ImageSize.SMALL, new byte[] {1, 2, 3}, 100, 150, "hash");
    var b =
        new ImageVariantService.GeneratedVariant(
            ImageSize.SMALL, new byte[] {1, 2, 3}, 100, 150, "hash");

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  @DisplayName("Should not consider variants equal when data differs")
  void shouldNotConsiderVariantsEqualWhenDataDiffers() {
    var a =
        new ImageVariantService.GeneratedVariant(ImageSize.SMALL, new byte[] {1}, 100, 150, "hash");
    var b =
        new ImageVariantService.GeneratedVariant(ImageSize.SMALL, new byte[] {2}, 100, 150, "hash");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when variant size differs")
  void shouldNotConsiderVariantsEqualWhenVariantSizeDiffers() {
    var data = new byte[] {1};
    var a = new ImageVariantService.GeneratedVariant(ImageSize.SMALL, data, 100, 150, "hash");
    var b = new ImageVariantService.GeneratedVariant(ImageSize.LARGE, data, 100, 150, "hash");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when dimensions differ")
  void shouldNotConsiderVariantsEqualWhenDimensionsDiffer() {
    var data = new byte[] {1};
    var a = new ImageVariantService.GeneratedVariant(ImageSize.SMALL, data, 100, 150, "hash");
    var b = new ImageVariantService.GeneratedVariant(ImageSize.SMALL, data, 200, 300, "hash");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variants equal when blurHash differs")
  void shouldNotConsiderVariantsEqualWhenBlurHashDiffers() {
    var data = new byte[] {1};
    var a = new ImageVariantService.GeneratedVariant(ImageSize.SMALL, data, 100, 150, "hash1");
    var b = new ImageVariantService.GeneratedVariant(ImageSize.SMALL, data, 100, 150, "hash2");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not consider variant equal when compared to null")
  void shouldNotConsiderVariantEqualWhenComparedToNull() {
    var variant =
        new ImageVariantService.GeneratedVariant(ImageSize.SMALL, new byte[] {1}, 100, 150, "hash");

    assertThat(variant).isNotEqualTo(null);
  }

  @Test
  @DisplayName("Should include data length when converted to string")
  void shouldIncludeDataLengthWhenConvertedToString() {
    var variant =
        new ImageVariantService.GeneratedVariant(
            ImageSize.SMALL, new byte[] {1, 2, 3}, 100, 150, "hash");

    assertThat(variant.toString()).contains("dataLength=3");
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
