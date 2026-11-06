package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import graphql.schema.DataFetchingEnvironment;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class LibraryOfItemFieldResolver {

  private static final String LIBRARY_LOADER = "library";

  @DgsData(parentType = "Movie", field = "library")
  public CompletableFuture<Library> movieLibrary(DataFetchingEnvironment dfe) {
    return loadLibrary(dfe);
  }

  @DgsData(parentType = "Series", field = "library")
  public CompletableFuture<Library> seriesLibrary(DataFetchingEnvironment dfe) {
    return loadLibrary(dfe);
  }

  private static CompletableFuture<Library> loadLibrary(DataFetchingEnvironment dfe) {
    BaseCollectable<?> item = dfe.getSource();
    // The id of an unloaded library comes from the item's foreign key without a query.
    var libraryId = item.getLibrary().getId();
    DataLoader<UUID, Library> loader = dfe.getDataLoader(LIBRARY_LOADER);
    return loader
        .load(libraryId)
        .thenApply(
            library ->
                Optional.ofNullable(library)
                    .orElseThrow(() -> new LibraryNotFoundException(libraryId)));
  }
}
