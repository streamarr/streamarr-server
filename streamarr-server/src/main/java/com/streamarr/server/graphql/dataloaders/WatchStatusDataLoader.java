package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.CollectableScope;
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
    return CompletableFuture.completedFuture(loadStatuses(keys));
  }

  private Map<WatchStatusLoaderKey, WatchStatus> loadStatuses(Set<WatchStatusLoaderKey> keys) {
    var result = new HashMap<WatchStatusLoaderKey, WatchStatus>();
    var keysByBatch =
        keys.stream()
            .collect(Collectors.groupingBy(key -> new Batch(key.profileId(), key.scope())));

    for (var entry : keysByBatch.entrySet()) {
      var entityIds = entry.getValue().stream().map(WatchStatusLoaderKey::entityId).toList();
      var statusMap = loadByScope(entry.getKey().profileId(), entry.getKey().scope(), entityIds);

      for (var key : entry.getValue()) {
        result.put(key, statusMap.getOrDefault(key.entityId(), WatchStatus.UNWATCHED));
      }
    }

    return result;
  }

  private record Batch(UUID profileId, CollectableScope scope) {}

  private Map<UUID, WatchStatus> loadByScope(
      UUID profileId, CollectableScope scope, List<UUID> entityIds) {
    return switch (scope) {
      case DIRECT_MEDIA -> watchStatusService.getWatchStatusForDirectMedia(profileId, entityIds);
      case SEASON -> watchStatusService.getWatchStatusForSeasons(profileId, entityIds);
      case SERIES -> watchStatusService.getWatchStatusForSeries(profileId, entityIds);
    };
  }
}
