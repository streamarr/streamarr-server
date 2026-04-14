package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.watchprogress.WatchProgressDto;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "aggregateWatchProgress")
@RequiredArgsConstructor
public class AggregateWatchProgressDataLoader
    implements MappedBatchLoader<WatchProgressLoaderKey, WatchProgressDto> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<WatchProgressLoaderKey, WatchProgressDto>> load(
      Set<WatchProgressLoaderKey> keys) {
    return CompletableFuture.supplyAsync(
        () -> {
          // TODO(#163): Replace with authenticated user ID from Spring Security
          var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

          var result = new HashMap<WatchProgressLoaderKey, WatchProgressDto>();
          var keysByType =
              keys.stream().collect(Collectors.groupingBy(WatchProgressLoaderKey::entityType));

          for (var entry : keysByType.entrySet()) {
            var entityIds =
                entry.getValue().stream().map(WatchProgressLoaderKey::entityId).toList();
            var progressByEntityId = loadByType(userId, entry.getKey(), entityIds);

            for (var key : entry.getValue()) {
              result.put(key, progressByEntityId.get(key.entityId()));
            }
          }

          return result;
        });
  }

  private Map<UUID, WatchProgressDto> loadByType(
      UUID userId, WatchStatusEntityType entityType, java.util.List<UUID> entityIds) {
    return switch (entityType) {
      case SEASON -> watchStatusService.getAggregateProgressForSeasons(userId, entityIds);
      case SERIES -> watchStatusService.getAggregateProgressForSeries(userId, entityIds);
      case DIRECT_MEDIA ->
          throw new UnsupportedOperationException(
              "DIRECT_MEDIA uses the per-media-file watchProgress loader");
    };
  }
}
