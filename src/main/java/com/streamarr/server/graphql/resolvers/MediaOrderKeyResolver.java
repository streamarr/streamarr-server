package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.dto.LibraryItemPosition;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.PaginationOptions;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class MediaOrderKeyResolver {
  private final CursorUtil cursors;

  @DgsData(parentType = "MediaEdge", field = "orderKey")
  public CompletableFuture<String> edgeOrderKey(DataFetchingEnvironment environment) {
    Edge<?> edge = environment.getSource();
    return keyFor(edge.getCursor().getValue(), environment);
  }

  @DgsData(parentType = "LibraryItemPosition", field = "orderKey")
  public CompletableFuture<String> positionOrderKey(DataFetchingEnvironment environment) {
    LibraryItemPosition position = environment.getSource();
    if (position.cursor() == null) {
      return CompletableFuture.completedFuture(null);
    }

    return keyFor(position.cursor(), environment);
  }

  private CompletableFuture<String> keyFor(String cursor, DataFetchingEnvironment environment) {
    var options =
        cursors.decodeMediaCursor(PaginationOptions.builder().cursor(Optional.of(cursor)).build());
    var filter = options.getMediaFilter();
    DataLoader<MediaOrder, String> loader = environment.getDataLoader("mediaOrderKey");
    return loader.load(
        MediaOrder.builder()
            .by(filter.getSortBy())
            .direction(filter.getSortDirection())
            .itemId(options.getCursorId().orElseThrow())
            .value(filter.getPreviousSortFieldValue())
            .build());
  }
}
