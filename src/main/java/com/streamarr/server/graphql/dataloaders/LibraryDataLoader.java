package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.library.LibraryQueryService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** Batches the libraries in one response, keyed by library id; a missing library has no entry. */
@DgsDataLoader(name = "library")
@RequiredArgsConstructor
public class LibraryDataLoader implements MappedBatchLoader<UUID, Library> {

  private final LibraryQueryService libraryQueryService;

  @Override
  public CompletionStage<Map<UUID, Library>> load(Set<UUID> libraryIds) {
    return CompletableFuture.completedFuture(
        libraryQueryService.findByIds(libraryIds).stream()
            .collect(Collectors.toMap(Library::getId, Function.identity())));
  }
}
