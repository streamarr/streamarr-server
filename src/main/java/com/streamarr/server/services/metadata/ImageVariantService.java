package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.services.metadata.color.AmbientColorExtractor;
import io.trbl.blurhash.BlurHash;
import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

@Service
public class ImageVariantService {

  private static final int BLUR_HASH_COMPONENT_X = 4;
  private static final int BLUR_HASH_COMPONENT_Y = 3;

  private static final List<ImageSize> RESIZABLE_SIZES =
      List.of(ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE);

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
      ImageSize variant,
      byte[] data,
      int width,
      int height,
      String blurHash,
      Optional<AmbientColors> ambientColors) {

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o
          instanceof
          GeneratedVariant(
              var otherVariant,
              var otherData,
              var otherWidth,
              var otherHeight,
              var otherBlurHash,
              var otherAmbientColors))) return false;
      return width == otherWidth
          && height == otherHeight
          && variant == otherVariant
          && Arrays.equals(data, otherData)
          && Objects.equals(blurHash, otherBlurHash)
          && Objects.equals(ambientColors, otherAmbientColors);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(variant, width, height, blurHash, ambientColors);
      result = 31 * result + Arrays.hashCode(data);
      return result;
    }

    @Override
    public String toString() {
      return "GeneratedVariant[variant="
          + variant
          + ", dataLength="
          + (data == null ? 0 : data.length)
          + ", width="
          + width
          + ", height="
          + height
          + ", blurHash="
          + blurHash
          + ", ambientColors="
          + ambientColors
          + "]";
    }
  }

  public List<GeneratedVariant> generateVariants(byte[] originalImageData, ImageType imageType) {
    if (originalImageData == null) {
      throw new ImageProcessingException("Image input data must not be null.");
    }

    var sourceImage = decodeImage(originalImageData);
    var transparent = hasTransparentPixel(sourceImage);
    if (transparent) {
      sourceImage = withAlphaThatSurvivesResizing(sourceImage);
    }

    var widths = WIDTH_TABLE.get(imageType);
    if (widths == null) {
      throw new ImageProcessingException("No width configuration for image type: " + imageType);
    }
    var variants = new ArrayList<GeneratedVariant>();

    for (var size : RESIZABLE_SIZES) {
      var targetWidth = widths.get(size);
      if (targetWidth == null) {
        throw new ImageProcessingException(
            "No width configuration for image size: " + size + " on image type: " + imageType);
      }
      var resized = resize(sourceImage, targetWidth);
      if (transparent) {
        variants.add(
            new GeneratedVariant(
                size,
                toPngBytes(resized),
                resized.getWidth(),
                resized.getHeight(),
                null,
                Optional.empty()));
        continue;
      }

      var blurHash = size == ImageSize.SMALL ? computeBlurHash(resized) : null;
      var ambientColors =
          size == ImageSize.SMALL
              ? AmbientColorExtractor.extract(resized)
              : Optional.<AmbientColors>empty();
      var data = toJpegBytes(resized);
      variants.add(
          new GeneratedVariant(
              size, data, resized.getWidth(), resized.getHeight(), blurHash, ambientColors));
    }

    variants.add(
        new GeneratedVariant(
            ImageSize.ORIGINAL,
            originalImageData,
            sourceImage.getWidth(),
            sourceImage.getHeight(),
            null,
            Optional.empty()));

    return variants;
  }

  private BufferedImage decodeImage(byte[] imageData) {
    BufferedImage image;
    try {
      image = ImageIO.read(new ByteArrayInputStream(imageData));
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }

    if (image == null) {
      throw new ImageProcessingException("Failed to decode image data.");
    }
    return image;
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

  // Reads alpha at the precision the image stores it, one row at a time. A palette holds alpha
  // in its entries. Every other color model holds it in the last band of each pixel.
  private static boolean hasTransparentPixel(BufferedImage image) {
    if (!image.getColorModel().hasAlpha()) {
      return false;
    }

    if (image.getColorModel() instanceof IndexColorModel palette) {
      return hasSampleMatching(image.getRaster(), entry -> palette.getAlpha(entry) != 0xFF);
    }

    var alphaBand = image.getAlphaRaster();
    var opaque = (1 << alphaBand.getSampleModel().getSampleSize(0)) - 1;
    return hasSampleMatching(alphaBand, alpha -> alpha != opaque);
  }

  private static boolean hasSampleMatching(Raster firstBand, IntPredicate condition) {
    var row = new int[firstBand.getWidth()];
    for (var y = firstBand.getMinY(); y < firstBand.getMinY() + firstBand.getHeight(); y++) {
      firstBand.getSamples(firstBand.getMinX(), y, row.length, 1, 0, row);
      if (Arrays.stream(row).anyMatch(condition)) {
        return true;
      }
    }

    return false;
  }

  // Thumbnailator resizes into a new image of its source's type, and a new palette image has an
  // opaque palette. A transparent palette image is therefore resized from an ARGB copy.
  private static BufferedImage withAlphaThatSurvivesResizing(BufferedImage transparentImage) {
    if (transparentImage.getColorModel() instanceof IndexColorModel) {
      return copy(transparentImage, BufferedImage.TYPE_INT_ARGB);
    }

    return transparentImage;
  }

  private static BufferedImage copy(BufferedImage image, int imageType) {
    var copy = new BufferedImage(image.getWidth(), image.getHeight(), imageType);
    var graphics = copy.createGraphics();
    graphics.setComposite(AlphaComposite.Src);
    graphics.drawImage(image, 0, 0, null);
    graphics.dispose();
    return copy;
  }

  private byte[] toJpegBytes(BufferedImage image) {
    var opaqueImage = image;
    // The JPEG encoder accepts no alpha channel. Every pixel is opaque here, so the copy loses
    // nothing.
    if (image.getColorModel().hasAlpha()) {
      opaqueImage = copy(image, BufferedImage.TYPE_INT_RGB);
    }

    return encode(opaqueImage, "jpg");
  }

  private byte[] toPngBytes(BufferedImage image) {
    return encode(image, "png");
  }

  private byte[] encode(BufferedImage image, String formatName) {
    try (var outputStream = new ByteArrayOutputStream()) {
      // ImageIO.write writes nothing and returns false when no encoder accepts the image.
      if (!ImageIO.write(image, formatName, outputStream)) {
        throw new ImageProcessingException(
            "No %s encoder accepts the image (BufferedImage type %d, %d bits per pixel)."
                .formatted(formatName, image.getType(), image.getColorModel().getPixelSize()));
      }

      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }
  }
}
