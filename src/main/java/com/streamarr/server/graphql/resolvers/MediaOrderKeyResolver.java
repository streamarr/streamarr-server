package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.pagination.MediaOrder;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class MediaOrderKeyResolver {
  @DgsData(parentType = "ContinueWatchingEdge", field = "orderKey")
  @DgsData(parentType = "MediaEdge", field = "orderKey")
  public CompletableFuture<String> edgeOrderKey(DataFetchingEnvironment environment) {
    OrderedEdge<?> edge = environment.getSource();
    DataLoader<MediaOrder, String> loader = environment.getDataLoader("mediaOrderKey");
    return loader.load(edge.order());
  }
}
