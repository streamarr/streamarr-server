package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import java.util.List;
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
  @DisplayName("Should batch load images grouped by entity type")
  void shouldBatchLoadImagesGroupedByEntityType() throws Exception {
    var movieId = UUID.randomUUID();
    var personId = UUID.randomUUID();
    saveImage(movieId, ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL);
    saveImage(personId, ImageEntityType.PERSON, ImageType.PROFILE, ImageSize.SMALL);

    var keys =
        Set.of(
            new ImageLoaderKey(movieId, ImageEntityType.MOVIE),
            new ImageLoaderKey(personId, ImageEntityType.PERSON));

    var result = dataLoader.load(keys).toCompletableFuture().get();

    assertThat(result).hasSize(2);
    assertThat(result.get(new ImageLoaderKey(movieId, ImageEntityType.MOVIE))).hasSize(1);
    assertThat(result.get(new ImageLoaderKey(personId, ImageEntityType.PERSON))).hasSize(1);
  }

  @Test
  @DisplayName("Should group image rows by image type when building DTOs")
  void shouldGroupImageRowsByImageTypeWhenBuildingDtos() {
    var entityId = UUID.randomUUID();
    var images =
        List.of(
            buildImage(entityId, ImageType.POSTER, ImageSize.SMALL, 185, 278),
            buildImage(entityId, ImageType.POSTER, ImageSize.LARGE, 500, 750),
            buildImage(entityId, ImageType.BACKDROP, ImageSize.SMALL, 300, 169));

    var dtos = ImageDataLoader.buildImageDtos(images);

    assertThat(dtos).hasSize(2);
    var posterDto =
        dtos.stream().filter(d -> d.imageType() == ImageType.POSTER).findFirst().orElseThrow();
    assertThat(posterDto.variants()).hasSize(2);
    var backdropDto =
        dtos.stream().filter(d -> d.imageType() == ImageType.BACKDROP).findFirst().orElseThrow();
    assertThat(backdropDto.variants()).hasSize(1);
  }

  @Test
  @DisplayName("Should hoist blurHash from small variant to image level")
  void shouldHoistBlurHashFromSmallVariantToImageLevel() {
    var entityId = UUID.randomUUID();
    var smallImage = buildImage(entityId, ImageType.POSTER, ImageSize.SMALL, 185, 278);
    smallImage.setBlurHash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
    var largeImage = buildImage(entityId, ImageType.POSTER, ImageSize.LARGE, 500, 750);

    var dtos = ImageDataLoader.buildImageDtos(List.of(smallImage, largeImage));

    assertThat(dtos).hasSize(1);
    assertThat(dtos.getFirst().blurHash()).isEqualTo("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
  }

  @Test
  @DisplayName("Should compute aspect ratio from small variant dimensions")
  void shouldComputeAspectRatioFromSmallVariantDimensions() {
    var entityId = UUID.randomUUID();
    var images = List.of(buildImage(entityId, ImageType.POSTER, ImageSize.SMALL, 185, 278));

    var dtos = ImageDataLoader.buildImageDtos(images);

    assertThat(dtos.getFirst().aspectRatio())
        .isCloseTo(185f / 278f, org.assertj.core.api.Assertions.within(0.001f));
  }

  @Test
  @DisplayName("Should return null blurHash when small variant missing")
  void shouldReturnNullBlurHashWhenSmallVariantMissing() {
    var entityId = UUID.randomUUID();
    var images = List.of(buildImage(entityId, ImageType.POSTER, ImageSize.LARGE, 500, 750));

    var dtos = ImageDataLoader.buildImageDtos(images);

    assertThat(dtos.getFirst().blurHash()).isNull();
  }

  @Test
  @DisplayName("Should return empty list when entity has no images")
  void shouldReturnEmptyListWhenEntityHasNoImages() throws Exception {
    var key = new ImageLoaderKey(UUID.randomUUID(), ImageEntityType.MOVIE);

    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result.get(key)).isEmpty();
  }

  private Image saveImage(
      UUID entityId, ImageEntityType entityType, ImageType imageType, ImageSize variant) {
    return imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(entityType)
            .imageType(imageType)
            .variant(variant)
            .width(185)
            .height(278)
            .path("test/path.jpg")
            .build());
  }

  private Image buildImage(
      UUID entityId, ImageType imageType, ImageSize variant, int width, int height) {
    return Image.builder()
        .entityId(entityId)
        .entityType(ImageEntityType.MOVIE)
        .imageType(imageType)
        .variant(variant)
        .width(width)
        .height(height)
        .path("test/path.jpg")
        .build();
  }
}
