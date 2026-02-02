package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.ImageProcessingException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Image Thumbnail Service Tests")
public class ImageThumbnailServiceTest {

  public static final String BASE_64_IMAGE =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

  private final ImageThumbnailService imageThumbnailService = new ImageThumbnailService();

  @Test
  @DisplayName("Should successfully convert image to a thumbnail image.")
  void shouldConvertToThumbnail() {
    var imageOutput =
        imageThumbnailService.convertImageToThumbnails(Base64.getDecoder().decode(BASE_64_IMAGE));

    assertThat(imageOutput).isNotEmpty();
  }

  @Test
  @DisplayName("Should throw ImageProcessingException when input is null.")
  void shouldThrowImageProcessingExceptionWhenInputIsNull() {
    assertThatThrownBy(() -> imageThumbnailService.convertImageToThumbnails(null))
        .isInstanceOf(ImageProcessingException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  @DisplayName("Should throw ImageProcessingException when image is corrupt.")
  void shouldThrowImageProcessingExceptionWhenImageIsCorrupt() {
    assertThatThrownBy(() -> imageThumbnailService.convertImageToThumbnails(new byte[] {0, 1, 2}))
        .isInstanceOf(ImageProcessingException.class);
  }
}
