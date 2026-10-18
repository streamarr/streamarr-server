package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.library.LibraryCountsService;
import com.streamarr.server.services.library.LibraryCountsService.Counts;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = "libraryItemCount")
@RequiredArgsConstructor
public class LibraryItemCountDataLoader implements MappedBatchLoader<UUID, Counts> {

  private final LibraryCountsService counts;

  @Override
  public CompletionStage<Map<UUID, Counts>> load(Set<UUID> libraryIds) {
    return CompletableFuture.completedFuture(counts.getItemCounts(libraryIds));
  }
}
