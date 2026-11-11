package com.streamarr.server.fakes;

import java.awt.Color;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class TestImages {

  /** The opaque colour that the images with a transparent border show at their centre. */
  public static final int OPAQUE_TEAL = 0xFF00A0A0;

  private static final int TRANSPARENT = 0;
  private static final int BORDERED_WIDTH = 400;
  private static final int BORDERED_HEIGHT = 200;

  private TestImages() {}

  public static byte[] createTestImage(int width, int height) {
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

  public static byte[] createSolidPngImage(int width, int height, int rgb) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(new Color(rgb));
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();

    return writePng(image);
  }

  public static byte[] createDistinctColorPngImage() {
    var image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(new Color(0x202020));
    graphics.fillRect(0, 0, 150, 100);
    graphics.setColor(new Color(0x404040));
    graphics.fillRect(150, 0, 150, 100);
    graphics.setColor(new Color(0x808080));
    graphics.fillRect(0, 100, 150, 100);
    graphics.setColor(new Color(0xC0C0C0));
    graphics.fillRect(150, 100, 150, 100);
    graphics.setColor(new Color(0x00A0A0));
    graphics.fillRect(148, 98, 4, 4);
    graphics.dispose();

    return writePng(image);
  }

  public static byte[] createTransparentPngImage() {
    return writePng(new BufferedImage(185, 278, BufferedImage.TYPE_INT_ARGB));
  }

  /**
   * A logo-like PNG: an opaque teal rectangle over the middle half of each axis, and transparent
   * pixels everywhere else.
   */
  public static byte[] createPngImageWithTransparentBorder() {
    var image = new BufferedImage(BORDERED_WIDTH, BORDERED_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    graphics.setColor(new Color(OPAQUE_TEAL, true));
    graphics.fillRect(
        BORDERED_WIDTH / 4, BORDERED_HEIGHT / 4, BORDERED_WIDTH / 2, BORDERED_HEIGHT / 2);
    graphics.dispose();

    return writePng(image);
  }

  /** The same picture as a palette PNG, whose transparency is a palette entry. */
  public static byte[] createPalettePngImageWithTransparentBorder() {
    var palette =
        new IndexColorModel(
            1, 2, new int[] {TRANSPARENT, OPAQUE_TEAL}, 0, true, 0, DataBuffer.TYPE_BYTE);
    var image =
        new BufferedImage(BORDERED_WIDTH, BORDERED_HEIGHT, BufferedImage.TYPE_BYTE_BINARY, palette);
    for (var y = BORDERED_HEIGHT / 4; y < BORDERED_HEIGHT * 3 / 4; y++) {
      for (var x = BORDERED_WIDTH / 4; x < BORDERED_WIDTH * 3 / 4; x++) {
        image.getRaster().setSample(x, y, 0, 1);
      }
    }

    return writePng(image);
  }

  /** A PNG that stores an alpha channel in which every pixel is opaque. */
  public static byte[] createOpaquePngImageWithAlphaChannel() {
    var image = new BufferedImage(600, 900, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    graphics.setColor(new Color(OPAQUE_TEAL, true));
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.dispose();

    return writePng(image);
  }

  /** A solid teal PNG with 16 bits for each of red, green and blue, and no alpha channel. */
  public static byte[] createSixteenBitOpaquePngImage() {
    var colorModel =
        new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            false,
            false,
            Transparency.OPAQUE,
            DataBuffer.TYPE_USHORT);
    var raster = colorModel.createCompatibleWritableRaster(600, 900);
    var tealRow = new int[raster.getWidth()];
    Arrays.fill(tealRow, 0xA0A0);
    for (var y = 0; y < raster.getHeight(); y++) {
      raster.setSamples(0, y, raster.getWidth(), 1, 1, tealRow);
      raster.setSamples(0, y, raster.getWidth(), 1, 2, tealRow);
    }

    return writePng(new BufferedImage(colorModel, raster, false, null));
  }

  /** A PNG with one 16-bit grey sample for each pixel, which no JPEG encoder accepts. */
  public static byte[] createSixteenBitGrayscalePngImage() {
    return writePng(new BufferedImage(600, 900, BufferedImage.TYPE_USHORT_GRAY));
  }

  public static BufferedImage decode(byte[] imageData) {
    try {
      return ImageIO.read(new ByteArrayInputStream(imageData));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The lower-case name of the format that ImageIO reads the bytes as, such as png or jpeg. */
  public static String formatNameOf(byte[] imageData) {
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageData))) {
      return ImageIO.getImageReaders(input).next().getFormatName().toLowerCase(Locale.ROOT);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static int alphaOfCornerPixel(byte[] imageData) {
    return decode(imageData).getRGB(0, 0) >>> 24;
  }

  public static int centrePixelOf(byte[] imageData) {
    var image = decode(imageData);
    return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
  }

  private static byte[] writePng(BufferedImage image) {
    return write(image, "png");
  }

  private static byte[] write(BufferedImage image, String formatName) {
    try (var outputStream = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, formatName, outputStream)) {
        throw new IllegalStateException("No " + formatName + " writer accepts the test image");
      }

      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** CC0-1.0 synthetic fixture with no third-party creative content. */
  public static byte[] createTestImageWithMismatchedColorProfile() {
    var jpeg = createTestImage(16, 16);
    var profile = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
    var profileSegment =
        ByteBuffer.allocate(profile.length + 18)
            .put((byte) 0xff)
            .put((byte) 0xe2)
            .putShort((short) (profile.length + 16))
            .put("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII))
            .put((byte) 1)
            .put((byte) 1)
            .put(profile)
            .array();

    var fixture = new ByteArrayOutputStream(jpeg.length + profileSegment.length);
    fixture.write(jpeg, 0, 2);
    fixture.writeBytes(profileSegment);
    fixture.write(jpeg, 2, jpeg.length - 2);
    return fixture.toByteArray();
  }
}
