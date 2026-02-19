package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.services.SeriesService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class SeasonFieldResolver {

  private final SeriesService seriesService;

  @DgsData(parentType = "Season", field = "episodes")
  public List<Episode> episodes(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return seriesService.findEpisodes(season.getId());
  }

  @DgsData(parentType = "Episode", field = "files")
  public List<MediaFile> episodeFiles(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return seriesService.findMediaFiles(episode.getId());
  }
}
