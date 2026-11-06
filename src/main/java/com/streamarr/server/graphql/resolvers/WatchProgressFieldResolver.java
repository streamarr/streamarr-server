package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.graphql.dataloaders.LastWatchedAtLoaderKey;
import com.streamarr.server.graphql.dataloaders.WatchProgressLoaderKey;
import com.streamarr.server.graphql.dataloaders.WatchStatusLoaderKey;
import com.streamarr.server.graphql.dto.WatchState;
import com.streamarr.server.services.watchprogress.WatchProgressDto;
import graphql.schema.DataFetchingEnvironment;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class WatchProgressFieldResolver {

  private static final String WATCH_STATE = "WatchState";
  private static final String WATCH_PROGRESS_LOADER = "watchProgress";
  private static final String WATCH_STATUS_LOADER = "watchStatus";
  private static final String LAST_WATCHED_AT_LOADER = "lastWatchedAt";

  @DgsData(parentType = WATCH_STATE, field = "watchProgress")
  public CompletableFuture<WatchProgressDto> watchProgress(DataFetchingEnvironment dfe) {
    WatchState state = dfe.getSource();
    DataLoader<WatchProgressLoaderKey, WatchProgressDto> loader =
        dfe.getDataLoader(WATCH_PROGRESS_LOADER);
    return loader.load(
        new WatchProgressLoaderKey(state.profileId(), state.titleId(), state.scope()));
  }

  @DgsData(parentType = WATCH_STATE, field = "watchStatus")
  public CompletableFuture<WatchStatus> watchStatus(DataFetchingEnvironment dfe) {
    WatchState state = dfe.getSource();
    DataLoader<WatchStatusLoaderKey, WatchStatus> loader = dfe.getDataLoader(WATCH_STATUS_LOADER);
    return loader.load(new WatchStatusLoaderKey(state.profileId(), state.titleId(), state.scope()));
  }

  @DgsData(parentType = WATCH_STATE, field = "lastWatchedAt")
  public CompletableFuture<String> lastWatchedAt(DataFetchingEnvironment dfe) {
    WatchState state = dfe.getSource();
    DataLoader<LastWatchedAtLoaderKey, Instant> loader = dfe.getDataLoader(LAST_WATCHED_AT_LOADER);
    return loader
        .load(new LastWatchedAtLoaderKey(state.profileId(), state.titleId(), state.scope()))
        .thenApply(lastWatchedAt -> lastWatchedAt == null ? null : lastWatchedAt.toString());
  }
}
