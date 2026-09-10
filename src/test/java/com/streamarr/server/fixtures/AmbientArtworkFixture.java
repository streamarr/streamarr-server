package com.streamarr.server.fixtures;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.Builder;

public final class AmbientArtworkFixture {

  private AmbientArtworkFixture() {}

  public static byte[] profiles() throws IOException {
    var image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
    var bands =
        List.of(
            Band.builder().rgb(0x00A0A0).width(90).build(),
            Band.builder().rgb(0x103070).width(60).build(),
            Band.builder().rgb(0x283830).width(60).build(),
            Band.builder().rgb(0x68F8F8).width(45).build(),
            Band.builder().rgb(0xC8D0C8).width(45).build());
    var offset = 0;
    var graphics = image.createGraphics();
    for (var band : bands) {
      graphics.setColor(new Color(band.rgb()));
      graphics.fillRect(offset, 0, band.width(), image.getHeight());
      offset += band.width();
    }

    graphics.dispose();
    return png(image);
  }

  @Builder(builderMethodName = "solid", buildMethodName = "png")
  private static byte[] solidArtwork(int rgb) throws IOException {
    var image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(new Color(rgb));
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.dispose();
    return png(image);
  }

  private static byte[] png(BufferedImage image) throws IOException {
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  @Builder
  private record Band(int rgb, int width) {}
}
