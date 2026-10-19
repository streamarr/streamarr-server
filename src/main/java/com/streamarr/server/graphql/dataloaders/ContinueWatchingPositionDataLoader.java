package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.watchprogress.ContinueWatchingPosition;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "continueWatchingPosition")
@RequiredArgsConstructor
public class ContinueWatchingPositionDataLoader
    implements MappedBatchLoader<ContinueWatchingPositionLoaderKey, ContinueWatchingPosition> {
  private final ContinueWatchingService continueWatchingService;

  @Override
  public CompletionStage<Map<ContinueWatchingPositionLoaderKey, ContinueWatchingPosition>> load(
      Set<ContinueWatchingPositionLoaderKey> keys) {
    var result = new HashMap<ContinueWatchingPositionLoaderKey, ContinueWatchingPosition>();
    var batches =
        keys.stream()
            .collect(
                Collectors.groupingBy(
                    ContinueWatchingPositionLoaderKey::profileId,
                    Collectors.mapping(
                        ContinueWatchingPositionLoaderKey::itemId, Collectors.toSet())));
    batches.forEach(
        (profileId, itemIds) ->
            continueWatchingService
                .getPositions(profileId, itemIds)
                .forEach(
                    (itemId, position) ->
                        result.put(
                            new ContinueWatchingPositionLoaderKey(profileId, itemId), position)));
    return CompletableFuture.completedFuture(result);
  }
}
