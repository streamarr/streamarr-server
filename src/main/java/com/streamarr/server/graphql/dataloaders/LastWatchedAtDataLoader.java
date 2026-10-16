package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "lastWatchedAt")
@RequiredArgsConstructor
public class LastWatchedAtDataLoader implements MappedBatchLoader<LastWatchedAtLoaderKey, Instant> {

  private final WatchStatusService watchStatusService;

  @Override
  public CompletionStage<Map<LastWatchedAtLoaderKey, Instant>> load(
      Set<LastWatchedAtLoaderKey> keys) {
    return CompletableFuture.completedFuture(loadLastWatchedAt(keys));
  }

  private Map<LastWatchedAtLoaderKey, Instant> loadLastWatchedAt(Set<LastWatchedAtLoaderKey> keys) {
    var result = new HashMap<LastWatchedAtLoaderKey, Instant>();
    var keysByBatch =
        keys.stream()
            .collect(Collectors.groupingBy(key -> new Batch(key.profileId(), key.scope())));

    for (var entry : keysByBatch.entrySet()) {
      var itemIds = entry.getValue().stream().map(LastWatchedAtLoaderKey::entityId).toList();
      var lastWatchedAtByItemId =
          watchStatusService.getLastWatchedAtForItems(
              entry.getKey().profileId(), entry.getKey().scope(), itemIds);

      for (var key : entry.getValue()) {
        result.put(key, lastWatchedAtByItemId.get(key.entityId()));
      }
    }

    return result;
  }

  private record Batch(UUID profileId, CollectableScope scope) {}
}
