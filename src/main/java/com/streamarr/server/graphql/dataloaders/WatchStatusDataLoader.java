package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "watchStatus")
@RequiredArgsConstructor
public class WatchStatusDataLoader implements MappedBatchLoader<WatchStatusLoaderKey, WatchStatus> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<WatchStatusLoaderKey, WatchStatus>> load(
      Set<WatchStatusLoaderKey> keys) {
    return CompletableFuture.supplyAsync(
        () -> {
          // TODO(#163): Replace with authenticated user ID from Spring Security
          var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

          var result = new HashMap<WatchStatusLoaderKey, WatchStatus>();
          var keysByType =
              keys.stream().collect(Collectors.groupingBy(WatchStatusLoaderKey::entityType));

          for (var entry : keysByType.entrySet()) {
            var entityIds = entry.getValue().stream().map(WatchStatusLoaderKey::entityId).toList();
            var statusMap = loadByType(userId, entry.getKey(), entityIds);

            for (var key : entry.getValue()) {
              result.put(key, statusMap.getOrDefault(key.entityId(), WatchStatus.UNWATCHED));
            }
          }

          return result;
        });
  }

  private Map<UUID, WatchStatus> loadByType(
      UUID userId, WatchStatusEntityType entityType, List<UUID> entityIds) {
    return switch (entityType) {
      case DIRECT_MEDIA -> watchStatusService.getWatchStatusForDirectMedia(userId, entityIds);
      case SEASON -> watchStatusService.getWatchStatusForSeasons(userId, entityIds);
      case SERIES -> watchStatusService.getWatchStatusForSeries(userId, entityIds);
    };
  }
}
