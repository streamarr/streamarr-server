package com.streamarr.server.services.metadata;

import com.streamarr.server.exceptions.ImageProcessingException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageThumbnailService {

  // TODO(#37): generate multiple thumbnail sizes and blur-up placeholder
  public byte[] convertImageToThumbnails(byte[] imageData) {
    if (imageData == null) {
      throw new ImageProcessingException("Image input data must not be null.");
    }

    try {
      var startTime = Instant.now();

      var inputStream = new ByteArrayInputStream(imageData);
      var outputStream = new ByteArrayOutputStream();

      var srcImg = ImageIO.read(inputStream);

      Thumbnails.of(srcImg).size(200, 200).outputFormat("jpg").toOutputStream(outputStream);

      var endTime = Instant.now();
      var timeElapsed = Duration.between(startTime, endTime);
      log.info("created thumbnail in {} ms.", timeElapsed.toMillis());

      return outputStream.toByteArray();

    } catch (IOException | NullPointerException ex) {
      throw new ImageProcessingException(ex);
    }
  }
}
