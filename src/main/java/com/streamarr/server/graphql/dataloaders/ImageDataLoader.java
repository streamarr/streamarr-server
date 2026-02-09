package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.graphql.dto.ImageDto;
import com.streamarr.server.graphql.dto.ImageVariantDto;
import com.streamarr.server.repositories.media.ImageRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "images")
@RequiredArgsConstructor
public class ImageDataLoader implements MappedBatchLoader<ImageLoaderKey, List<ImageDto>> {

  private final ImageRepository imageRepository;

  @Override
  public CompletionStage<Map<ImageLoaderKey, List<ImageDto>>> load(Set<ImageLoaderKey> keys) {
    return CompletableFuture.supplyAsync(
        () -> {
          var result = new HashMap<ImageLoaderKey, List<ImageDto>>();

          var keysByType = keys.stream().collect(Collectors.groupingBy(ImageLoaderKey::entityType));

          for (var entry : keysByType.entrySet()) {
            var entityType = entry.getKey();
            var entityIds = entry.getValue().stream().map(ImageLoaderKey::entityId).toList();

            var images = imageRepository.findByEntityTypeAndEntityIdIn(entityType, entityIds);

            var imagesByEntity = images.stream().collect(Collectors.groupingBy(Image::getEntityId));

            for (var key : entry.getValue()) {
              var entityImages = imagesByEntity.getOrDefault(key.entityId(), List.of());
              result.put(key, buildImageDtos(entityImages));
            }
          }

          for (var key : keys) {
            result.putIfAbsent(key, List.of());
          }

          return result;
        });
  }

  private static List<ImageDto> buildImageDtos(List<Image> images) {
    var byType = images.stream().collect(Collectors.groupingBy(Image::getImageType));

    return byType.entrySet().stream()
        .map(
            entry -> {
              var imageType = entry.getKey();
              var variants = entry.getValue();

              var smallVariant =
                  variants.stream().filter(v -> v.getVariant() == ImageSize.SMALL).findFirst();

              var blurHash = smallVariant.map(Image::getBlurHash).orElse(null);
              var aspectRatio =
                  smallVariant.map(v -> (float) v.getWidth() / v.getHeight()).orElse(0f);

              var variantDtos =
                  variants.stream()
                      .map(
                          v ->
                              new ImageVariantDto(
                                  v.getId(),
                                  v.getVariant(),
                                  v.getWidth(),
                                  v.getHeight(),
                                  "/api/images/" + v.getId()))
                      .toList();

              return new ImageDto(imageType, blurHash, aspectRatio, variantDtos);
            })
        .toList();
  }
}
