package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class SeasonFieldResolver {

  private final EpisodeRepository episodeRepository;
  private final MediaFileRepository mediaFileRepository;

  @DgsData(parentType = "Season", field = "episodes")
  public List<Episode> episodes(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return episodeRepository.findBySeasonId(season.getId());
  }

  @DgsData(parentType = "Episode", field = "files")
  public List<MediaFile> episodeFiles(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return mediaFileRepository.findByMediaId(episode.getId());
  }
}
