package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Image DataLoader Tests")
class ImageDataLoaderTest {

  private FakeImageRepository imageRepository;
  private ImageDataLoader dataLoader;

  @BeforeEach
  void setUp() {
    imageRepository = new FakeImageRepository();
    dataLoader = new ImageDataLoader(imageRepository);
  }

  @Test
  @DisplayName("Should return images for each key when batch loading")
  void shouldReturnImagesForEachKeyWhenBatchLoading() throws Exception {
    var movieId = UUID.randomUUID();
    var personId = UUID.randomUUID();
    saveImage(movieId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278, null);
    saveImage(
        personId, ImageEntityType.PERSON, ImageType.PROFILE, ImageSize.SMALL, 185, 278, null);

    var keys =
        Set.of(
            new ImageLoaderKey(movieId, ImageEntityType.MOVIE),
            new ImageLoaderKey(personId, ImageEntityType.PERSON));

    var result = dataLoader.load(keys).toCompletableFuture().get();

    assertThat(result.get(new ImageLoaderKey(movieId, ImageEntityType.MOVIE))).hasSize(1);
    assertThat(result.get(new ImageLoaderKey(personId, ImageEntityType.PERSON))).hasSize(1);
  }

  @Test
  @DisplayName("Should group image rows by image type when building DTOs")
  void shouldGroupImageRowsByImageTypeWhenBuildingDtos() throws Exception {
    var entityId = UUID.randomUUID();
    saveImage(entityId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278, null);
    saveImage(entityId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.LARGE, 500, 750, null);
    saveImage(
        entityId, ImageEntityType.MOVIE, ImageType.BACKDROP, ImageSize.SMALL, 300, 169, null);

    var key = new ImageLoaderKey(entityId, ImageEntityType.MOVIE);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    var dtos = result.get(key);
    assertThat(dtos).hasSize(2);
    var posterDto =
        dtos.stream().filter(d -> d.imageType() == ImageType.POSTER).findFirst().orElseThrow();
    assertThat(posterDto.variants()).hasSize(2);
    var backdropDto =
        dtos.stream().filter(d -> d.imageType() == ImageType.BACKDROP).findFirst().orElseThrow();
    assertThat(backdropDto.variants()).hasSize(1);
  }

  @Test
  @DisplayName("Should hoist blurHash to image level when small variant present")
  void shouldHoistBlurHashToImageLevelWhenSmallVariantPresent() throws Exception {
    var entityId = UUID.randomUUID();
    saveImage(
        entityId,
        ImageEntityType.MOVIE,
        ImageType.POSTER,
        ImageSize.SMALL,
        185,
        278,
        "LEHV6nWB2yk8pyo0adR*.7kCMdnj");
    saveImage(entityId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.LARGE, 500, 750, null);

    var key = new ImageLoaderKey(entityId, ImageEntityType.MOVIE);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    var dtos = result.get(key);
    assertThat(dtos).hasSize(1);
    assertThat(dtos.getFirst().blurHash()).isEqualTo("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
  }

  @Test
  @DisplayName("Should compute aspect ratio when small variant present")
  void shouldComputeAspectRatioWhenSmallVariantPresent() throws Exception {
    var entityId = UUID.randomUUID();
    saveImage(entityId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278, null);

    var key = new ImageLoaderKey(entityId, ImageEntityType.MOVIE);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result.get(key).getFirst().aspectRatio()).isCloseTo(0.6655f, within(0.001f));
  }

  @Test
  @DisplayName("Should return null blurHash when small variant missing")
  void shouldReturnNullBlurHashWhenSmallVariantMissing() throws Exception {
    var entityId = UUID.randomUUID();
    saveImage(entityId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.LARGE, 500, 750, null);

    var key = new ImageLoaderKey(entityId, ImageEntityType.MOVIE);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result.get(key).getFirst().blurHash()).isNull();
  }

  @Test
  @DisplayName("Should return empty list when entity has no images")
  void shouldReturnEmptyListWhenEntityHasNoImages() throws Exception {
    var key = new ImageLoaderKey(UUID.randomUUID(), ImageEntityType.MOVIE);

    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result.get(key)).isEmpty();
  }

  private Image saveImage(
      UUID entityId,
      ImageEntityType entityType,
      ImageType imageType,
      ImageSize variant,
      int width,
      int height,
      String blurHash) {
    return imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(entityType)
            .imageType(imageType)
            .variant(variant)
            .width(width)
            .height(height)
            .blurHash(blurHash)
            .path("test/path.jpg")
            .build());
  }
}
