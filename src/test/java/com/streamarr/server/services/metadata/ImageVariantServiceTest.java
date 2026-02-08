package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
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

    assertThat(variants).hasSize(4);
    assertThat(variants)
        .extracting(ImageVariantService.GeneratedVariant::variant)
        .containsExactlyInAnyOrder(
            ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE, ImageSize.ORIGINAL);
  }

  @Test
  @DisplayName("Should resize small poster variant to 185px width")
  void shouldResizeSmallPosterVariantTo185pxWidth() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.width()).isEqualTo(185);
  }

  @Test
  @DisplayName("Should resize small backdrop variant to 300px width")
  void shouldResizeSmallBackdropVariantTo300pxWidth() {
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
  @DisplayName("Should include original variant with unmodified dimensions")
  void shouldIncludeOriginalVariantWithUnmodifiedDimensions() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var original =
        variants.stream().filter(v -> v.variant() == ImageSize.ORIGINAL).findFirst().orElseThrow();
    assertThat(original.width()).isEqualTo(600);
    assertThat(original.height()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should compute BlurHash on small variant only")
  void shouldComputeBlurHashOnSmallVariantOnly() {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, ImageType.POSTER);

    var small =
        variants.stream().filter(v -> v.variant() == ImageSize.SMALL).findFirst().orElseThrow();
    assertThat(small.blurHash()).isNotNull();
    assertThat(small.blurHash()).isNotBlank();
  }

  @Test
  @DisplayName("Should return null BlurHash for non-small variants")
  void shouldReturnNullBlurHashForNonSmallVariants() {
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
  @DisplayName("Should generate variants for every ImageType")
  void shouldGenerateVariantsForEveryImageType(ImageType imageType) {
    var imageData = createTestImage(600, 900);

    var variants = imageVariantService.generateVariants(imageData, imageType);

    assertThat(variants).hasSize(4);
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

  private byte[] createTestImage(int width, int height) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.BLUE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();

    try (var outputStream = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", outputStream);
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
