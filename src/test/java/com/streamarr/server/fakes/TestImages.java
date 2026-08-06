package com.streamarr.server.fakes;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

public final class TestImages {

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
