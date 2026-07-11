package com.streamarr.server.fakes;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
}
