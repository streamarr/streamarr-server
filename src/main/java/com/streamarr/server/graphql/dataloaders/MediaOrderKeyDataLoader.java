package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaOrderKeys;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "mediaOrderKey")
@RequiredArgsConstructor
public class MediaOrderKeyDataLoader implements MappedBatchLoader<MediaOrder, String> {
  private final MediaOrderKeys keys;

  @Override
  public CompletionStage<Map<MediaOrder, String>> load(Set<MediaOrder> orders) {
    return CompletableFuture.completedFuture(keys.getKeys(orders));
  }
}
