package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.services.watchprogress.WatchProgressDto;
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

@DgsDataLoader(name = "aggregateWatchProgress")
@RequiredArgsConstructor
public class AggregateWatchProgressDataLoader
    implements MappedBatchLoader<WatchProgressLoaderKey, WatchProgressDto> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<WatchProgressLoaderKey, WatchProgressDto>> load(
      Set<WatchProgressLoaderKey> keys) {
    return CompletableFuture.completedFuture(loadProgress(keys));
  }

  private Map<WatchProgressLoaderKey, WatchProgressDto> loadProgress(
      Set<WatchProgressLoaderKey> keys) {
    var result = new HashMap<WatchProgressLoaderKey, WatchProgressDto>();
    var keysByBatch =
        keys.stream()
            .collect(Collectors.groupingBy(key -> new Batch(key.profileId(), key.scope())));

    for (var entry : keysByBatch.entrySet()) {
      var entityIds = entry.getValue().stream().map(WatchProgressLoaderKey::entityId).toList();
      var progressByEntityId =
          loadByScope(entry.getKey().profileId(), entry.getKey().scope(), entityIds);

      for (var key : entry.getValue()) {
        result.put(key, progressByEntityId.get(key.entityId()));
      }
    }

    return result;
  }

  private record Batch(UUID profileId, CollectableScope scope) {}

  private Map<UUID, WatchProgressDto> loadByScope(
      UUID profileId, CollectableScope scope, List<UUID> entityIds) {
    return switch (scope) {
      case SEASON -> watchStatusService.getAggregateProgressForSeasons(profileId, entityIds);
      case SERIES -> watchStatusService.getAggregateProgressForSeries(profileId, entityIds);
      case DIRECT_MEDIA ->
          throw new UnsupportedOperationException(
              "DIRECT_MEDIA uses the per-media-file watchProgress loader");
    };
  }
}
