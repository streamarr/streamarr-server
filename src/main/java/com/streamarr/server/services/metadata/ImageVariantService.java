package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import io.trbl.blurhash.BlurHash;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

@Service
public class ImageVariantService {

  private static final int BLUR_HASH_COMPONENT_X = 4;
  private static final int BLUR_HASH_COMPONENT_Y = 3;

  private static final Map<ImageType, Map<ImageSize, Integer>> WIDTH_TABLE =
      Map.of(
          ImageType.POSTER,
              Map.of(ImageSize.SMALL, 185, ImageSize.MEDIUM, 342, ImageSize.LARGE, 500),
          ImageType.BACKDROP,
              Map.of(ImageSize.SMALL, 300, ImageSize.MEDIUM, 780, ImageSize.LARGE, 1280),
          ImageType.PROFILE,
              Map.of(ImageSize.SMALL, 185, ImageSize.MEDIUM, 342, ImageSize.LARGE, 500),
          ImageType.STILL,
              Map.of(ImageSize.SMALL, 300, ImageSize.MEDIUM, 780, ImageSize.LARGE, 1280),
          ImageType.LOGO, Map.of(ImageSize.SMALL, 92, ImageSize.MEDIUM, 185, ImageSize.LARGE, 500));

  public record GeneratedVariant(
      ImageSize variant, byte[] data, int width, int height, String blurHash) {

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof GeneratedVariant that)) return false;
      return width == that.width
          && height == that.height
          && variant == that.variant
          && Arrays.equals(data, that.data)
          && Objects.equals(blurHash, that.blurHash);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(variant, width, height, blurHash);
      result = 31 * result + Arrays.hashCode(data);
      return result;
    }

    @Override
    public String toString() {
      return "GeneratedVariant[variant="
          + variant
          + ", data="
          + Arrays.toString(data)
          + ", width="
          + width
          + ", height="
          + height
          + ", blurHash="
          + blurHash
          + "]";
    }
  }

  public List<GeneratedVariant> generateVariants(byte[] originalImageData, ImageType imageType) {
    if (originalImageData == null) {
      throw new ImageProcessingException("Image input data must not be null.");
    }

    BufferedImage sourceImage;
    try {
      sourceImage = ImageIO.read(new ByteArrayInputStream(originalImageData));
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }

    if (sourceImage == null) {
      throw new ImageProcessingException("Failed to decode image data.");
    }

    var widths = WIDTH_TABLE.get(imageType);
    var variants = new ArrayList<GeneratedVariant>();

    for (var size : List.of(ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE)) {
      var targetWidth = widths.get(size);
      var resized = resize(sourceImage, targetWidth);
      var blurHash = size == ImageSize.SMALL ? computeBlurHash(resized) : null;
      var data = toJpegBytes(resized);
      variants.add(
          new GeneratedVariant(size, data, resized.getWidth(), resized.getHeight(), blurHash));
    }

    variants.add(
        new GeneratedVariant(
            ImageSize.ORIGINAL,
            originalImageData,
            sourceImage.getWidth(),
            sourceImage.getHeight(),
            null));

    return variants;
  }

  private BufferedImage resize(BufferedImage source, int targetWidth) {
    try {
      return Thumbnails.of(source).width(targetWidth).asBufferedImage();
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }
  }

  private String computeBlurHash(BufferedImage image) {
    return BlurHash.encode(image, BLUR_HASH_COMPONENT_X, BLUR_HASH_COMPONENT_Y);
  }

  private byte[] toJpegBytes(BufferedImage image) {
    try (var outputStream = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", outputStream);
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }
  }
}
