package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.exceptions.SeasonNotFoundException;
import com.streamarr.server.services.SeriesService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class EpisodeFieldResolver {

  private final SeriesService seriesService;

  @DgsData(parentType = "Episode", field = "files")
  public List<MediaFile> files(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return seriesService.findMediaFiles(episode.getId());
  }

  @DgsData(parentType = "Episode", field = "season")
  public Season season(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    var seasonId = episode.getSeason().getId();
    return seriesService
        .findSeasonById(seasonId)
        .orElseThrow(() -> new SeasonNotFoundException(seasonId));
  }
}
