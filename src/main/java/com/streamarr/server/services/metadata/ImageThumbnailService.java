package com.streamarr.server.services.metadata;

import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ImageThumbnailService {

    private final Logger log;

    public byte[] convertImageToThumbnails(byte[] imageData) {
        if (imageData == null) {
            throw new RuntimeException("Image input data must not be null.");
        }

        try {
            var startTime = Instant.now();

            var inputStream = new ByteArrayInputStream(imageData);
            var outputStream = new ByteArrayOutputStream();

            var srcImg = ImageIO.read(inputStream);

            // TODO: Create multiple thumbnail sizes
            Thumbnails.of(srcImg)
                .size(200, 200)
                .outputFormat("jpg")
                .toOutputStream(outputStream);

            var endTime = Instant.now();
            var timeElapsed = Duration.between(startTime, endTime);
            log.info("created thumbnail in {} ms.", timeElapsed.toMillis());

            return outputStream.toByteArray();

        } catch (IOException | NullPointerException ex) {
            throw new RuntimeException(ex);
        }
    }
}
