package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.ImageFormat;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.services.metadata.color.AmbientColorExtractor;
import io.trbl.blurhash.BlurHash;
import java.awt.AlphaComposite;
import java.awt.color.ColorSpace;
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
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.Builder;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

@Service
public class ImageVariantService {

  private static final int BLUR_HASH_COMPONENT_X = 4;
  private static final int BLUR_HASH_COMPONENT_Y = 3;
  // A 4x3 BlurHash keeps only the lowest frequencies, which a 128-pixel copy still carries.
  private static final int BLUR_HASH_MAX_INPUT_EDGE = 128;
  // The pixel count of 8K, 7680 x 4320, for the decoded image and for each resized variant. A few
  // hundred compressed kilobytes can declare more pixels than the heap holds, and a narrow image
  // grows when it is resized to a variant's width.
  private static final long MAX_PIXELS = 7680L * 4320;

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

  @Builder
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

    if (ImageFormat.ofBytes(originalImageData).isEmpty()) {
      throw new ImageProcessingException("Image data is neither JPEG nor PNG.");
    }

    var sourceImage = decodeImage(originalImageData);
    var transparent = hasTransparentPixel(sourceImage);
    if (isGrayscaleWithAlpha(sourceImage)) {
      sourceImage = copyGrayscaleSamplesToArgb(sourceImage);
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
      requireAtMostMaxPixels(targetWidth, heightAtWidth(sourceImage, targetWidth));
      if (transparent) {
        var resized = resizeIntoArgb(sourceImage, targetWidth);
        variants.add(
            GeneratedVariant.builder()
                .variant(size)
                .data(toPngBytes(resized))
                .width(resized.getWidth())
                .height(resized.getHeight())
                .ambientColors(Optional.empty())
                .build());
        continue;
      }

      var resized = resize(sourceImage, targetWidth);
      var blurHash = size == ImageSize.SMALL ? computeBlurHash(resized) : null;
      var ambientColors =
          size == ImageSize.SMALL
              ? AmbientColorExtractor.extract(resized)
              : Optional.<AmbientColors>empty();
      var data = toJpegBytes(resized);
      variants.add(
          GeneratedVariant.builder()
              .variant(size)
              .data(data)
              .width(resized.getWidth())
              .height(resized.getHeight())
              .blurHash(blurHash)
              .ambientColors(ambientColors)
              .build());
    }

    variants.add(
        GeneratedVariant.builder()
            .variant(ImageSize.ORIGINAL)
            .data(originalImageData)
            .width(sourceImage.getWidth())
            .height(sourceImage.getHeight())
            .ambientColors(Optional.empty())
            .build());

    return variants;
  }

  private BufferedImage decodeImage(byte[] imageData) {
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageData))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new ImageProcessingException("Failed to decode image data.");
      }

      return read(readers.next(), input);
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }
  }

  private static BufferedImage read(ImageReader reader, ImageInputStream input) throws IOException {
    try {
      reader.setInput(input, true, true);
      requireAtMostMaxPixels(reader.getWidth(0), reader.getHeight(0));
      return reader.read(0);
    } finally {
      reader.dispose();
    }
  }

  private static void requireAtMostMaxPixels(long width, long height) {
    if (width * height > MAX_PIXELS) {
      throw new ImageProcessingException(
          "Image or variant of %dx%d pixels exceeds the limit of %d pixels."
              .formatted(width, height, MAX_PIXELS));
    }
  }

  // Resizing keeps the aspect ratio, so 1 x 8192 pixels resized to 300 wide become 300 x 2457600.
  private static long heightAtWidth(BufferedImage image, int width) {
    return (long) image.getHeight() * width / image.getWidth();
  }

  private BufferedImage resize(BufferedImage source, int targetWidth) {
    return render(Thumbnails.of(source).width(targetWidth));
  }

  // Thumbnailator resizes into a new image of its source's type unless it is given another. A new
  // palette image has an opaque palette, so an image with transparency is resized into ARGB.
  private static BufferedImage resizeIntoArgb(BufferedImage source, int targetWidth) {
    return render(Thumbnails.of(source).imageType(BufferedImage.TYPE_INT_ARGB).width(targetWidth));
  }

  private static BufferedImage render(Thumbnails.Builder<BufferedImage> thumbnail) {
    try {
      return thumbnail.asBufferedImage();
    } catch (IOException e) {
      throw new ImageProcessingException(e);
    }
  }

  private String computeBlurHash(BufferedImage smallVariant) {
    return BlurHash.encode(
        blurHashInput(smallVariant), BLUR_HASH_COMPONENT_X, BLUR_HASH_COMPONENT_Y);
  }

  private static BufferedImage blurHashInput(BufferedImage smallVariant) {
    var fitsWithinInput =
        smallVariant.getWidth() <= BLUR_HASH_MAX_INPUT_EDGE
            && smallVariant.getHeight() <= BLUR_HASH_MAX_INPUT_EDGE;
    // Thumbnailator's progressive resizer draws nothing when it narrows an image that is one pixel
    // high, so fitting it would hash as black.
    if (fitsWithinInput || smallVariant.getHeight() == 1) {
      return smallVariant;
    }

    return render(
        Thumbnails.of(smallVariant).size(BLUR_HASH_MAX_INPUT_EDGE, BLUR_HASH_MAX_INPUT_EDGE));
  }

  // Reads alpha as the image stores it, one row at a time. A palette holds alpha in its entries.
  // Every other color model holds it in its own band of each pixel, where a JPEG or a PNG stores
  // it as an unsigned integer of 8 or 16 bits.
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

  private static boolean isGrayscaleWithAlpha(BufferedImage image) {
    var colorModel = image.getColorModel();
    return colorModel.hasAlpha() && colorModel.getColorSpace().getType() == ColorSpace.TYPE_GRAY;
  }

  // ImageIO decodes a grayscale PNG with alpha as linear gray. Java2D converts linear gray to
  // sRGB when it draws such an image, which lightens it: gray 128 becomes 188. Copying the
  // samples keeps their values.
  private static BufferedImage copyGrayscaleSamplesToArgb(BufferedImage image) {
    var samples = image.getRaster();
    var width = samples.getWidth();
    var maxSample = (1 << samples.getSampleModel().getSampleSize(0)) - 1;
    var argb = new BufferedImage(width, samples.getHeight(), BufferedImage.TYPE_INT_ARGB);
    var gray = new int[width];
    var alpha = new int[width];
    var argbRow = new int[width];
    for (var y = 0; y < samples.getHeight(); y++) {
      samples.getSamples(samples.getMinX(), samples.getMinY() + y, width, 1, 0, gray);
      samples.getSamples(samples.getMinX(), samples.getMinY() + y, width, 1, 1, alpha);
      Arrays.setAll(
          argbRow, x -> argbOf(toEightBits(gray[x], maxSample), toEightBits(alpha[x], maxSample)));
      argb.getRaster().setDataElements(0, y, width, 1, argbRow);
    }

    return argb;
  }

  private static int toEightBits(int sample, int maxSample) {
    return (sample * 0xFF + maxSample / 2) / maxSample;
  }

  private static int argbOf(int gray, int alpha) {
    return alpha << 24 | gray << 16 | gray << 8 | gray;
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
