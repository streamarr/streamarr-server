package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.graphql.dataloaders.SelectedProfileDataLoader;
import com.streamarr.server.graphql.dto.SelectableProfile;
import com.streamarr.server.graphql.dto.SelectedProfileState;
import com.streamarr.server.graphql.dto.WatchState;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import graphql.schema.DataFetchingEnvironment;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class SelectedProfileStateResolver {

  private static final String SELECTED_PROFILE_STATE = "selectedProfileState";

  private final AuthorizationService authorizationService;

  @DgsData(parentType = "Movie", field = SELECTED_PROFILE_STATE)
  public Optional<SelectedProfileState> movieState(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return stateOf(movie.getId(), CollectableScope.MOVIE_OR_EPISODE);
  }

  @DgsData(parentType = "Episode", field = SELECTED_PROFILE_STATE)
  public Optional<SelectedProfileState> episodeState(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return stateOf(episode.getId(), CollectableScope.MOVIE_OR_EPISODE);
  }

  @DgsData(parentType = "Season", field = SELECTED_PROFILE_STATE)
  public Optional<SelectedProfileState> seasonState(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return stateOf(season.getId(), CollectableScope.SEASON);
  }

  @DgsData(parentType = "Series", field = SELECTED_PROFILE_STATE)
  public Optional<SelectedProfileState> seriesState(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return stateOf(series.getId(), CollectableScope.SERIES);
  }

  @DgsData(parentType = "SelectedProfileState", field = "profile")
  public CompletableFuture<SelectableProfile> profile(DataFetchingEnvironment dfe) {
    SelectedProfileState state = dfe.getSource();
    DataLoader<AuthenticatedIdentity, SelectableProfile> loader =
        dfe.getDataLoader(SelectedProfileDataLoader.NAME);
    return loader.load(state.identity());
  }

  @DgsData(parentType = "SelectedProfileState", field = "watchState")
  public WatchState watchState(DataFetchingEnvironment dfe) {
    SelectedProfileState state = dfe.getSource();
    return new WatchState(state.profileId(), state.titleId(), state.scope());
  }

  private Optional<SelectedProfileState> stateOf(UUID titleId, CollectableScope scope) {
    var identity = authorizationService.currentIdentity();
    if (identity.profileId() == null) {
      return Optional.empty();
    }

    return Optional.of(new SelectedProfileState(identity, titleId, scope));
  }
}
