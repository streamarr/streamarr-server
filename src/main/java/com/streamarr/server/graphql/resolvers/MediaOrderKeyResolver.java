package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dto.LibraryItemPosition;
import com.streamarr.server.graphql.dto.MediaEdge;
import com.streamarr.server.services.pagination.MediaOrder;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class MediaOrderKeyResolver {
  @DgsData(parentType = "MediaEdge", field = "orderKey")
  public CompletableFuture<String> edgeOrderKey(DataFetchingEnvironment environment) {
    MediaEdge<?> edge = environment.getSource();
    return keyFor(edge.order(), environment);
  }

  @DgsData(parentType = "LibraryItemPosition", field = "orderKey")
  public CompletableFuture<String> positionOrderKey(DataFetchingEnvironment environment) {
    LibraryItemPosition position = environment.getSource();
    return keyFor(position.order(), environment);
  }

  private CompletableFuture<String> keyFor(MediaOrder order, DataFetchingEnvironment environment) {
    if (order == null) {
      return CompletableFuture.completedFuture(null);
    }

    DataLoader<MediaOrder, String> loader = environment.getDataLoader("mediaOrderKey");
    return loader.load(order);
  }
}
