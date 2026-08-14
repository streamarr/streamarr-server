package com.streamarr.server.services.metadata;

import static com.streamarr.server.fakes.TestImages.createSolidPngImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.ImageService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("IntegrationTest")
@DisplayName("Image Replacement Lock Timeout Integration Tests")
@SpringBootTest(properties = "image.replacement-lock-timeout=200ms")
class ImageReplacementLockTimeoutIT extends AbstractIntegrationTest {

  @Autowired private ImageRepository imageRepository;
  @Autowired private ImageService imageService;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should preserve existing artwork when replacement exceeds lock timeout")
  void shouldPreserveExistingArtworkWhenReplacementExceedsLockTimeout() throws Exception {
    var entityId = UUID.randomUUID();
    var original = processedImage(entityId, 0x0000FF, "/original.jpg");
    var holderReplacement = processedImage(entityId, 0x00FFFF, "/holder.jpg");
    var contenderReplacement = processedImage(entityId, 0xFF00FF, "/contender.jpg");
    imageService.saveImages(original.images());

    try {
      try (var lockConnection = dataSource.getConnection();
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        lockConnection.setAutoCommit(false);
        lockArtwork(lockConnection, holderReplacement.images().getFirst());

        var contender =
            executor.submit(
                () -> {
                  imageService.replaceImages(contenderReplacement);
                  return null;
                });

        try {
          assertThatThrownBy(() -> contender.get(2, TimeUnit.SECONDS))
              .isInstanceOf(ExecutionException.class)
              .hasStackTraceContaining("canceling statement due to lock timeout");
          assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
              .hasSize(ImageSize.values().length)
              .extracting(Image::getKey)
              .containsOnly("/original.jpg");
          assertThat(contenderReplacement.writtenFiles())
              .allSatisfy(path -> assertThat(path).doesNotExist());
          assertThat(lockConnection.isValid(1)).isTrue();

          lockConnection.commit();
        } finally {
          lockConnection.rollback();
        }
      }

      imageService.replaceImages(holderReplacement);

      assertThat(imageRepository.findByEntityIdAndEntityType(entityId, ImageEntityType.MOVIE))
          .hasSize(ImageSize.values().length)
          .extracting(Image::getKey)
          .containsOnly("/holder.jpg");
      assertThat(holderReplacement.writtenFiles()).allSatisfy(path -> assertThat(path).exists());
      assertThat(original.writtenFiles()).allSatisfy(path -> assertThat(path).doesNotExist());
    } finally {
      imageService.deleteImagesForEntity(entityId, ImageEntityType.MOVIE);
      imageService.deleteFiles(original.writtenFiles());
      imageService.deleteFiles(holderReplacement.writtenFiles());
      imageService.deleteFiles(contenderReplacement.writtenFiles());
    }
  }

  private ImageService.ProcessedImage processedImage(UUID entityId, int color, String key) {
    return imageService.processImage(
        createSolidPngImage(600, 900, color),
        ImageType.POSTER,
        entityId,
        ImageEntityType.MOVIE,
        key);
  }

  private void lockArtwork(Connection connection, Image image) throws SQLException {
    var artworkIdentity =
        image.getEntityId() + "|" + image.getEntityType() + "|" + image.getImageType();
    var lockKey =
        UUID.nameUUIDFromBytes(artworkIdentity.getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits();
    try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      statement.setLong(1, lockKey);
      statement.execute();
    }
  }
}
