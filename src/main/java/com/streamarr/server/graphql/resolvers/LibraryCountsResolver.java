package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryCountsService.Counts;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class LibraryCountsResolver {

  private final AuthorizationService authorization;

  @DgsData(parentType = "Library")
  public CompletableFuture<Integer> itemCount(DataFetchingEnvironment environment) {
    authorization.requireProfile();
    Library library = environment.getSource();
    DataLoader<UUID, Counts> loader = environment.getDataLoader("libraryItemCount");
    return loader.load(library.getId()).thenApply(Counts::total);
  }
}
